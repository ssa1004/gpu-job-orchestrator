# ADR-0017: K8s Lease 기반 Leader Election (ShedLock 보강)

## 상태
적용

## 배경

다중 인스턴스 환경에서 같은 `@Scheduled` 메서드를 한 번에 한 노드만 돌리는 직렬화 (mutual
exclusion — 동시 실행 금지) 가 필요하다. 대상은 세 스케줄러:

- `OutboxRelay` — 매 1초 outbox polling → Kafka publish
- `PreemptionScheduler` — 매 1분 preemption 평가
- `DependencyScanScheduler` — 매 1분 WAITING_DEPS 잡 보강 스캔

지금까지 `ShedLock` (DB row 락 기반 라이브러리, ADR 없는 채로 도입됨) 으로 처리해 왔다.
row 한 개를 `SELECT ... FOR UPDATE` 로 잠그고 메서드 종료 시 lock_until 을 갱신하는
방식. 동작은 한다. 그러나 K8s 위에 배포된 시스템 전체를 보면 이 메커니즘은 환경에 비해
어색하다.

- DB 가 down 이면 leader election 자체가 마비 (스케줄러 정지)
- Pod 죽음을 DB 가 모른다 — `lock_until` 만료까지 다른 인스턴스가 takeover 못 함 (보통 1~5분)
- ShedLock 테이블 자체가 sysadmin 으로부터 왜 있는지 질문을 받기 쉽다 (도메인 무관)

K8s 자체가 이미 합의된 분산 락 서비스를 제공한다. `coordination.k8s.io/Lease` 리소스가
그것이고, etcd 가 backend 라 Raft consensus 기반으로 강한 일관성을 보장한다. K8s
컨트롤 플레인의 표준 컴포넌트들도 같은 리소스로 leader 를 선출한다.

## 결정

### 추상화 — `LeaderElector` 인터페이스

```java
public interface LeaderElector {
    boolean isLeader();
}
```

스케줄러 진입 직전에 `if (!leaderElector.isLeader()) return;` — 비-리더 인스턴스는 즉시 끝.
DB 락 / API 호출 / Kafka send 어떤 비용도 안 듦.

세 가지 구현:

- `KubernetesLeaseLeaderElector` — fabric8 의 `LeaderElector` + `LeaseLock`. 운영.
- `ShedLockLeaderElector` — 항상 true. 기존 ShedLock 호환 / 점진 마이그레이션.
- `AlwaysLeaderElector` — 단일 인스턴스 dev / 단위 테스트.

`gwp.leader.mode` 설정으로 선택. dev (`shedlock`), prod (`lease`).

### K8s Lease 동작

```
   Pod-A (leader)              Pod-B (standby)             Pod-C (standby)
       │                              │                           │
       │  매 2s renew (retryPeriod)   │                           │
       ▼                              │                           │
   ┌────────────────────────┐         │                           │
   │ Lease(name, holderId,  │         │                           │
   │ leaseDuration=15s,     │◄───watch───────────────watch────────┤
   │ renewTime=now)         │         │                           │
   └────────────────────────┘         │                           │
       │                              │                           │
       Pod-A 죽음 (OOM)               │                           │
       │                              │                           │
       Lease 만료 (15s 후)             │                           │
       ▼                              ▼                           │
   ┌────────────────────────┐                                     │
   │ Lease (만료, 비어있음)   │◄──── 동시 acquire 시도 ────────────┘
   └────────────────────────┘
                        ▼
                    Pod-B 또는 Pod-C 가 새 holder 가 됨 (etcd 가 한 쪽만 commit)
```

표준 시간값:

| 파라미터 | 값 | 의미 |
|---|---|---|
| `leaseDuration` | 15s | 리더가 lease 보유 주장 유효기간 |
| `renewDeadline` | 10s | 현 리더가 이 시간 안에 갱신 못 하면 리더십 잃음 |
| `retryPeriod` | 2s | 비-리더가 lease 잡으려 시도하는 주기 |

비율 (15 / 10 / 2) 의 트레이드오프. 너무 짧으면 (5s) network blip 한 번에 leader 가
바뀌어 churn (잦은 전환). 너무 길면 (60s) takeover 가 느려져 SLA 영향이 생긴다. 15s 는
client-go 권장 기본값으로 검증된 수치다.

### 식별자 (holderIdentity)

K8s Downward API 로 `metadata.name` (Pod 이름) 을 환경변수로 주입:

```yaml
env:
  - name: POD_NAME
    valueFrom:
      fieldRef: { fieldPath: metadata.name }
```

application.yml: `gwp.leader.identity: ${POD_NAME:}` — 누락 시 hostname fallback.

운영자가 `kubectl get lease -n gwp gwp-orchestrator-leader -o yaml` 로 지금 누가 리더인지
한 줄로 확인할 수 있다. ShedLock 의 `locked_by` 와 비슷하지만 K8s native 라 도구
(k9s / Lens) 가 자동으로 인식한다.

### 조합 — Lease + ShedLock 둘 다 살아있음

운영 yml 은 `mode=lease` 지만 `@SchedulerLock` 어노테이션도 그대로 둠. 이유:

1. **점진적 마이그레이션** — 새 elector 가 안정성 검증 끝날 때까지 ShedLock 가 belt-and-suspenders
   (이중 안전장치) 역할.
2. **lease-duration 갭** — 리더가 죽고 lease 만료 직전 (예: 14s 시점) 에 새 리더가
   나타나면 둘 다 자기가 리더라고 믿는 split-brain 의 짧은 윈도우가 생긴다. 이 윈도우 안에
   두 인스턴스의 tick 이 우연히 겹치면 ShedLock 가 두 번째를 거절해 안전이 보장된다.
3. **K8s API 일시 단절** — lease 갱신 실패 → onStopLeading → leader=false. 그동안 ShedLock 가
   직렬화 담당.

향후 안정성 확신이 들면 ShedLock annotation 제거 가능 (별도 ADR / Migration). 지금은 양쪽
다 살아있는 게 안전.

### Fail-safe — false 우선

K8s API server 일시 끊김 → lease 갱신 실패 → `onStopLeading` callback → `leader=false`.
이 인스턴스는 비-리더로 동작 (스케줄러 즉시 return). API 회복되면 retry-period (2s) 안에 다시
acquire 시도.

핵심은 불확실할 때는 false 로 둔다는 점. 두 인스턴스가 동시에 자기가 리더라고 믿는
split-brain 이 outbox 중복 발행 / preemption race 등 데이터 사고로 이어질 수 있으므로
보수적으로 false 를 유지한다.

### RBAC

```yaml
apiGroups: ["coordination.k8s.io"]
resources: ["leases"]
verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
```

namespace=gwp 로 제한 — ClusterRole 이 아닌 namespace Role 로 권한 폭증을 방지. fabric8
가 lease 의 acquire / renew / release 에 필요로 하는 verb 셋이다.

## 대안

### ShedLock 만 유지

탈락 — 이유:

- DB 의존성 (DB down 시 leader election 정지)
- Pod 죽음 감지가 lock_until 만료 후에야 가능 (보통 1~5분 takeover 지연)
- K8s native 가 아닌 별도 메커니즘이라 운영 가시성 ↓ (`kubectl` 로 못 봄)

### Redis 분산 락 (Redlock)

탈락 — 이유:

- Redis 클러스터 의존성 추가 (이 시스템은 캐시용 Redis 만 있음, 락 전용은 안 씀)
- Redlock 알고리즘의 정확성 논쟁 (Martin Kleppmann vs antirez 의 잘 알려진 디베이트)
- K8s 위에 있는데 K8s 외부 의존성을 더하는 게 부자연스러움

### Spring Integration Leader Election (Hazelcast / Zookeeper)

탈락 — 이유:

- 외부 의존성 (Hazelcast 클러스터 / Zookeeper ensemble) 운영 비용
- K8s 위에 있다면 어차피 etcd 가 이미 backend 로 살아있음 — 또 다른 합의 시스템 운영은 중복

### Kubernetes ConfigMap 락 (예전 패턴)

탈락 — 이유:

- ConfigMap 은 lease 가 아니라 임의 데이터 — race condition 처리가 직접 구현 (resourceVersion 관리)
- K8s 1.14 부터 정식 Lease 리소스가 도입됨 (이전 까지의 패턴)
- fabric8 에 `ConfigMapLock` 도 있지만 비추천 (deprecated 방향)

## 결과

- DB down 에 leader election 영향 없음 (K8s API server 만 살아있으면 됨)
- Pod 죽음 시 lease 만료 (15s) + retry-period (2s) = ~17s 안에 takeover. ShedLock 의
  default 1~5분 대비 대폭 단축.
- `kubectl get lease` 로 지금 누가 리더인지 한 줄로 확인 가능 (운영 가시성 ↑)
- 외부 의존성 0 — 이미 있는 K8s API / etcd 만 사용
- ShedLock 도 살아있어 점진 마이그레이션 안전
- (단점) RBAC 추가 필요 — `coordination.k8s.io/leases` 권한
- (단점) K8s 없는 환경 (로컬 dev / docker-compose) 에서는 `mode=shedlock` 으로 전환 필요
- (단점) 두 메커니즘이 겹쳐 살아있어 코드 복잡도 ↑ (인터페이스 + 어댑터 + K8s 구현)

## 운영 절차

### 리더 확인

```bash
kubectl get lease -n gwp gwp-orchestrator-leader -o jsonpath='{.spec.holderIdentity}'
# → orchestrator-api-7d4c5b6f8-xj7p2
```

### 강제 takeover (운영 사고 대응)

```bash
# 현 리더의 Pod 가 hang 인데 lease 가 안 풀릴 때 (드물게).
kubectl delete lease -n gwp gwp-orchestrator-leader
# → 다음 retry-period (2s) 안에 모든 Pod 가 race acquire, 한 Pod 가 새 리더가 됨.
```

### 메트릭

micrometer 로 `gwp_orchestrator_leader_state{instance=...}` 게이지를 추가하면 Grafana
에서 현재 어떤 Pod 가 리더인지 시각화할 수 있다. (후속 작업)

## 용어 풀이 (쉽게)

- **leader election (리더 선출)** — 같은 앱이 여러 대 떠 있을 때 정기 작업을 한 대만 돌리도록 그중 "대장" 한 명을 정하는 것. 대장이 죽으면 다른 대가 승계한다.
- **Lease (임대)** — K8s가 제공하는 "이 자리는 지금 내가 맡는다"는 시한부 예약표. 정해진 시간 안에 갱신 안 하면 자동으로 풀려 다른 대가 가져간다(회의실 예약이 시간 지나면 풀리듯).
- **etcd / Raft consensus** — K8s가 클러스터 상태를 저장하는 핵심 DB(etcd)와, 여러 노드가 "누가 리더인가"를 두고 한 답에 합의하는 규칙(Raft). 동시에 둘이 자리를 잡아도 한 쪽만 인정된다.
- **split-brain (분열 뇌)** — 두 인스턴스가 동시에 "내가 대장"이라고 착각하는 위험한 순간. 그러면 같은 작업이 두 번 돌아 데이터 사고가 날 수 있다.
- **fail-safe (불확실하면 false)** — 리더인지 확실치 않으면 일단 "나는 리더 아님"으로 두는 보수적 정책. 둘 다 대장이라 믿는 split-brain보다 잠깐 아무도 안 도는 게 낫다.

## 후속 ADR

- [ADR-0018](0018-otel-kafka-trace-propagation.md): OpenTelemetry context propagation
  through Kafka headers — leader 가 보낸 record 의 trace 가 consumer 에서 끊기지 않게. (적용)
- [ADR-0019](0019-prometheus-exemplars.md): Prometheus Exemplars — leader transition /
  scheduler tick 의 trace 를 metric 에서 한 번 클릭으로 jump. (적용)
