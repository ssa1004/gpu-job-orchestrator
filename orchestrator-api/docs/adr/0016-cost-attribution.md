# ADR-0016: Cost Attribution / Chargeback — FinOps 기반

## 상태
적용

## 배경

GPU 클러스터 운영에서 *누가 얼마나 썼고 그래서 얼마인지* 가 매달 따라옴:

- 회계 / 빌링 (요금 청구): 팀별 / 사용자별 청구
- FinOps (Financial Operations — 클라우드 / 인프라 비용을 운영 지표로 관리하는 실무) :
  GPU 자원 활용률 분석, 낭비 식별 (예: 큰 GPU 잡고 IDLE 인 잡 패턴)
- 운영 dashboard: top spender (가장 많이 쓴 사용자), 시간대별 사용 패턴
- 사용자 가시성: "내 잡 얼마 나왔지?"
- chargeback (비용을 실제 사용한 팀에게 다시 청구하는 사내 회계 방식)

이전까지 잡의 종착 사실은 `Job` aggregate 에만 있고, 실제 *cost 환산* 은 외부
spreadsheet / 사후 처리 batch (잡이 끝난 뒤 별도 일괄 처리) 로 했음. 결과적으로:

- *언제 쓰던 단가* 인지 불명확 (단가가 바뀌면 과거 잡 cost 재계산이 흐트러짐)
- Job 이 SUCCEEDED 됐는데 cost 가 누락되는 사고 (회계 사고)
- 빌링 / dashboard / 청구서가 각각 *다른 쿼리* 로 같은 잡을 다르게 합산

## 결정

### 별도 aggregate `JobCostRecord` (append-only — 한 번 쓰면 수정·삭제 안 함)

```
job_cost_records
  ├─ job_id              (UNIQUE)
  ├─ owner, gpu_count
  ├─ runtime_millis
  ├─ rate_per_gpu_hour    ← 계산 시점 단가 박제 (FeeSnapshot)
  ├─ computed_cost
  ├─ currency             ← 기본 KRW
  ├─ final_status         ← SUCCEEDED / FAILED / CANCELLED / PREEMPTED 박제
  ├─ job_started_at, job_finished_at
  └─ recorded_at
```

`Job` aggregate 에 컬럼을 더하지 않은 이유:

- `Job` 은 *상태 변경* 의 aggregate, `JobCostRecord` 는 *불변 사실* 의 ledger.
- 가격 정정 / 재계산이 필요할 때 *새 row* 로 표현 (append-only). Job 컬럼 update 는 history 가 사라짐.
- Cost analytics query 가 Job 의 다른 컬럼 (k8s 이름 / errorMessage / resultUri) join 안 해도 빠름.

### 계산 시점 단가 박제 (Snapshot 패턴 — 그 시점의 값을 그대로 보존하는 패턴)

`CostRate` (record VO — 값을 담는 불변 객체) — `costPerGpuHour` 한 필드.
`CostRateProvider` 가 `application.yml` 의 `gwp.cost.gpu-hour-rate-krw` 읽어 제공.

단가가 나중에 바뀌어도 *과거 row 의 `rate_per_gpu_hour` 와 `computed_cost` 는 그대로*.
즉 그 시점의 가격이 그 잡에 적용되었음을 영구히 기록 (FeeSnapshot / PricingSnapshot 패턴).

### 종착 hook — 같은 트랜잭션, 모든 path

```
JobLifecycleService.updateStatusFromCallback  →  costAttribution.recordCost(persisted)
JobLifecycleService.cancel                    →  costAttribution.recordCost(persisted)
JobSubmissionService.dispatchOrFail           →  costAttribution.recordCost(job)   (dispatch 실패)
PreemptionService.preempt                     →  costAttribution.recordCost(persisted)
DependencyResolutionService (cascade-cancel)  →  costAttribution.recordCost(persisted)
```

*같은 트랜잭션* 인 이유: Job SUCCEEDED 가 commit 됐는데 cost record 만 누락되면
**회계 사고**. Job 과 cost record 가 같이 commit / 같이 rollback. 누락 사고 0.

### 멱등성 — DB UNIQUE + DataIntegrityViolationException

`UNIQUE(job_id)` (DB 의 유일성 제약 — 같은 job_id 의 row 가 두 개 이상이면 INSERT 실패)
— 한 잡당 cost record 1개. 호출 측이 두 번 호출해도 두 번째 INSERT 는 DB 가 거절 →
service 가 catch 하고 debug log. race / 재시도 어떤 경로로 들어와도 안전.

### 음수 / corner case 방어

- `runtime` 음수 (clock skew — 서버 / 컨테이너 사이 시계 차이로 종료 시각이 시작 시각
  보다 이른 케이스) → 0 으로 clamp (음수 값을 0 으로 보정)
- `startedAt == null` (dispatch 실패로 RUNNING 안 함) → runtime 0, cost 0, **record 는
  만든다**. *어떤 잡이 dispatch 실패였는지* 운영에서 추적 가능.
- `gpuCount == 0` 도 cost 0 (산술 결과)
- `finalStatus PREEMPTED` 도 그때까지 사용한 GPU-시간 (1 GPU 가 1 시간 동안 점유한 양)
  은 청구 (사용자 잘못 — priority 낮게 제출)

### 조회 책임 분리 — `CostQueryService`

write (`CostAttributionService`) 와 read (`CostQueryService`) 책임 분리.

read 는 4가지 view:
1. **단건** — `findByJobId(uuid)` — 사용자 잡 상세
2. **owner 합계** — `summaryForOwner(owner, from, to)` — 월별 청구서
3. **전체 합계** — `summaryAll(from, to)` — 회계 / 빌링 export
4. **Top spender** — `topSpenders(from, to, topN)` — 운영 dashboard

JPQL constructor expression 으로 `OwnerCostSummary` record 매핑 — 한 번 query 에 4개 SUM 동시 집계.

### 인덱스 — 가장 빈번한 query 우선

```sql
idx_job_cost_owner_time  (owner, recorded_at DESC)   -- owner 별 시간 구간 (월별 청구서)
idx_job_cost_time        (recorded_at DESC)          -- 전체 시간 구간 (월별 export)
```

`UNIQUE(job_id)` 는 자체로 단건 lookup index 역할.

## 대안

### Job 컬럼 추가 (`Job.computedCost`)

탈락 — 이유:

- aggregate 책임 mix (상태 + 회계)
- 단가 변경 후 재계산 시 Job 의 update history 가 사라짐 (append-only 깨짐)
- Cost-only batch / export 가 Job 의 큰 row 를 통째로 읽어야 함

### 별도 batch (사후 처리)

탈락 — 이유:

- 누락 사고 가능성 (batch 가 한 번 실패하면 cost 영영 안 잡힘)
- *언제 쓰던 단가* 박제가 어려움 (사후 batch 시점의 단가가 적용됨)
- Job SUCCEEDED ↔ cost record 사이 시간차 — billing 정확성 흠집

### Outbox event 기반 비동기

후속 검토 — 별도 서비스 (e.g. cost-aggregator) 가 listen 하면 좋지만, 본 ADR 의 경계는 **단일 모듈**.
Outbox 발행 자체는 cost commit 과 같은 트랜잭션이라 비동기 처리는 ledger 자체와 무관.

## 결과

- 같은 트랜잭션 + `UNIQUE(job_id)` 로 cost record 누락 / 중복 차단
- 단가 박제로 과거 cost 가 단가 변경의 영향을 받지 않음
- 4가지 view 가 모두 같은 ledger 를 출처로 일관
- `Job` aggregate 가 회계 책임에서 분리됨
- (단점) 종착 path 가 늘어날 때마다 hook 추가 필요 (현재 5곳). 누락 검출은 통합 테스트로
- (단점) 단일 단가만 지원 — instance type 별 (A100/H100/V100) 다른 rate 는 후속 ADR 의 `GpuRateCard`
- (단점) `recordedAt` 기준 query — 잡이 4월에 시작해서 5월에 끝나면 5월 청구. 정책 다르면 별도 인덱스 추가

## 후속 ADR

- ADR-0017 (예정): instance type 별 GpuRateCard — DB 영속 + 시간대별 rate
- ADR-0018 (예정): cost dashboard streaming — Outbox → Kafka → 실시간 집계
- ADR-0019 (예정): 다단계 currency — KRW / USD / 변환 시점 환율 박제
