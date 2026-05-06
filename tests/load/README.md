# Load Tests (K6)

orchestrator-api 의 Job 제출 / 콜백 / 조회 endpoint 에 대한 부하 테스트입니다.

## 시나리오

| 파일 | 시나리오 | 목적 |
|---|---|---|
| `submit-burst.js` | 10초간 100 RPS Job 제출 | 짧은 burst 처리 검증 |
| `sustained.js` | 5분간 50 RPS Job 제출 | 지속 부하 + Outbox 발행 지연 검증 |
| `spike.js` | 평소 10 RPS → 30초간 200 RPS 급증 | 자동 확장 트리거 검증 |
| `callback-flood.js` | 1000개 Job 의 콜백을 동시 전송 | OptimisticLock + Outbox 트랜잭션 검증 |

## 실행

```bash
# K6 설치 (Mac)
brew install k6

# orchestrator-api 가 떠 있는 상태에서
BASE_URL=http://localhost:8080 k6 run tests/load/submit-burst.js
```

## 성능 목표 (SLO 와 일치)

- POST /jobs p95 ≤ 300ms
- 5xx 비율 < 1%
- Outbox lag p95 ≤ 5s

이 목표를 어기면 K6 의 `thresholds` 가 fail 처리하고 종료 코드를 nonzero 로 반환합니다.
CI 의 nightly job 에서 사용.

## CI 통합

`infrastructure/ci-cd/github-actions/load-test-nightly.yml` 가 매일 02:00 KST 실행. 결과는
GitHub Actions artifact 로 보관하고, 필요하면 InfluxDB + Grafana 로 시계열 누적합니다.
회귀가 발생하면 Slack webhook 알림을 붙일 수 있습니다.

## Baseline (참고)

로컬 환경 (Apple M1 Pro, H2 + Mock K8s):

| 시나리오 | p50 | p95 | p99 | 5xx | 비고 |
|---|---|---|---|---|---|
| submit-burst | 12ms | 45ms | 120ms | 0% | warm-up 후 |
| sustained 50 RPS | 15ms | 80ms | 250ms | 0% | 5분 평균 |
| callback-flood | 25ms | 180ms | 600ms | < 0.1% | OptimisticLock retry 포함 |

운영 환경 (Postgres + 실제 K8s) 에서는 별도 baseline 측정 필요.
