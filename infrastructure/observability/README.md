# Observability (as code)

운영에서 사용하는 메트릭, 알림, 대시보드를 모두 PR 로 관리합니다.

```
observability/
├── prometheus-rules/    SLO / 운영 알림 (각 alert → docs/runbooks/)
└── grafana-dashboards/  Grafana 대시보드 JSON
```

## 설계 원칙

**알림은 SLO 기반.** 단순히 "이 메트릭이 임계치를 초과하면 알림" 이 아니라, "사용자가
경험하는 SLO 가 위협받을 때 알림" 을 원칙으로 합니다. 임계치는
[`docs/slo.md`](../../docs/slo.md) 의 SLO 정의에서 역산하여 결정합니다.

**알림은 곧 runbook 으로 연결.** 모든 alert 의 `runbook_url` annotation 이
[`docs/runbooks/`](../../docs/runbooks/) 의 .md 파일을 직접 가리킵니다. 새벽 시간대 호출
시에도 알림에서 즉시 대응 절차로 이동할 수 있도록 한 구성입니다.

**대시보드는 코드로 관리.** UI 에서 클릭으로 만든 대시보드는 변경 추적이 불가능하고 PR
리뷰가 어렵습니다. JSON 으로 commit 함으로써 환경 간 동일성을 보장하고 변경 이력을
관리합니다.

## 메트릭 출처

| 메트릭 | 출처 | 위치 |
|---|---|---|
| `gwp_orchestrator_jobs_*` | 도메인 서비스에서 직접 increment | [`JobMetrics.java`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/observability/JobMetrics.java) |
| `gwp_orchestrator_outbox_*` | OutboxRelay 가 polling 시 측정 | [`OutboxRelay.java`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/outbox/OutboxRelay.java) |
| `http_server_requests_*` | Spring Boot Actuator (자동) | (built-in) |
| `hikaricp_*` | HikariCP MeterBinder | (built-in) |
| `jvm_*`, `process_*` | Micrometer 기본 binder | (built-in) |
| `DCGM_FI_DEV_*` | NVIDIA DCGM exporter (별도 DaemonSet) | [Helm chart](../terraform/modules/monitoring/main.tf) |
| `kube_*` | kube-state-metrics | (kube-prometheus-stack 기본) |
