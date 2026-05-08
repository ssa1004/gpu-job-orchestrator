# ADR-0024: Graceful Shutdown — SIGTERM 후 in-flight 처리 단계화

## 상태
적용

## 배경

K8s 가 Pod 종료 시 SIGTERM 을 보낸다. default 30초 후에도 살아있으면 SIGKILL.
Spring Boot 의 default 동작은 SIGTERM 즉시 web container 를 멈추고 응답 중인 요청
을 끊고 종료 — 사용자 입장에서 *random 5xx*. 배포 / 스케일 다운 / node drain 마다
일정 비율의 요청이 깨진다.

배포가 하루에 5번이고 평균 RPS 가 100 이라면, 매 배포마다 500ms x 100 = 50개의
in-flight 요청이 강제 종료. 사용자는 *왜 가끔 5xx 나는지* 추적 불가능. 운영의 미들미들한
잡음으로 영원히 남는다.

표준 해법: **graceful shutdown**. SIGTERM 후

1. 새 요청은 거절 (readiness=unready 로 service mesh 에 전파)
2. in-flight 요청 처리 완료 대기
3. Kafka consumer commit / DB connection 정리
4. JVM exit

이 흐름이 30초 안에 끝나면 사용자 입장에서 *5xx 0건*.

## 결정

### 1) 4단계 셧다운 시퀀스

```
T=0    SIGTERM 도착
       ├── K8s 가 endpoint 에서 Pod 빼는 *과정* 시작 (network propagation 시작)
       └── GracefulShutdownLifecycle.stop() 진입 (phase=Integer.MIN_VALUE+100 이라 가장 먼저)
            └── readiness=REFUSING_TRAFFIC publish
                 └── /actuator/health/readiness 가 OUT_OF_SERVICE 응답
                      └── 다음 readiness probe (5s 주기) 에서 K8s 가 endpoint 즉시 제외

T=0~5s preStop hook: sleep 5
       service mesh / kube-proxy iptables 갱신 propagation 시간 확보.
       이 동안 들어오는 새 요청은 *아직 받음* (Spring 이 거절 안 함).
       그러나 LB 입장에서는 이미 endpoint 가 제거되어 새 요청이 거의 안 옴.

T=5s   preStop 종료 → K8s 가 컨테이너에 SIGTERM 전달
       └── Spring Boot 의 graceful shutdown 시작
            ├── web container 가 새 요청 거절 (Connection: close)
            ├── in-flight 요청은 timeout-per-shutdown-phase=25s 동안 처리
            └── 다른 SmartLifecycle bean / @PreDestroy 호출
                 ├── ScheduledTaskRegistrar 가 다음 tick 안 시작
                 ├── KafkaTemplate 의 producer flush + close
                 ├── Hikari connection pool 정리
                 └── KubernetesLeaseLeaderElector.stop() — lease release

T=30s  terminationGracePeriodSeconds 만료 → SIGKILL (도달하면 안 됨)
```

### 2) 시간 예산

| 항목 | 시간 | 근거 |
|---|---|---|
| `terminationGracePeriodSeconds` | 30s | K8s default. 변경 시 운영팀과 합의 필요 |
| `preStop sleep` | 5s | service mesh propagation 평균 (Istio docs / kube-proxy iptables 갱신 측정값) |
| `spring.lifecycle.timeout-per-shutdown-phase` | 25s | 30 - 5 = 25, JVM exit 여유 약간 |
| 평균 in-flight 요청 max latency | ~3s | API 의 P99 응답시간 — 25s 면 충분 여유 |

### 3) `GracefulShutdownLifecycle` 이 *가장 먼저* stop() 되는 이유

Spring 의 SmartLifecycle 은 phase 값으로 stop 순서가 결정된다 (낮을수록 먼저 stop).
다른 빈은 default phase=0 인데, `GracefulShutdownLifecycle.getPhase()=Integer.MIN_VALUE+100`
으로 설정 → 다른 어떤 cleanup 보다 *먼저* readiness 를 빼는 게 보장된다.

readiness 가 늦게 빠지면, 그 사이 들어오는 새 요청이 in-flight 풀에 들어가 우리가
처리해야 할 요청 수가 계속 증가. timeout-per-shutdown-phase 안에 못 끝남.

### 4) 왜 SmartLifecycle 이고 @PreDestroy 가 아닌가

@PreDestroy 는 ApplicationContext close 의 *마지막* 단계 — 이미 web container 가
멈춘 후 호출된다. 그때 readiness 를 publish 해도 의미 없음 (web container 가 응답
못 함).

SmartLifecycle.stop() 은 ApplicationContext close 의 *시작* 단계라 web container 가
살아있는 동안 publish 가능하고, 다음 readinessProbe 응답에 즉시 반영.

### 5) 왜 K8s preStop hook 만으로 부족한가

preStop hook 은 컨테이너에 SIGTERM 보내기 *전* 에 실행되는 K8s 단계. `sleep 5` 만
넣으면 그 동안 endpoint 갱신 propagation 은 진행되지만, *Spring 자체는* 여전히 ready
라고 답한다. 어떤 LB 는 health-check 결과를 endpoint state 보다 우선시 — 결과적으로
이 Pod 가 endpoint 에서 안 빠질 수 있다.

SmartLifecycle 로 readiness 를 *Spring 안에서 명시적으로* 빼야 *모든 LB* 에 대해
일관된 신호를 보낸다.

### 6) Outbox / Scheduler 들의 graceful 처리

- **OutboxRelay** — `@Scheduled` 가 `fixedDelay` 라 다음 tick 의 시작이 SmartLifecycle.stop()
  으로 차단됨. 진행 중 tick 의 batch 처리는 그대로 끝남 — at-least-once 보장.
- **PreemptionScheduler / DependencyScanScheduler** — 같은 메커니즘. tick 한 번이
  최장 1분 (PreemptionScheduler) 인데 timeout 25s 안에 못 끝나면 강제 종료될 수
  있지만, 다음 인스턴스가 leader 가 되면서 같은 작업 재실행 (멱등).
- **KubernetesLeaseLeaderElector** — `@PreDestroy` 에서 `releaseOnCancel=true` 로
  lease 즉시 양보. 다른 인스턴스가 lease-duration 만큼 기다리지 않고 즉시 takeover.

## 왜 over-spec 아닌가

이건 *기본 위생*. K8s 위 어떤 서비스든 graceful shutdown 이 없으면 매 배포마다 일정
확률로 5xx 가 나온다. 안 하면 시나리오 명확:

- 배포 5번/일 × in-flight ~50건 × 30 영업일 = **월 7,500건의 random 5xx**.
- 사용자가 retry 로 흡수해도 신뢰 손실. SLO 99.9% 면 월 43분 outage budget 이 그냥 소진.

추가 비용:
- 코드 ~80 라인 (SmartLifecycle bean + readiness publisher 통합)
- yml ~10 라인 (`server.shutdown=graceful`, `timeout-per-shutdown-phase`)
- K8s manifest ~5 라인 (preStop sleep, terminationGracePeriodSeconds)
- 단위 테스트 ~50 라인

## 다시 검토할 시점

- gRPC / WebSocket / SSE 같은 long-lived connection 도입 시 — graceful 의 의미가
  달라짐 (서버 push 종료 신호를 클라이언트에 보내는 추가 단계 필요).
- Kafka consumer 가 늘어나면 — consumer rebalance 가 graceful 안에 끝나야 함.
  group.instance.id 와 max.poll.interval.ms 설계 추가 필요.
- 다중 인스턴스 → 단일 인스턴스 (single-node debugging) 시 — readiness 빼면 트래픽이
  갈 곳 없음. 운영 모드 / dev 모드 분기 검토.

## 참고 자료

- Spring Boot reference — Graceful Shutdown
  https://docs.spring.io/spring-boot/docs/3.3.x/reference/html/web.html#web.graceful-shutdown
- Kubernetes — Termination of Pods
  https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-termination
- ADR-0023 (3 probe) — readiness 빼는 메커니즘.
- ADR-0017 (K8s Lease leader election) — `releaseOnCancel` 의 빠른 takeover.
