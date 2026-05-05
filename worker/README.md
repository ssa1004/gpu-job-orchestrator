# GPU Worker Simulator

orchestrator-api 가 생성한 Kubernetes Job 안에서 실행되는 GPU 워커 시뮬레이터입니다.

실 운영에서는 GPU 학습 / 추론 컨테이너가 들어갈 자리이며, 본 시뮬레이터는 다음과 같은
동작 흐름을 동일하게 구현합니다.

1. 시작 시 `RUNNING` 콜백 발송
2. 지정된 시간만큼 sleep + 가벼운 CPU 작업으로 GPU 연산 시뮬레이션
3. 완료 시 `SUCCEEDED` (또는 시뮬레이션 실패 시 `FAILED`) 콜백 발송

콜백 재시도, Prometheus 메트릭 노출, graceful shutdown 까지 운영 워커가 갖춰야 할 기본
기능을 모두 포함합니다.

## 빌드 및 실행

```bash
# 로컬 빌드
go build -o bin/worker ./cmd/worker

# 실행 (orchestrator-api 가 떠 있다고 가정)
./bin/worker \
  --job-id=$(uuidgen) \
  --orchestrator-url=http://localhost:8080 \
  --callback-secret=dev-secret-change-me \
  --duration=10s
```

## 컨테이너 빌드

```bash
docker build -t ghcr.io/ssa1004/gpu-worker:0.1.0 .
```

## 옵션

| 플래그 | 환경변수 | 기본값 | 설명 |
|---|---|---|---|
| `--job-id` | - | (필수) | 처리할 Job 의 UUID |
| `--orchestrator-url` | `ORCHESTRATOR_URL` | `http://orchestrator-api:8080` | orchestrator-api base URL |
| `--callback-secret` | `GWP_CALLBACK_SECRET` | - | 콜백 인증 시크릿 |
| `--duration` | - | `30s` | GPU 작업 시뮬레이션 시간 |
| `--fail-probability` | - | `0.0` | 0.0~1.0 작업 실패 확률 (chaos test 용) |
| `--output-uri` | - | (자동 생성) | 결과 파일 URI |
| `--metrics-port` | - | `9090` | Prometheus metrics 포트 |

## 메트릭

`:9090/metrics` 에서 노출되는 지표.

- `gwp_worker_jobs_started_total` — 처리 시작한 Job 수
- `gwp_worker_jobs_succeeded_total` — 정상 완료
- `gwp_worker_jobs_failed_total` — 실패 (fail_probability 또는 callback 실패)
- `gwp_worker_job_duration_seconds` — 처리 시간 histogram
- `gwp_worker_callback_retries_total` — 콜백 재시도 횟수

## 콜백 재시도 정책

지수 백오프 (500ms → 1s → 2s → 4s → 8s), 최대 5회. 5xx / 네트워크 오류만 재시도하고,
4xx 는 즉시 실패합니다 (재시도 무의미).

## 테스트

```bash
go test ./...
```
