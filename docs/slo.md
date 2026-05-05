# SLO

본 문서는 `orchestrator-api` 의 SLI / SLO 와 error budget 정책을 정리한 것입니다.
Prometheus 알림 정의 ([`infrastructure/observability/prometheus-rules/`](../infrastructure/observability/prometheus-rules/))
와 1:1 로 대응됩니다.

## SLI / SLO 정의

다섯 가지 지표를 30일 rolling 으로 측정합니다.

| 지표 | 정의 | 목표 |
|---|---|---|
| API 가용성 | 5xx 를 제외한 정상 응답 비율 | 99.9% (월 다운타임 43.2분 이하) |
| API 지연 | `POST /api/v1/jobs` 의 p95 응답시간 | 300ms 이하 (5% 시간 초과 허용) |
| Job 성공률 | `SUCCEEDED / (SUCCEEDED + FAILED)`. 사용자 취소 제외 | 99% |
| Outbox 발행 지연 | `now() - outbox.created_at WHERE published_at IS NULL` 의 p95 | 5초 이하 |
| 콜백 처리 정확도 | 워커 콜백 수신 → DB 반영 성공률 | 99.95% |

API 가용성 99.9% 는 GPU Job 자체의 가용성과는 별개입니다. "API 가 사용자 요청을 받아
등록할 수 있는가" 에 대한 지표입니다. Job 실행 자체의 안정성은 `Job 성공률` 로 별도
측정합니다.

## 측정 PromQL

API 가용성:

```promql
1 - (
  sum(rate(http_server_requests_seconds_count{status=~"5..", uri!~"/actuator.*"}[5m]))
  /
  sum(rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[5m]))
)
```

`/actuator/*` 는 K8s probe 호출이므로 SLI 에서 제외합니다.

API 지연:

```promql
histogram_quantile(0.95,
  sum(rate(http_server_requests_seconds_bucket{uri="/api/v1/jobs", method="POST"}[5m]))
  by (le)
)
```

Job 성공률:

```promql
sum(rate(gwp_orchestrator_jobs_completed_total{status="succeeded"}[30m]))
/
sum(rate(gwp_orchestrator_jobs_completed_total{status=~"succeeded|failed"}[30m]))
```

Outbox 발행 지연 (OutboxRelay 가 매 polling 마다 gauge 로 노출):

```promql
gwp_orchestrator_outbox_publish_lag_seconds{quantile="0.95"}
```

콜백 처리 정확도:

```promql
1 - (
  sum(rate(gwp_orchestrator_callback_total{result="error"}[5m]))
  /
  sum(rate(gwp_orchestrator_callback_total[5m]))
)
```

## Error Budget 운영 정책

90% 소진 시 Slack 채널로 자동 알림이 전송됩니다. 이 시점부터 다음 PR 의 위험한 변경
(스키마 마이그레이션, 외부 연동 변경 등) 은 보류하고 원인 분석 티켓을 생성합니다. 아직
페이저 호출 단계는 아닙니다.

100% 소진 시 on-call 페이저 호출이 발생합니다. 새 feature 배포는 정지하고 fix 와 rollback
만 허용되며, 회복 후 회고 작성이 필수입니다.

회복은 자동입니다. rolling 30일 기준으로 budget 이 재충전되면 알람이 해제됩니다. 다만 같은
원인이 budget 을 두 번 연속 소진시키는 경우 architectural review 를 진행합니다. 단발성
incident 가 아닌 구조적 문제일 가능성이 높기 때문입니다.

## 사용 도구

- **메트릭 수집**: Prometheus (kube-prometheus-stack)
- **로그**: Loki + Promtail
- **트레이스**: OpenTelemetry → Tempo
- **시각화**: Grafana ([대시보드 JSON](../infrastructure/observability/grafana-dashboards/))
- **알림**: AlertManager → PagerDuty / Slack
- **메트릭 장기 저장**: Mimir (Prometheus remote write)

알림 규칙은
[`orchestrator-slo.yaml`](../infrastructure/observability/prometheus-rules/orchestrator-slo.yaml)
한 파일에 정의되어 있으며, 각 alert 의 `runbook_url` annotation 이
[`docs/runbooks/`](runbooks/) 의 개별 .md 파일을 직접 link 합니다.
