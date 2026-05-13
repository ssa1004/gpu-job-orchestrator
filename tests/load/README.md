# Load test (k6)

orchestrator-api 의 5 가지 부하 시나리오. 단순 submit RPS 만이 아니라 GPU 스케줄러 특유의
관심사 (callback throughput, DAG 처리 latency, BigDecimal cost 집계, queue depth 불변식)
를 함께 회귀 가드한다.

## 디렉토리

```
tests/load/
├── README.md
└── k6/
    ├── lib/
    │   ├── auth.js              # K6_TOKEN env + W3C Baggage 헤더 (cost-center / priority)
    │   └── config.js            # BASE URL + GPU image / owner / DAG depth 풀
    └── scenarios/
        ├── job-submit.js        # POST /api/v1/jobs constant 200 req/s (단일 잡 throughput)
        ├── job-callback.js      # POST /internal/jobs/{id}/status constant 500 req/s (callback throughput)
        ├── dag-submit.js        # POST /api/v1/jobs (parent 2 + child 1) ramping 0→50 VU (DAG resolve)
        ├── cost-query.js        # GET /api/v1/cost/owners/{owner} constant 100 req/s (cost ledger)
        └── queue-depth.js       # submit burst + RUNNING/SUCCEEDED callback (queue depth 불변식)
```

## 사전 준비

세 가지 방법 중 하나:

### A. brew 로 로컬 설치

```bash
brew install k6
k6 version
```

### B. docker 직접 실행

```bash
docker run --rm -i grafana/k6 run - < tests/load/k6/scenarios/job-submit.js
```

### C. `scripts/run-load.sh` 일괄 실행

```bash
./scripts/run-load.sh                # 모든 시나리오 순차 실행 + summary JSON
BASE_URL=http://localhost:8080 ./scripts/run-load.sh
```

## 환경 기동

본 시나리오는 orchestrator-api 가 떠 있는 상태를 가정한다.

### dev 단독 (H2 또는 Postgres)

```bash
cd orchestrator-api
./gradlew bootRun
# → http://localhost:8080
```

### 통합 환경 (Postgres + Kafka + KEDA — 부하 측정용)

```bash
cd infrastructure/docker
docker compose up -d postgres kafka kafka-ui
cd ../../orchestrator-api
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
BASE_URL=http://localhost:8080 k6 run tests/load/k6/scenarios/job-submit.js
```

### 보안 모드

- **Permissive** (기본 dev) — `gwp.security.jwt.enabled=false`. 모든 호출자가
  `caller.owner() = anonymousUser` 로 묶인다. cost-query 시나리오는 path variable 의
  owner 가 anonymousUser 와 다르면 403 — `K6_OWNERS=anonymousUser` 로 풀을 덮어쓰면 200
  비율이 올라간다.
- **JWT mode** — `gwp.security.jwt.enabled=true`. `/api/**` 는 Bearer 필수. `K6_TOKEN` env
  로 외부 IdP (Keycloak / auth-service) 발급 토큰을 주입.

콜백 endpoint (`/internal/jobs/{id}/status`) 는 SecurityConfig 의 두 모드에서 모두 JWT 검사
를 우회하고 `X-GWP-Callback-Secret` 헤더로 별도 검증. 본 시나리오는 `K6_CALLBACK_SECRET` env
로 운영 helm secret 값을 외부 주입 (`scripts/run-load.sh` 가 통과).

## 시나리오별 실행

### 1) job-submit — 단일 잡 제출 throughput

```bash
k6 run tests/load/k6/scenarios/job-submit.js
```

| metric | 기준 |
|---|---|
| `http_req_duration{name:job-submit}` p95 / p99 | < 100ms / < 250ms |
| `http_req_failed{name:job-submit}` | < 1% |
| `submit_accepted` | > 99% |

검증 + Job INSERT + Outbox INSERT 의 한 트랜잭션 path. JobMetrics 의 server side
`gwp_orchestrator_job_submit_seconds` 히스토그램과 client side 의 `http_req_duration`
을 비교하면 client-server gap (네트워크 / 직렬화) 분리 가능.

### 2) job-callback — 워커 콜백 throughput

```bash
k6 run tests/load/k6/scenarios/job-callback.js
```

| metric | 기준 |
|---|---|
| `http_req_duration{name:callback}` p95 / p99 | < 50ms / < 150ms |
| `http_req_failed{name:callback}` | < 2% |
| `callback_lifecycle_ok` | > 95% (200 + 409 합) |
| `callback_unexpected_5xx` | < 10 |

setup() 단계에서 K6_CALLBACK_JOB_POOL (기본 300) 개의 잡을 미리 제출 → 본 부하는 그 풀의
잡들에 RUNNING / SUCCEEDED 콜백을 흘린다. 상태 머신 전이 (@Version optimistic lock) 와
종료 시 Outbox INSERT 의 빠른 path 가 부하 상황에서도 50ms 안에 들어와야 한다.

### 3) dag-submit — 부모 2 + 자식 1 DAG ramping 부하

```bash
k6 run tests/load/k6/scenarios/dag-submit.js
```

| metric | 기준 |
|---|---|
| `http_req_duration{name:dag-parent}` p95 | < 200ms |
| `http_req_duration{name:dag-child}` p95 / p99 | < 500ms / < 1000ms |
| `http_req_failed` (parent + child) | < 2% |
| `dag_complete_ratio` | > 95% |

child 제출은 부모 ownership 검증 + cycle detection (그래프 traversal) + JobDependency
INSERT 가 추가되어 단일 path 보다 비용이 커진다. ramping-vus (0 → 50) 부하 모델로 lock
경합 / Hikari 풀 고갈이 잘 드러나도록.

### 4) cost-query — 시간 구간 cost 집계

```bash
k6 run tests/load/k6/scenarios/cost-query.js
```

| metric | 기준 |
|---|---|
| `http_req_duration{name:cost-query}` p95 / p99 | < 200ms / < 500ms |
| `http_req_failed{name:cost-query}` | < 10% (403 은 환경 / 권한 의존이라 관대) |
| `cost_query_ok` | > 90% |

owner 풀 × 24h / 7d / 30d 윈도우 round-robin. cost_record 테이블의 BigDecimal SUM 이
인덱스 (owner_id, created_at) range scan 안에서 닫히는지가 p99 의 결정 요인.

### 5) queue-depth — submit + callback 불변식 검증

```bash
k6 run tests/load/k6/scenarios/queue-depth.js
```

| metric | 기준 |
|---|---|
| `http_req_failed` | < 5% (부하 spike) |
| `queue_depth_invariant` | > 99% |
| `submitted_total_seen` | > 95% (actuator/prometheus 노출 환경에서) |

submit 부하 (burst N 개) + 콜백 부하 (RUNNING + 70% SUCCEEDED) 를 같이 흘려, 클라이언트
측 카운터와 서버 측 Prometheus counter (`gwp_orchestrator_jobs_submitted_total`) 의
trend 가 일치하는지를 teardown 에서 확인.

## GPU 스케줄러 특유 측정 항목

본 orchestrator-api 가 단순 REST 백엔드와 다른 측정 항목:

| metric | 의미 |
|---|---|
| `submit_latency_ms` | 단일 잡 제출의 client side 응답 시간 — 검증 + DB INSERT + Outbox INSERT 합. 운영 환경의 `gwp_orchestrator_job_submit_seconds` (server side) 와 비교하면 client-server gap 분리. |
| `callback_lifecycle_ok` | 콜백의 200 + 409 합 비율 — 정상 전이와 OptimisticLock 충돌 후 재시도까지 포함. |
| `callback_unexpected_5xx` | 콜백 path 에서 5xx 가 나면 즉시 신호 — 한 풀의 잡들에 빠르게 두 콜백 (RUNNING / SUCCEEDED) 흘리는 부하 모델에서 5xx 가 곧 상태 머신 / lock 버그. |
| `dag_resolve_time_ms` | parent 2 + child 1 한 묶음 iteration 의 client-side wall-clock. child 제출 path 의 cycle detection / JobDependency INSERT 비용이 어디서 정점인지의 시각. |
| `dag_cycle_rejected` | 본 시나리오는 cycle 을 만들지 않으므로 0 이 정상. 0 이 아니면 정규 부하 안에서 cycle detector 가 false positive. |
| `dag_complete_ratio` | parent + child 한 묶음이 모두 2xx 인 iteration 비율 — DAG submit 의 end-to-end 신호. |
| `cost_aggregate_ms` | `/api/v1/cost/owners/{owner}` 의 client side latency. owner × window round-robin 평균. p99 가 잘 안 떨어지면 캐시 미적중 / 인덱스 미스 신호. |
| `cost_query_forbidden` | 403 count — 권한 / Permissive 모드 owner 불일치. 환경 정합성 신호. |
| `queue_submitted_client` | k6 가 직접 추적한 제출 성공 누적. 서버측 `gwp_orchestrator_jobs_submitted_total` 의 증가분과 대조. |
| `queue_completed_client` | k6 가 직접 추적한 SUCCEEDED 콜백 누적. 서버측 `gwp_orchestrator_jobs_completed_total{status=succeeded}` 와 대조. |
| `queue_depth_invariant` | 클라이언트 측 [submitted - completed] 가 음수가 아닌가의 시각. 콜백이 제출보다 많이 성공하면 중복 콜백 / 상태 머신 누수. |
| `submitted_total_seen` | teardown 의 Prometheus scrape 가 0 이 아닌 값을 봤는가 — env 가 `/actuator/prometheus` 를 노출하지 않으면 false (skip 신호). |
| `prometheus_scrape_ms` | scrape RTT — observability stack 자체의 부하 환경 성능 신호. |
| `preemption_count` (예정) | preemption controller 의 동작 카운트 — KEDA / Cluster Autoscaler 통합 단계에 추가 예정. 현재 시나리오 5종은 기본 path 만 다룬다. |

## 환경변수

| key | 기본 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | orchestrator-api 의 HTTP base. |
| `K6_TOKEN` | (빈 값) | JWT mode 에서 Bearer 로 부착할 토큰. Permissive 모드면 비워둠. |
| `K6_CALLBACK_SECRET` / `GWP_CALLBACK_SECRET` | `dev-secret-change-me` | `/internal/jobs/{id}/status` 의 `X-GWP-Callback-Secret`. 운영 helm secret 과 같게. |
| `K6_GPU_IMAGES` | `gpu-worker:1.0,...` (6 개) | image 풀 — CSV. JobSubmissionRequest 의 image 정규식 통과 형태만. |
| `K6_OWNERS` | `team-vision,team-llm,...` (8 개) | cost-query 의 owner path variable 풀. JWT mode 면 sub 클레임과 같게 / Permissive 면 `anonymousUser` 로 덮어쓰기. |
| `K6_COST_CENTER` | `research-vision` | W3C Baggage 의 `cost-center` 값. trace + log + metric 라벨로 전파. |
| `K6_PRIORITY_LABEL` | `NORMAL` | W3C Baggage 의 `priority` 값. |
| `K6_CALLBACK_JOB_POOL` | `300` | job-callback 시나리오의 setup() 단계 잡 풀 크기. |
| `K6_QUEUE_BURST` | `500` | queue-depth 시나리오의 submit / callback burst 크기. |

## 부하 모델 요약

| 시나리오 | executor | rate / VU |
|---|---|---|
| job-submit | constant-arrival-rate | 200 req/s, 60s, preAllocated 50 |
| job-callback | constant-arrival-rate | 500 req/s, 30s, preAllocated 80 |
| dag-submit | ramping-vus | 0 → 50 VU stages, 총 70s |
| cost-query | constant-arrival-rate | 100 req/s, 60s, preAllocated 30 |
| queue-depth | shared-iterations × 2 | 500 iter × 2 stage, 최대 60s |

submit / callback / cost-query 는 throughput / latency 측정이 목적이라
`constant-arrival-rate` 로 connection-bound 변동을 줄였고, dag-submit 은 ramping 부하에서
lock 경합 / 풀 고갈이 잘 드러나도록 `ramping-vus`, queue-depth 는 두 단계 (submit burst →
callback burst) 가 순차적으로 흘러야 해서 `shared-iterations` 두 개를 시간차로 묶었다.

## CI 통합

`infrastructure/ci-cd/github-actions/load-test-nightly.yml` 가 매일 02:00 KST 실행. 결과는
GitHub Actions artifact (`summary.json`) 로 보관. SLO regression 발생 시 Slack webhook 알림
훅 자리 잡혀 있음.

## 결과 예시 (참고 — 환경마다 다름)

m1 max + 단독 bootRun (Postgres 16, HikariCP 20, 2G heap) 기준 대략적인 예시:

```
job-submit (200 req/s, 60s)
  http_req_duration{name:job-submit}
    avg=18ms  p(95)=62ms   p(99)=180ms
  submit_accepted............. 99.9%

job-callback (500 req/s, 30s)
  http_req_duration{name:callback}
    avg=6ms   p(95)=22ms   p(99)=88ms
  callback_lifecycle_ok....... 98.5%
  callback_unexpected_5xx..... 0

dag-submit (ramping 0→50 VU, 70s)
  http_req_duration{name:dag-parent}
    avg=22ms  p(95)=76ms
  http_req_duration{name:dag-child}
    avg=58ms  p(95)=260ms  p(99)=620ms
  dag_complete_ratio.......... 99.1%

cost-query (100 req/s, 60s)
  http_req_duration{name:cost-query}
    avg=42ms  p(95)=130ms  p(99)=320ms
  cost_query_ok............... 99.7%   (anonymousUser 풀 기준)

queue-depth (burst 500 × 2)
  http_req_failed............. 1.2%
  queue_depth_invariant....... 100%
  submitted_total_seen........ 100%
```

운영 환경 (실제 GPU 노드 + KEDA / Cluster Autoscaler) 에서는 별도 baseline 측정 필요.

## 더 나아가려면

- 5 시나리오 결과를 `build/k6-reports/*.json` 으로 떨군 뒤 Grafana k6 dashboard 에 plot.
- `--out experimental-prometheus-rw=http://prom:9090/api/v1/write` 로 Prometheus
  remote-write 하면 k6 client metric + orchestrator-api 의 `gwp_orchestrator_*` server
  metric 을 같은 시간축에 올릴 수 있다. submit_latency_ms (client) vs
  `gwp_orchestrator_job_submit_seconds` (server) 의 gap 이 한 화면에 보인다.
- preemption 시나리오 — HIGH priority 잡을 H100 풀이 꽉 찼을 때 제출해
  `infrastructure/keda/` 의 priority class + Kueue 의 preemption 가 동작하는지 측정.
  현재 5 시나리오는 그 분기를 다루지 않는다 (별도 라운드).
