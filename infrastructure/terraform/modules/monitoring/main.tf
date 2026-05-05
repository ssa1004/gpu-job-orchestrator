# 모니터링 모듈 - Prometheus + Grafana + Loki + Tempo + Mimir + DCGM Exporter
# 모든 컴포넌트는 enable_* 변수로 개별 활성화/비활성화 가능

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    helm = {
      source  = "hashicorp/helm"
      version = ">= 2.11"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = ">= 2.23"
    }
  }
}

# 로컬 변수
locals {
  name_prefix   = "${var.environment}-gwp"
  monitoring_ns = var.monitoring_namespace
  loki_ns       = var.loki_namespace

  common_tags = merge(var.tags, {
    Environment = var.environment
    Project     = "gwp"
    ManagedBy   = "terraform"
    Module      = "monitoring"
  })

  # Helm 차트 공통 레이블
  common_labels = {
    "app.kubernetes.io/managed-by" = "terraform"
    "app.kubernetes.io/part-of"    = "gwp-monitoring"
    "environment"                  = var.environment
  }
}

# 모니터링 네임스페이스
resource "kubernetes_namespace" "monitoring" {
  metadata {
    name   = local.monitoring_ns
    labels = merge(local.common_labels, {
      name = local.monitoring_ns
    })
  }
}

resource "kubernetes_namespace" "loki" {
  count = var.enable_loki ? 1 : 0

  metadata {
    name   = local.loki_ns
    labels = merge(local.common_labels, {
      name = local.loki_ns
    })
  }
}

# Prometheus + Grafana (kube-prometheus-stack)
resource "helm_release" "kube_prometheus_stack" {
  name             = "kube-prometheus-stack"
  repository       = var.helm_repository_prometheus
  chart            = "kube-prometheus-stack"
  version          = var.prometheus_stack_version
  namespace        = local.monitoring_ns
  create_namespace = false
  # 900초(15분): 대규모 모니터링 스택 설치에 충분한 타임아웃
  timeout          = 900

  # Prometheus 서버 설정
  set {
    name  = "prometheus.prometheusSpec.retention"
    value = var.prometheus_retention
  }

  set {
    name  = "prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.resources.requests.storage"
    value = var.prometheus_storage_size
  }

  set {
    name  = "prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.storageClassName"
    value = var.storage_class_name
  }

  # Prometheus → Mimir 장기 저장 (Remote Write)
  set {
    name  = "prometheus.prometheusSpec.remoteWrite[0].url"
    value = "http://mimir-gateway.${local.monitoring_ns}.svc.cluster.local/api/v1/push"
  }

  # GPU Workload Platform 컴포넌트 서비스 디스커버리 활성화
  set {
    name  = "prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues"
    value = "false"
  }

  set {
    name  = "prometheus.prometheusSpec.podMonitorSelectorNilUsesHelmValues"
    value = "false"
  }

  # Grafana 설정
  set {
    name  = "grafana.enabled"
    value = var.enable_grafana
  }

  set {
    name  = "grafana.adminPassword"
    value = var.grafana_admin_password
  }

  set {
    name  = "grafana.persistence.enabled"
    value = "true"
  }

  set {
    name  = "grafana.persistence.size"
    value = var.grafana_storage_size
  }

  set {
    name  = "grafana.persistence.storageClassName"
    value = var.storage_class_name
  }

  # Grafana 서비스 유형
  set {
    name  = "grafana.service.type"
    value = var.grafana_service_type
  }

  # Grafana Ingress (선택사항)
  dynamic "set" {
    for_each = var.grafana_ingress_host != "" ? [1] : []
    content {
      name  = "grafana.ingress.enabled"
      value = "true"
    }
  }

  dynamic "set" {
    for_each = var.grafana_ingress_host != "" ? [1] : []
    content {
      name  = "grafana.ingress.hosts[0]"
      value = var.grafana_ingress_host
    }
  }

  # Loki 활성화 시 Grafana 데이터 소스로 추가
  dynamic "set" {
    for_each = var.enable_loki ? [1] : []
    content {
      name  = "grafana.additionalDataSources[0].name"
      value = "Loki"
    }
  }

  dynamic "set" {
    for_each = var.enable_loki ? [1] : []
    content {
      name  = "grafana.additionalDataSources[0].type"
      value = "loki"
    }
  }

  dynamic "set" {
    for_each = var.enable_loki ? [1] : []
    content {
      name  = "grafana.additionalDataSources[0].url"
      value = "http://loki-gateway.${local.loki_ns}.svc.cluster.local"
    }
  }

  dynamic "set" {
    for_each = var.enable_loki ? [1] : []
    content {
      name  = "grafana.additionalDataSources[0].access"
      value = "proxy"
    }
  }

  # AlertManager 설정
  set {
    name  = "alertmanager.enabled"
    value = var.enable_alertmanager
  }

  depends_on = [kubernetes_namespace.monitoring]
}

# NVIDIA DCGM Exporter - GPU 사용률, 메모리, 온도, 전력 메트릭 수집
resource "helm_release" "dcgm_exporter" {
  count = var.enable_dcgm_exporter ? 1 : 0

  name             = "dcgm-exporter"
  repository       = var.helm_repository_nvidia
  chart            = "dcgm-exporter"
  version          = var.dcgm_exporter_version
  namespace        = local.monitoring_ns
  create_namespace = false

  # GPU 노드에서만 실행 (GPU taint toleration 필수)
  set {
    name  = "tolerations[0].key"
    value = "nvidia.com/gpu"
  }
  set {
    name  = "tolerations[0].operator"
    value = "Exists"
  }
  set {
    name  = "tolerations[0].effect"
    value = "NoSchedule"
  }

  set {
    name  = "nodeSelector.nvidia\\.com/gpu\\.present"
    value = "true"
  }

  # Prometheus 자동 디스커버리용 ServiceMonitor 활성화
  set {
    name  = "serviceMonitor.enabled"
    value = "true"
  }

  # 15초: GPU 메트릭의 세밀한 모니터링을 위한 수집 간격
  set {
    name  = "serviceMonitor.interval"
    value = "15s"
  }

  depends_on = [
    helm_release.kube_prometheus_stack,
    kubernetes_namespace.monitoring,
  ]
}

# Loki - 로그 수집 (SimpleScalable 모드)
resource "helm_release" "loki" {
  count = var.enable_loki ? 1 : 0

  name             = "loki"
  repository       = var.helm_repository_grafana
  chart            = "loki"
  version          = var.loki_version
  namespace        = local.loki_ns
  create_namespace = false
  # 600초(10분): Loki 설치에 충분한 타임아웃
  timeout          = 600

  # SimpleScalable 모드로 배포
  set {
    name  = "deploymentMode"
    value = "SimpleScalable"
  }

  # 스토리지 백엔드 설정
  set {
    name  = "loki.storage.type"
    value = var.loki_storage_type
  }

  # 클라우드 배포용 S3 스토리지
  dynamic "set" {
    for_each = var.loki_storage_type == "s3" ? [1] : []
    content {
      name  = "loki.storage.s3.region"
      value = var.aws_region
    }
  }

  dynamic "set" {
    for_each = var.loki_storage_type == "s3" && var.loki_s3_bucket != "" ? [1] : []
    content {
      name  = "loki.storage.s3.bucketNames.chunks"
      value = var.loki_s3_bucket
    }
  }

  dynamic "set" {
    for_each = var.loki_storage_type == "s3" && var.loki_s3_bucket != "" ? [1] : []
    content {
      name  = "loki.storage.s3.bucketNames.ruler"
      value = var.loki_s3_bucket
    }
  }

  # 보관 설정
  set {
    name  = "loki.limits_config.retention_period"
    value = var.loki_retention_period
  }

  set {
    name  = "loki.compactor.retention_enabled"
    value = "true"
  }

  depends_on = [kubernetes_namespace.loki]
}

# Promtail - 로그 전송 에이전트
# GPU 노드 포함 모든 노드의 컨테이너 로그를 Loki로 전송
resource "helm_release" "promtail" {
  count = var.enable_loki ? 1 : 0

  name             = "promtail"
  repository       = var.helm_repository_grafana
  chart            = "promtail"
  version          = var.promtail_version
  namespace        = local.loki_ns
  create_namespace = false

  set {
    name  = "config.clients[0].url"
    value = "http://loki-gateway.${local.loki_ns}.svc.cluster.local/loki/api/v1/push"
  }

  # GPU 노드 taint tolerate하여 GPU Pod 로그도 수집
  set {
    name  = "tolerations[0].key"
    value = "nvidia.com/gpu"
  }
  set {
    name  = "tolerations[0].operator"
    value = "Exists"
  }
  set {
    name  = "tolerations[0].effect"
    value = "NoSchedule"
  }

  # Spot 인스턴스 taint toleration
  set {
    name  = "tolerations[1].key"
    value = "example.com/spot"
  }
  set {
    name  = "tolerations[1].operator"
    value = "Exists"
  }
  set {
    name  = "tolerations[1].effect"
    value = "PreferNoSchedule"
  }

  depends_on = [
    helm_release.loki,
    kubernetes_namespace.loki,
  ]
}

# Tempo - 분산 트레이싱 백엔드
# OTel Collector에서 OTLP로 수신, S3/MinIO에 트레이스 저장
resource "helm_release" "tempo" {
  count = var.enable_tempo ? 1 : 0

  name             = "tempo"
  repository       = var.helm_repository_grafana
  chart            = "tempo"
  version          = var.tempo_version
  namespace        = local.monitoring_ns
  create_namespace = false

  # 스토리지 백엔드 (S3/MinIO)
  set {
    name  = "tempo.storage.trace.backend"
    value = var.loki_storage_type  # "s3" 또는 "filesystem"
  }

  set {
    name  = "tempo.storage.trace.s3.bucket"
    value = var.tempo_s3_bucket
  }

  # Grafana 연동을 위한 서비스 포트
  set {
    name  = "tempo.server.http_listen_port"
    value = "3200"
  }

  depends_on = [kubernetes_namespace.monitoring]
}

# Mimir - Prometheus 장기 메트릭 저장소
# Prometheus Remote Write로 수신, S3/MinIO에 장기 저장
resource "helm_release" "mimir" {
  count = var.enable_mimir ? 1 : 0

  name             = "mimir"
  repository       = var.helm_repository_grafana
  chart            = "mimir-distributed"
  version          = var.mimir_version
  namespace        = local.monitoring_ns
  create_namespace = false

  # 스토리지 백엔드
  set {
    name  = "mimir.structuredConfig.common.storage.backend"
    value = var.loki_storage_type
  }

  set {
    name  = "mimir.structuredConfig.common.storage.s3.bucket_name"
    value = var.mimir_s3_bucket
  }

  depends_on = [kubernetes_namespace.monitoring]
}

# GPU Workload Platform 커스텀 Prometheus 알림 규칙
# GPU 워크로드 플랫폼용 GPU 전용 알림 규칙
resource "kubernetes_manifest" "gpu_alert_rules" {
  count = var.enable_gpu_alerts ? 1 : 0

  manifest = {
    apiVersion = "monitoring.coreos.com/v1"
    kind       = "PrometheusRule"
    metadata   = {
      name      = "${local.name_prefix}-gpu-alerts"
      namespace = local.monitoring_ns
      labels    = merge(local.common_labels, {
        "prometheus" = "kube-prometheus-stack-prometheus"
        "role"       = "alert-rules"
      })
    }
    spec = {
      groups = [
        {
          name  = "gwp-gpu-alerts"
          rules = [
            {
              alert  = "GPUMemoryExhaustion"
              # 90%: 새 모델 로딩 공간 부족으로 OOM 위험
              expr   = "DCGM_FI_DEV_FB_USED / (DCGM_FI_DEV_FB_USED + DCGM_FI_DEV_FB_FREE) > 0.9"
              # 5분: 일시적인 메모리 급증(예: 모델 로딩 중)은 무시하고 지속적인 고사용량만 알림
              for    = "5m"
              labels = {
                severity = "critical"
                team     = "gwp-platform"
              }
              annotations = {
                summary     = "GPU 메모리 사용량 90% 초과: {{ $labels.gpu }}"
                description = "{{ $labels.instance }} 노드의 GPU {{ $labels.gpu }}가 5분간 90% 이상의 메모리를 사용 중입니다. GPU Job에서 OOM이 발생할 수 있습니다."
              }
            },
            {
              alert  = "GPUThermalThrottling"
              # 85도: NVIDIA GPU의 열 스로틀링 시작 온도. 이 이상에서는 클록 속도가 자동으로 감소
              expr   = "DCGM_FI_DEV_GPU_TEMP > 85"
              # 10분: 짧은 순간의 온도 급등은 무시하고, 지속적인 과열만 경고
              for    = "10m"
              labels = {
                severity = "warning"
                team     = "gwp-platform"
              }
              annotations = {
                summary     = "GPU 온도 85도 초과: {{ $labels.gpu }}"
                description = "{{ $labels.instance }}의 GPU {{ $labels.gpu }}가 과열 상태입니다. 성능이 스로틀링되어 GPU Job 속도가 저하될 수 있습니다."
              }
            },
            {
              alert  = "GPULowUtilization"
              # 10%: GPU 사용률이 10% 미만이면 리소스가 낭비되고 있으므로 비용 최적화 필요
              expr   = "DCGM_FI_DEV_GPU_UTIL < 10"
              # 30분: 작업 사이의 유휴 시간을 허용하되, 장시간 미사용은 알림
              for    = "30m"
              labels = {
                severity = "info"
                team     = "gwp-platform"
              }
              annotations = {
                summary     = "GPU 사용률 30분간 10% 미만"
                description = "{{ $labels.instance }}의 GPU {{ $labels.gpu }}가 저활용 상태입니다. 비용 절감을 위해 GPU 노드 축소를 고려하세요."
              }
            },
          ]
        }
      ]
    }
  }

  depends_on = [helm_release.kube_prometheus_stack]
}
