# ADR-0014: Job Priority + Preemption

## 상태
적용

## 배경

GPU 클러스터는 비싸고 한정적이다. 요구는 다양함:

- *학습 잡* — 우선순위 낮음, 며칠씩 돌아도 됨, 급한 작업이 들어오면 양보 가능
- *실시간 inference / 긴급 분석* — 우선순위 높음, 결과가 빨리 나와야 함
- *DB migration / 결제 처리* — 진행 중에 죽이면 손실 큼, NEVER 보호 필요

기본 FIFO 큐만으로는 위 시나리오들이 충돌. 학습 잡 5개가 GPU 8장 모두 점유하고 있을 때
inference 잡 들어오면 학습 끝날 때까지 (수 시간/일) 기다려야 함 — 사용자 분노 + SLA 위반.

표준 해법: **priority + preemption**. Slurm 의 job preemption / K8s + Kueue 의
PriorityClass.preemptionPolicy 와 같은 컨셉.

## 결정

### 도메인 추가

```
JobPriority         (이미 존재) — LOW(0) / NORMAL(50) / HIGH(100). weight 로 비교.
PreemptionPolicy    (신규)     — PREEMPTABLE (default) / NEVER
JobStatus.PREEMPTED (신규)     — terminal. CANCELLED 와 구분 (시스템 vs 사용자 의도)

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

2. 정렬:
   - priority 낮은 순
   - 같은 priority 면 *늦게 시작한 순* (덜 진행됐으니 손실 적음)

3. 위에서부터 누적 GPU 가 preemptor.gpuCount 도달할 때까지 victim 추가

4. 모자라면 noop (양보해도 자리 안 남)
```

핵심 invariant:

- **같은/높은 priority 는 절대 preempt 안 함** — priority tier 가 *contract*. NORMAL 끼리
  서로 죽이면 운영 예측 불가. HIGH 가 들어왔을 때만 NORMAL → 양보.
- **NEVER 는 절대 보호** — 이게 있어 사용자가 "이 잡만은 끝까지" 라고 보장 가능.

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

K8s Pod 종료에 시간이 걸림 (graceful shutdown 30초 등). preemptor 를 곧바로 dispatch 하면
GPU 가 아직 점유 중이라 새 Pod 가 Pending — 의미 없음. 다음 scheduler tick (1분 후) 에
일반 dispatch path 가 GPU 비어 있는 걸 보고 정상 시작.

트래픽 늘어 1분 latency 가 문제 되면 *예약 (reserved binding)* 패턴 도입 검토:
victim 의 k8s Pod 종료 watch → 종료 즉시 preemptor dispatch (Kueue 의 admission queue 패턴).

### Preemption history 영속화

```
preemption_history (append-only):
  victim_job_id, victim_owner, victim_priority, victim_gpu_count,
  preemptor_job_id, preemptor_owner, preemptor_priority,
  preempted_at, reason
```

`Job.preempted_at` 만으론 분석 어려움. 영속 history 가 있어야:
- 운영 화면 timeline ("최근 1시간 preemption 발생 N건")
- 빌링 (양보 횟수 따라 보상 / 우선순위 가격 차등)
- 정책 튜닝 (어느 priority 가 너무 자주 죽이나)

### REST 노출

```
POST /api/v1/jobs                        — preemptionPolicy 필드 추가
GET  /api/v1/jobs/{id}                   — 응답에 preempted* 필드 포함
GET  /api/v1/jobs/{id}/preemption-history — victim 시점 (보통 1건)
GET  /api/v1/preemption-history?limit=N  — 운영자 timeline
```

## 대안 검토

- **K8s Kueue 도입** — 이미 production-grade preemption 구현체.
  거부. 클러스터에 새 controller 설치 / 학습 비용 / 우리 도메인과의 매핑 비용. 본 프로젝트의
  job 수 (수백/일) 면 자체 구현이 빠르고 가벼움. 더 큰 규모면 그때 도입 검토.
- **Priority 만 — preemption 없음** — 단순. 거부. HIGH 잡이 LOW 잡 끝날 때까지 하염없이 대기.
  고객 SLA 보장 불가.
- **Preempt 시 자동 requeue (PREEMPTED → QUEUED 다시)** — 사용자가 관여 없이 자동 재실행.
  지금은 안 함. 자동 requeue 는 *재시도 가능한 잡* 만 옳고 (학습 잡), 결과가 외부에 push 된
  잡 (이미 결과 일부 보냄) 에는 위험. 사용자 / 호출자가 JobPreempted 이벤트 받고 직접
  resubmit 결정하는 게 안전. 후속 ADR 에서 자동 requeue 정책 도입 시 명시.

## 결과

- 우선순위 contract 가 진짜로 작동 — HIGH 잡이 LOW 점유 GPU 빼앗아 빠른 시작
- NEVER 로 critical 작업 보호
- 영속 history 로 분석 / 빌링 / 정책 튜닝 기반 마련
- (단점) preemptor 시작 latency ~1.5분 (Pod shutdown + scheduler tick)
- (단점) 단일 leader 가정 — multi-instance 운영 시 ShedLock 필요 (현재는 in-memory race 만)
- (단점) victim 의 진행 분 손실 — 자동 requeue 안 함, 사용자가 결정

## 후속 후보

- 자동 requeue 정책 (재시도 가능한 잡 식별 메타데이터)
- 예약 (reserved binding) — Pod 종료 watch + 즉시 dispatch
- ShedLock 으로 multi-instance preemption scheduler 안전화
- Backfill scheduling (작은 잡이 큰 잡 시작 전에 끼어들어 GPU 활용도 향상 — Slurm 패턴)
- Priority-based 빌링 차등 (HIGH 가 비쌈 / LOW 는 보너스 크레딧 — preemption 보상)
