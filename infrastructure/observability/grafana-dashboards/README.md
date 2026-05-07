# Grafana 대시보드 (as code)

대시보드를 JSON 으로 commit 합니다. UI 에서 만든 대시보드는 변경 추적이 불가능하고,
PR 리뷰가 어려우며, 환경 간 동일성을 보장할 수 없습니다. JSON 으로 관리하면 Terraform
`grafana_dashboard` 리소스 또는 Grafana provisioning 으로 자동 배포할 수 있고, 변경이 PR
리뷰 대상이 됩니다.

| 파일 | 대상 | 핵심 패널 |
|---|---|---|
| [`orchestrator-overview.json`](orchestrator-overview.json) | API 자체 | RPS, p95 latency, 5xx by endpoint, Job 상태, Outbox lag, HikariCP, JVM heap |
| [`gpu-fleet.json`](gpu-fleet.json) | GPU 노드 / DCGM | 노드별 GPU utilization, 메모리, 온도, XID 에러, fleet capacity |

## 배포 방법

### 1. Terraform 사용 (권장)

```hcl
resource "grafana_dashboard" "orchestrator_overview" {
  config_json = file("${path.module}/grafana-dashboards/orchestrator-overview.json")
  folder      = grafana_folder.gwp.id
}
```

### 2. Grafana Provisioning 사용

```yaml
# /etc/grafana/provisioning/dashboards/gwp.yaml
apiVersion: 1
providers:
  - name: gwp
    folder: GWP
    type: file
    options:
      path: /var/lib/grafana/dashboards/gwp
```

JSON 파일을 위 경로에 mount 하면 자동으로 import 됩니다.

### 3. kube-prometheus-stack Sidecar 사용

ConfigMap 에 label 을 부여하면 sidecar 가 자동으로 import 합니다.

```bash
kubectl -n monitoring create configmap orchestrator-overview-dashboard \
  --from-file=orchestrator-overview.json=infrastructure/observability/grafana-dashboards/orchestrator-overview.json

kubectl -n monitoring label configmap orchestrator-overview-dashboard \
  grafana_dashboard=1
```

## 변경 흐름

1. Grafana UI 에서 대시보드 수정
2. JSON Model 메뉴에서 export (또는 API 사용)
3. 본 디렉토리의 JSON 갱신 후 PR 생성
4. main 머지 시 Terraform 또는 sidecar 가 자동으로 반영
