# ADR-0014: Job Priority + Preemption

## 상태
적용

## 배경

GPU 클러스터는 비싸고 한정적이다. 요구는 다양하다.

- 학습 잡 — 우선순위 낮음. 며칠씩 돌아도 되고, 급한 작업이 들어오면 양보 가능.
- 실시간 inference / 긴급 분석 — 우선순위 높음. 결과가 빨리 나와야 한다.
- DB migration / 결제 처리 — 진행 중에 죽이면 손실 크다. NEVER 보호가 필요하다.

기본 FIFO 큐 (먼저 들어온 요청을 먼저 처리하는 단순 큐) 만으로는 위 시나리오들이 충돌.
학습 잡 5개가 GPU 8장 모두 점유하고 있을 때 inference 잡 들어오면 학습 끝날 때까지
(수 시간 / 일) 기다려야 함 — SLA (Service Level Agreement, 고객과 맺은 약속 수치) 위반.

표준 해법: **priority + preemption (우선순위가 높은 잡이 들어오면 낮은 잡의 GPU 를
강제로 회수)**. HPC 전용 잡 스케줄러나 K8s 배치 큐잉 시스템에서 오랫동안 검증된 패턴이다.
컨셉 자체는 동일.

## 결정

### 도메인 추가

```
JobPriority         (이미 존재) — LOW(0) / NORMAL(50) / HIGH(100). weight 로 비교.
PreemptionPolicy    (신규)     — PREEMPTABLE (default, 양보 가능) / NEVER (절대 보호)
JobStatus.PREEMPTED (신규)     — terminal (더 이상 변경 불가한 종료 상태).
                                 CANCELLED (사용자가 취소) 와 구분 (시스템 vs 사용자 의도)

Job 에 추가 필드:
  preemptionPolicy
  preemptedAt
  preemptedByJobId
  preemptedReason
```

### 알고리즘 (PreemptionEvaluator)

```
input:  preemptor (QUEUED), activeJobs (RUNNING + DISPATCHING)
output: PreemptionDecision (preemptor + 죽일 victim 들)

1. 후보 = activeJobs 중에서:
   - PREEMPTABLE
   - priority < preemptor.priority   (같은 priority 는 절대 안 죽임 — contract)

2. 정렬 (죽이기 좋은 순서):
   - priority 낮은 순 (LOW 먼저)
   - 같은 priority 면 늦게 시작한 잡 (덜 진행됐으니 손실 적음) 먼저
   - DISPATCHING (startedAt = null, 진행도 0%) 이 가장 먼저

3. 위에서부터 누적 GPU 가 preemptor.gpuCount 도달할 때까지 victim 추가

4. 모자라면 noop — 가능한 victim 다 죽여도 GPU 가 부족하면 그냥 큐 대기.
   (어차피 preempt 해봐야 dispatch 못 함, 멀쩡한 잡만 죽이는 꼴)
```

invariant (절대 깨지면 안 되는 불변 조건):

- **같은 / 높은 priority 는 절대 preempt 안 함** — NORMAL 끼리 서로 죽이면 운영 예측 불가.
  HIGH 가 들어왔을 때만 NORMAL → 양보.
- **NEVER 는 절대 보호** — 사용자가 "이 잡만은 끝까지" 를 명시할 수 있게 하는 옵션.

### Worker 흐름 (PreemptionService)

```
@Scheduled(매 분)
  ↓
runOnce()  [한 트랜잭션]
  ↓
1. QUEUED 중 priority 높은 N개 픽업 (preemptor 후보)
2. ACTIVE PREEMPTABLE 잡 전체 로드
3. 각 preemptor:
     decision = evaluate(preemptor, candidates)
     for victim in decision.victims:
       k8sDispatcher.cancel(victim.k8sJobName)   # graceful shutdown 시작
       victim.markPreempted(...)                 # PREEMPTED 전이
       jobs.save(victim)
       history.save(...)                         # 영속 timeline
       outboxWriter.write(JobPreempted)          # 이벤트 발행
     candidates -= decision.victims              # 다음 preemptor 가 같은 victim 안 잡게
```

### 왜 preemptor 를 즉시 dispatch 하지 않나

K8s Pod 종료에 시간이 걸린다 (graceful shutdown — 진행 중인 작업을 마무리하고 깔끔하게
종료하는 데 30초 등). preemptor (자리를 차지하러 들어오는 잡) 를 곧바로 dispatch 하면
GPU 가 아직 점유 중이라 새 Pod 가 Pending (스케줄 대기) 으로 떨어진다. 의미가 없으므로
다음 scheduler tick (1분 후) 에 일반 dispatch path 가 GPU 가 비어 있는 것을 보고 정상
시작하도록 둔다.

트래픽이 늘어 1분 latency 가 문제 되면 예약 (reserved binding) 패턴을 검토할 수 있다.
victim 의 k8s Pod 종료 watch → 종료 즉시 preemptor dispatch 흐름. 잡 admission queue 를
별도로 두고 자원을 미리 예약해 두는 일반적인 배치 큐잉 패턴이다.

### Preemption history 영속화

```
preemption_history (append-only — 한 번 쓰면 수정·삭제 안 함):
  victim_job_id, victim_owner, victim_priority, victim_gpu_count,
  preemptor_job_id, preemptor_owner, preemptor_priority,
  preempted_at, reason
```

`Job.preempted_at` 만으론 분석 어려움. 영속 history 가 있어야:
- 운영 화면 timeline ("최근 1시간 preemption 발생 N건")
- 빌링 (양보 횟수 따라 보상 / 우선순위 가격 차등 — chargeback)
- 정책 튜닝 (어느 priority 가 너무 자주 죽이나)

### REST 노출

```
POST /api/v1/jobs                        — preemptionPolicy 필드 추가
GET  /api/v1/jobs/{id}                   — 응답에 preempted* 필드 포함
GET  /api/v1/jobs/{id}/preemption-history — victim 시점 (보통 1건)
GET  /api/v1/preemption-history?limit=N  — 운영자 timeline
```

## 대안 검토

- **외부 배치 큐잉 시스템 도입** — production-grade preemption 구현체가 이미 존재한다.
  거부. 클러스터에 새 controller 설치 / 학습 비용 / 우리 도메인과의 매핑 비용이 따른다.
  본 프로젝트 규모 (수백 잡/일) 면 자체 구현이 빠르고 가볍다. 규모가 더 커지면 그때
  도입을 다시 검토.
- **Priority 만 — preemption 없음** — 단순. 거부. HIGH 잡이 LOW 잡 끝날 때까지 하염없이 대기.
  고객 SLA 보장 불가.
- **Preempt 시 자동 requeue (PREEMPTED → QUEUED 다시)** — 사용자가 관여 없이 자동 재실행.
  지금은 안 한다. 자동 requeue 는 재시도가 안전한 잡 (학습 잡 등) 에서만 옳고, 결과가
  외부로 이미 일부 push 된 잡에서는 위험하다. 사용자 / 호출자가 JobPreempted 이벤트를
  받고 직접 resubmit 을 결정하는 게 안전하다. 후속 ADR 에서 자동 requeue 정책 도입 시 명시.

## 결과

- 우선순위 단계별 동작 — HIGH 잡이 LOW 점유 GPU 회수해 빠른 시작
- NEVER 로 critical 작업 보호
- 영속 history 로 분석 / 빌링 / 정책 튜닝 기반 마련
- (단점) preemptor 시작 latency ~1.5분 (Pod shutdown + scheduler tick)
- (단점) 단일 leader 가정 (인스턴스 1대만 스케줄러 돌리는 가정) — multi-instance 운영 시
  ShedLock (DB 행 락 등을 이용해 한 번에 한 인스턴스만 스케줄러를 돌리도록 보장하는
  라이브러리) 필요 (현재는 in-memory race 만)
- (단점) victim (양보 당한 잡) 의 진행 분 손실 — 자동 requeue (재제출) 안 함, 사용자가 결정

## 후속 후보

- 자동 requeue 정책 (재시도 가능한 잡 식별 메타데이터)
- 예약 (reserved binding) — Pod 종료 watch + 즉시 dispatch
- ShedLock 으로 multi-instance preemption scheduler 안전화
- Backfill scheduling (큰 잡 대기 시간에 작은 잡이 끼어들어 GPU 활용도 향상 — HPC 스케줄러
  들의 일반 패턴)
- Priority-based 빌링 차등 (HIGH 가 비쌈 / LOW 는 보너스 크레딧 — preemption 보상)
