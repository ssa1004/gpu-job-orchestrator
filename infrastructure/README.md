# Infrastructure

[`orchestrator-api`](../orchestrator-api/) 가 운영되는 환경을 구성하는 코드입니다. 백엔드
구현 검토 후에 살펴보시면 됩니다.

```
infrastructure/
├── terraform/        AWS 환경 (cloud / hybrid / onprem) 모듈
├── ansible/          온프레미스 노드 부트스트랩
├── ci-cd/
│   ├── github-actions/   PR 테스트 → 이미지 빌드 → Trivy / Cosign → GitOps
│   └── argocd/           kustomize base + overlay, Argo Rollouts
└── observability/
    ├── prometheus-rules/    SLO 알림 (각 알림 → docs/runbooks/)
    └── grafana-dashboards/  대시보드 JSON
```

## terraform/

AWS 환경 (`environments/cloud`) 을 기준으로 구성되어 있으며, `hybrid` / `onprem` 환경에서는
필요한 모듈만 선택적으로 사용합니다.

`monitoring/` 모듈 하나로 kube-prometheus-stack, Loki, Tempo, Mimir, DCGM exporter 를 Helm
으로 일괄 배포합니다. 환경별 활성화 여부는 변수 (`enable_loki`, `enable_tempo`,
`enable_mimir`, `enable_dcgm_exporter`) 로 제어합니다. 메트릭 / 로그 / 트레이스 stack 을
단일 모듈로 묶어 운영 편의성을 확보한 점이 핵심입니다.

```bash
cd terraform/environments/cloud
terraform init
terraform plan -var="grafana_admin_password=..."
```

GPU 노드는 `gpu-nodes/` 모듈로 분리되어 있습니다. taint 설정, NVIDIA driver 호환 AMI, spot
옵션까지 모두 변수화되어 있습니다.

## ansible/

EKS 와 같은 매니지드 Kubernetes 가 적합하지 않은 환경 (자체 데이터센터의 GPU 노드 등) 에서
노드를 부트스트랩하기 위한 코드입니다.

| Role | 설치 항목 |
|---|---|
| `common` | 시스템 패키지, NTP, 사용자 |
| `docker` | 컨테이너 런타임 |
| `gpu-driver` | NVIDIA 드라이버 + nvidia-container-toolkit |
| `kubernetes` | k3s / kubeadm |
| `registry` | private container registry (Harbor) |
| `monitoring` | node_exporter, DCGM exporter |

```bash
ansible-playbook -i inventories/onprem/hosts.yml playbooks/site.yml
```

## ci-cd/

### github-actions/

`orchestrator-api-release.yml` 에 PR 부터 운영 배포까지의 전 과정이 정의되어 있습니다.

```text
PR
  test (단위 / 슬라이스)
  integration-test (Postgres Testcontainers)
  static-analysis (OWASP Dependency-Check → SARIF → GitHub Security)

main 머지
  build-image (Buildx, ECR push)
  Trivy scan (HIGH / CRITICAL → fail)
  Cosign keyless sign (OIDC)

tag (orchestrator-v*)
  update-gitops: 별도 GitOps 저장소의 kustomize image tag 갱신 → ArgoCD sync
```

> 본 파일은 `infrastructure/ci-cd/github-actions/` 에 예시로 보관되어 있습니다. 실제로
> GitHub Actions 가 동작하려면 `.github/workflows/` 로 이동시켜야 합니다.

### argocd/

`base/` + `overlays/{staging,production}/` 의 kustomize 구조입니다. `ApplicationSet` 으로
환경별 Application 을 자동 등록하고, `Rollout` 으로 canary 배포를 진행합니다.

| 파일 | 역할 |
|---|---|
| `applicationset.yml` | 환경별 Application 자동 등록 |
| `rollout.yml` | Argo Rollouts canary 정의 (10% → 30% → 60% → 100% + 분석 게이트) |
| `base/prometheus-rules.yml` | GPU / 워커 알림 (orchestrator 알림은 `observability/` 측) |
| `base/otel-collector.yml` | OTel Collector → Tempo |

## observability/

알림과 대시보드를 모두 코드로 관리합니다. Grafana UI 에서 클릭으로 만든 대시보드는 변경
추적과 PR 리뷰가 불가능하기 때문입니다.

`prometheus-rules/orchestrator-slo.yaml` 에는 API 가용성, latency, Outbox lag, K8s API,
Job 실패율, quota 거절 등의 알림이 포함되어 있습니다. 각 alert 의 `runbook_url` annotation
이 [`docs/runbooks/`](../docs/runbooks/) 의 파일과 1:1 로 대응됩니다.

`grafana-dashboards/` 에는 두 개의 대시보드가 있습니다. `orchestrator-overview.json` 은
API 자체 지표 (RPS, p95, 5xx, Job 상태, Outbox lag, HikariCP, JVM heap) 를, `gpu-fleet.json`
은 DCGM 기반의 노드별 utilization, 메모리, 온도, XID 에러를 보여줍니다.

대시보드 배포는 Terraform `grafana_dashboard` 리소스, Grafana provisioning 디렉토리 mount,
또는 kube-prometheus-stack sidecar 방식 중 선택할 수 있습니다 ([dashboards README](observability/grafana-dashboards/README.md) 참고).

## 백엔드와의 연결

`KubernetesJobDispatcher` 가 위 Kubernetes 클러스터에 Job 을 생성합니다. PostgreSQL 에는
Job, 쿼터, Outbox 가 저장되며, Redis 는 Job 단건 조회 캐시 (hit ratio 가 대시보드 패널에
노출됨) 로 사용됩니다. Kafka 는 OutboxRelay 가 publish 하는 대상이며, publish lag 자체가
SLO 지표입니다. 결과 파일은 Object Storage 에 저장되고, API 는 직접 다운로드하지 않고
presigned URL 만 발급합니다.

관측 측면에서는 `actuator/prometheus` 가 스크레이프 대상이며, Prometheus 가 Mimir 로
remote write 해서 장기 저장됩니다. requestId / traceId / jobId 가 로그에 함께 기록되어
Loki 검색이 가능하고, OpenTelemetry 트레이스는 Tempo 로 수집됩니다. Loki 에서 traceId 를
클릭하면 Tempo 트레이스로 바로 이동합니다.
