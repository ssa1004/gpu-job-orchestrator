# ADR-0026: DLQ 관리 콘솔 백엔드 API

## 상태

적용

## 배경

ADR-0004 의 Outbox 패턴, ADR-0025 의 Resilience4j circuit + retry 까지 갖춰 두면
대부분의 메시지 발행 실패는 자동으로 회복된다. 그러나 *영구적 실패* (poison pill —
페이로드 자체가 깨졌거나, 다운스트림 도메인의 영구 거절 등) 는 자동 회복 path 가
없다. 현재 코드는 `OutboxMessage.deadLetteredAt` 으로 격리해 head-of-line blocking
만 막아두고, 실제 운영자 동작 (페이로드 조사 → 재발행 또는 폐기) 은 DB 접근이라는
*수동 절차* 로 위임되어 있다.

운영 사고가 났을 때 SSH → DB 접속 → SQL UPDATE 로 dead_lettered_at 을 NULL 로
바꾸는 흐름은 다음 문제가 있다.

- **권한 / audit 부재**: 누가, 언제, 왜 replay 또는 discard 했는지 기록이 없다.
- **멱등성 부재**: 같은 운영자가 두 번 실행, 또는 두 운영자가 동시에 실행해도 막을
  방법이 없다.
- **bulk 사고 위험**: `UPDATE WHERE 1=1` 한 줄로 모든 DLQ 메시지가 한꺼번에 다시
  발행되어 broker 가 다시 죽거나, downstream 이 다시 거절해서 같은 사고가 증폭된다.
- **다른 source 와의 통합 부재**: outbox 외에도 worker callback retry exhausted,
  K8s dispatch 실패, DAG eval 실패 등 *saga 단계별로 흩어진 dead-letter 가 있는데*
  한 화면에서 보기 어렵다.

cross-repo 표준 패턴이 이미 자리잡았다.

- **notification ADR-0015** — DLQ admin v1 정의. 8 endpoint + audit + scope 별 rate
  limit.
- **billing ADR-0033** — `DlqSource` enum + `DlqStats.byCustomer` 차원 추가. 한
  customer 의 잘못된 입력으로 같은 source 의 메시지가 한꺼번에 stuck 되는 패턴 감지.
- **market ADR-0028** — `DlqSource` 6값 + `bySku` 차원 + bulk 의 `source` 필수.
  한 번에 한 source 만 조작하도록 강제 — replay 의 *의미* 가 source 마다 다르기
  때문 (메시지 재발행 / endpoint 재호출 / DAG eval 재실행 등).

이번 ADR 은 이 4번째 적용이다. 단일 모듈 100% Kotlin (Lombok 혼재) 구조에 신규
sub-package 하나만 추가하는 방식.

## 결정

`/api/v1/admin/dlq/*` 8 endpoint 를 admin 전용으로 노출, 단일 모듈
`orchestrator-api` 안에 `dlq/` sub-package 하나 신설. 도메인 모델 / 라이프사이클은
*변경하지 않는다*.

### 1) Endpoint 표 (8 endpoint)

| Method | Path | 의미 |
|---|---|---|
| GET | `/api/v1/admin/dlq?source=&topic=&from=&to=&errorType=&cursor=&size=` | 목록 (cursor pagination) |
| GET | `/api/v1/admin/dlq/{messageId}` | 단건 (전체 payload) |
| POST | `/api/v1/admin/dlq/{messageId}/replay` | 단건 replay (`Idempotency-Key` 헤더) |
| POST | `/api/v1/admin/dlq/{messageId}/discard` | 단건 discard (`reason` 필수, audit) |
| POST | `/api/v1/admin/dlq/bulk-replay?confirm=` | bulk replay (`confirm=true` 없으면 dry-run) |
| POST | `/api/v1/admin/dlq/bulk-discard?confirm=` | bulk discard (`confirm=true` 없으면 dry-run) |
| GET | `/api/v1/admin/dlq/bulk-jobs/{jobId}` | bulk job 폴링 |
| GET | `/api/v1/admin/dlq/stats?from=&to=&bucket=PT1H` | 통계 (source/topic/owner/gpuClass/errorType/시간 bucket) |

### 2) `DlqSource` — gpu 도메인의 saga 단계 5값

market ADR-0028 의 6값 (TXN_LOG / ORDER_EVENT 등) 과 위치는 같지만 도메인 어휘가 다르다.

```kotlin
enum class DlqSource {
    JOB_DISPATCH,   // KubernetesJobDispatcher 호출 실패
    CALLBACK,       // worker → orchestrator 콜백 4xx/5xx
    OUTBOX,         // OutboxRelay max-attempts 도달
    PREEMPTION,     // PreemptionService 의 victim 선정/cancel 실패
    DAG_EVAL,       // DependencyResolutionService 의 child promote 실패
}
```

`bulk-replay` / `bulk-discard` 는 source 필수 — 한 번에 한 단계만 조작. 같이 묶으면
*무엇이 어떻게 replay 되는지* (메시지 재발행 vs endpoint 재호출 vs DAG eval 재실행)
가 섞여 추적이 불가능해진다. market ADR-0028 패턴.

### 3) `DlqStats` — gpu 특유 차원 `byOwner` + `byGpuClass`

notification 은 `byTenant`, billing 은 `byCustomer`, market 은 `bySku` 차원을 추가했다.
gpu 의 동치 차원은 **owner** (research team / billing tenant — 같은 사용자의 job 이
한꺼번에 stuck 되는 패턴) + **gpuClass** (H100 / A100 / V100 — class 별 dispatch
failure 분포로 spot 회수율 / driver 호환성 신호 + preemption 정책 튜닝 입력).

```kotlin
data class DlqStats(
    val from: Instant, val to: Instant,
    val total: Long,
    val bySource: Map<DlqSource, Long>,
    val byTopic: Map<String, Long>,
    val byErrorType: Map<String, Long>,
    val byOwner: Map<String, Long>,       // gpu 특유
    val byGpuClass: Map<String, Long>,    // gpu 특유 (선택 — null 가능)
    val buckets: List<DlqStatsBucket>,
)
```

운영 시나리오: H100 노드 spot 회수율이 갑자기 올라가면 `byGpuClass.H100` 의 dispatch
failure 가 다른 class 보다 5배 이상 튀는 패턴이 stats 콘솔에 보인다 → 해당 class 의
preemption 정책 / spot 풀 우선순위를 일시적으로 낮추는 운영 판단의 입력.

### 4) callback 멱등성 — Round 3 short-circuit 활용

CALLBACK source 의 replay 가 도메인의 멱등 처리와 어떻게 결합되는지가 중요하다.
worker 가 보내는 콜백은 *RUNNING → SUCCEEDED* 같은 lifecycle event 인데, 이미 종료된
잡에 같은 콜백이 두 번 도달하면 어떻게 되는가?

`JobLifecycleService.updateStatusFromCallback` 가 이미 다음 short-circuit 을 가지고
있다 (`already-terminal` 검사):

```kotlin
if (job.status.isTerminal()) {
    log.warn("ignored callback for terminal job id={} from={} to={}", ...)
    return job   // 멱등 — 두 번 호출해도 같은 결과
}
```

`PreemptionService.preempt` 의 *victim 종료 race 검사* 도 같은 패턴 (`victim already
terminal — skipping preempt`). 즉, callback replay 가 우연히 *지금은 이미 종료된*
잡으로 다시 들어와도 도메인이 no-op 으로 흡수한다. DLQ store 의 idempotencyKey
(`replayedKeys` set) 와 합쳐 *두 겹의 멱등 보호* — 키 충돌 시 store 단에서 IGNORED,
키가 새것이라도 도메인 단에서 no-op.

### 5) DAG eval failure replay 의 의미

DAG_EVAL source 는 `DependencyResolutionService.onParentTerminal` 가 child 의
WAITING_DEPS → QUEUED 전이를 트리거 못 한 경우의 dead-letter 다. replay 는 단순한
*같은 입력 재실행* 이 아니라, **parent 상태 재검증 → 현재 도메인 상태에 맞는 cascade
재실행** 으로 동작한다. 즉,

1. parent 의 현재 status 를 다시 읽는다.
2. parent 가 SUCCEEDED 면 → child 들을 QUEUED 로 promote.
3. parent 가 FAILED / CANCELLED 면 → child 들을 같이 CANCELLED.
4. parent 가 아직 종료 안 되었다면 → no-op (다음 콜백을 기다림).

DLQ 의 페이로드는 *그때 어떤 입력이 들어왔는지* 의 audit 만으로 쓰이고, 실제 동작은
지금의 도메인 상태로부터 결정된다. 운영자가 replay 누르는 순간 stale data 로 잘못된
전이가 발생하지 않게 하는 핵심 — 멱등성 그 자체.

### 6) obsolete job 의 bulk-discard 패턴

운영에서 가장 빈번한 시나리오 — `SUCCEEDED / FAILED / CANCELLED` 로 이미 종료된
잡에 대한 DLQ 메시지. 콜백이 늦게 도착하거나 outbox row 가 격리된 후 잡이 사용자
취소된 경우. replay 가 의미 없으므로 bulk-discard 가 일반적이다.

운영 흐름:

1. `POST /bulk-discard?confirm=false`  — dry-run, source 와 시간 구간으로 매칭.
2. 응답 의 `matched` 카운트 + 표본 페이로드 확인.
3. `POST /bulk-discard?confirm=true&reason=obsolete-jobs-2026-05-15` — 실제 실행.
4. `GET /bulk-jobs/{jobId}` 폴링으로 진행 상황.

`reason` 은 audit log 에 그대로 남아 사후 *왜 폐기했는지* 추적 가능. hard DELETE 는
하지 않고 `discarded` 플래그만 set — cost ledger / audit 무결성 유지 (소급해서
"실제로는 메시지가 있었다" 를 증명할 수 있어야 회계 분쟁 시 방어 가능).

### 7) 권한 / 안전

- `@PreAuthorize("hasRole('admin')")` — JWT 모드의 SecurityConfig 가 ROLE_admin 확인.
- Permissive 모드 (로컬 dev) 에서는 PreAuthorize 가 비활성이지만, 컨트롤러 본문이
  `Caller.from(jwt).isAdmin` 으로 한 번 더 검사 → 두 방어선.
- audit: `DLQ_REPLAY` / `DLQ_DISCARD` / `DLQ_BULK_REPLAY` / `DLQ_BULK_DISCARD` —
  actor (admin sub) / target (messageId 또는 filter) / reason / 결과 / jobId /
  ownerId 가 모두 남는다.
- rate limit: Redis Lua 의 token bucket, scope 별 (READ / WRITE / BULK) 분리. 읽기가
  폭발해도 write / bulk 가 살아남는다. dev 는 [NoopAdminRateLimiter] (항상 통과).
- bulk 의 `source` 필수 (market 패턴).
- bulk-discard 의 `reason` 은 confirm=true 시 필수.
- 페이로드는 list 응답에서 256자 preview 만, 전체 payload 는 단건 detail 에서만.

### 8) 구조 — 단일 모듈 100% Kotlin

market / billing / notification 은 모듈 분리 / sub-package 분리 방식이 다 다르다.
gpu 는 **단일 `orchestrator-api` 모듈** 이고 직전 라운드 (refactor: Java → Kotlin
마이그레이션 5건) 로 main source 가 100% Kotlin 으로 통일된 상태. 신규 코드는 같은
모듈의 `dlq/` sub-package 하나에 모두 넣고 (DTO / port / service / adapter), 신규
컨트롤러 `api/AdminDlqController.kt`, bean wiring `config/DlqAdminConfig.kt` 도
Kotlin 으로 유지.

```
orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/
├── api/
│   └── AdminDlqController.kt           (신규 — 8 endpoint)
├── config/
│   └── DlqAdminConfig.kt               (신규 — bean wiring + bulk executor)
└── dlq/                                (신규 sub-package)
    ├── DlqSource.kt                    (enum 5값)
    ├── DlqMessage.kt                   (DTO)
    ├── DlqEntryFilter.kt / DlqListPage.kt / DlqStats.kt
    ├── DlqBulkJob.kt / DlqBulkResult.kt
    ├── DlqAdminUseCase.kt / DlqBulkAdminUseCase.kt
    ├── DlqAdminService.kt / DlqBulkAdminService.kt
    ├── DlqMessageStore.kt / AdminRateLimiter.kt /
    │   DlqBulkJobRepository.kt / DlqAuditLog.kt (port out)
    ├── InMemoryDlqMessageStore.kt / KafkaDlqMessageStore.kt
    ├── InMemoryDlqBulkJobRepository.kt
    ├── RedisAdminRateLimiter.kt
    └── Slf4jDlqAuditLog.kt
```

신규 Java 코드는 0줄 — 단일 모듈 100% Kotlin 컨벤션 유지.

## 왜 over-spec 아닌가

- 모든 코드는 `dlq/` sub-package + 1 controller + 1 config 안에 격리. 도메인 코드,
  Job 라이프사이클, Outbox 메커니즘, DAG eval, cost ledger 변경 0줄. K8s manifest,
  Helm chart, DB schema, Kafka payload 변경 0줄.
- KafkaDlqMessageStore 는 현 단계에서 outbox dead-letter row 만 노출 (가장 빈번한
  DLQ 사용 사례) — 나머지 source 의 consumer 통합은 본 ADR '다시 검토할 시점' 참고.
  outbox 부분만으로도 운영에서 즉시 가치 (격리된 row 가 콘솔에 즉시 보이고 replay /
  discard 가능).
- 도메인 멱등 short-circuit (already-terminal callback, victim race 검사) 을 *재사용*
  — replay 의 안전성 보장에 추가 코드 필요 없음. DLQ store 의 idempotencyKey 와
  합산되어 두 겹 보호.
- audit / rate limit / dry-run 셋 다 운영 사고 (잘못된 bulk replay 로 broker 가 다시
  죽는 사고, 잘못된 discard 로 회계 분쟁) 의 방어 비용이 사고 한 번의 cleanup 비용
  보다 훨씬 작다.

## 트레이드오프

- **bulk executor 가 JVM-local fixed pool 4 워커**. pod 가 죽으면 진행 중 bulk job
  의 상태를 잃는다. `InMemoryDlqBulkJobRepository` 도 같은 한계 — Redis hash 로 교체
  하면 cross-pod 가시성이 살지만, 추가 외부 의존을 늦춰 첫 버전에서는 in-memory.
  (다시 검토할 시점 참고)
- **rate limit 의 fail-open**: Redis 가 죽으면 admin 콘솔까지 차단되면 운영 사고
  대응이 막힌다 — RedisAdminRateLimiter 가 예외 시 true 를 반환. 트레이드오프: SLO
  알림이 Redis 장애를 별 채널로 잡지 못하면 burst 가 가능. 운영에서는 Prometheus
  rule (redis 가용성 + admin 호출 burst) 으로 보강.
- **callback DLQ 가 워커 측 retry queue 와 분리**: 워커의 retry exhausted 시점에 이
  DLQ 로 흘리는 producer 가 별도 필요 — 본 ADR 범위 밖. 후속 단계에서 worker/main.go
  의 retry queue 가 exhausted 시 Kafka admin topic 으로 dead-letter 발행하도록 확장.

## 다시 검토할 시점

- **Kafka admin topic consumer** 추가 — `gwp.dlq.callback`, `gwp.dlq.preempt` 등 별
  topic 으로 worker / scheduler 가 발행하고 KafkaDlqMessageStore 가 consume. 현재는
  outbox row 만 노출. 우선순위 — worker DLQ producer 가 같이 들어와야 의미 있음.
- **Redis-backed `DlqBulkJobRepository`** — pod restart 후에도 진행 중 bulk job 상태
  유지. 같은 pattern 의 ShedLock (ADR-0017) 처럼 외부 storage 로 옮기는 게 표준.
- **Kafka audit topic** — 현재는 Slf4j logger 로만 audit. 별 topic 으로 발행해서 SIEM
  / compliance audit 으로 흘리도록 확장.
- **stats 의 시간 bucket 을 RRD / TS-DB 로 옮김** — 현재는 in-memory grouping. 데이터가
  크면 ClickHouse / Prometheus 로 query offloading. 단기 (한 달 retention) 에서는
  in-memory 로 충분.
- **콘솔 frontend** — 별 repo. 본 ADR 은 백엔드 API 만.

## 참고 자료

- notification ADR-0015 (DLQ admin v1)
- billing ADR-0033 (DlqSource enum + byCustomer)
- market ADR-0028 (DlqSource 6값 + bySku + source 필수)
- ADR-0004 (Outbox 패턴 — `dead_lettered_at` 컬럼 도입)
- ADR-0022 (Lifecycle state machine 사이드카 — terminal 상태 정의)
- ADR-0025 (Resilience4j retry + circuit chain — DLQ 격리 직전 단계)
