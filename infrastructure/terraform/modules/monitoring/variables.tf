# GPU Workload Platform - 모니터링 모듈 변수

variable "environment" {
  description = "배포 환경 (dev, staging, prod)."
  type        = string
}

# Helm 차트 레포지토리 URL (폐쇄망에서는 내부 Harbor OCI URL로 교체)
variable "helm_repository_prometheus" {
  description = "Prometheus 커뮤니티 Helm 차트 레포지토리 URL."
  type        = string
  default     = "https://prometheus-community.github.io/helm-charts"
}

variable "helm_repository_grafana" {
  description = "Grafana Helm 차트 레포지토리 URL (Loki, Tempo, Mimir, Promtail)."
  type        = string
  default     = "https://grafana.github.io/helm-charts"
}

variable "helm_repository_nvidia" {
  description = "NVIDIA DCGM Exporter Helm 차트 레포지토리 URL."
  type        = string
  default     = "https://nvidia.github.io/dcgm-exporter/helm-charts"
}

variable "aws_region" {
  description = "AWS 리전 (Loki S3 스토리지 설정에 사용)."
  type        = string
  default     = ""
}

# 네임스페이스 설정

variable "monitoring_namespace" {
  description = "Prometheus 및 Grafana용 Kubernetes 네임스페이스."
  type        = string
  default     = "monitoring"
}

variable "loki_namespace" {
  description = "Loki 로그 수집용 Kubernetes 네임스페이스."
  type        = string
  default     = "loki"
}

# Prometheus 설정

variable "prometheus_stack_version" {
  description = "kube-prometheus-stack Helm 차트 버전."
  type        = string
  default     = "56.6.2"
}

variable "prometheus_retention" {
  description = "Prometheus 메트릭 데이터 보관 기간."
  type        = string
  default     = "15d"
}

variable "prometheus_storage_size" {
  description = "Prometheus 데이터용 영구 볼륨 크기."
  type        = string
  default     = "50Gi"
}

variable "storage_class_name" {
  description = "영구 볼륨용 Kubernetes StorageClass."
  type        = string
  default     = "gp3"
}

# Grafana 설정

variable "enable_grafana" {
  description = "Grafana 배포 활성화 여부."
  type        = bool
  default     = true
}

variable "grafana_admin_password" {
  description = "Grafana 관리자 비밀번호. 프로덕션에서는 시크릿 매니저를 사용하세요."
  type        = string
  sensitive   = true
}

variable "grafana_storage_size" {
  description = "Grafana 데이터용 영구 볼륨 크기."
  type        = string
  default     = "10Gi"
}

variable "grafana_service_type" {
  description = "Grafana의 Kubernetes 서비스 유형 (클라우드: LoadBalancer, 온프레미스: ClusterIP)."
  type        = string
  default     = "ClusterIP"
}

variable "grafana_ingress_host" {
  description = "Grafana Ingress 호스트명. 빈 값이면 Ingress 비활성화."
  type        = string
  default     = ""
}

# AlertManager 설정

variable "enable_alertmanager" {
  description = "AlertManager 배포 활성화 여부."
  type        = bool
  default     = true
}

variable "enable_gpu_alerts" {
  description = "GPU Workload Platform 워크로드용 커스텀 GPU 알림 규칙 배포 여부."
  type        = bool
  default     = true
}

# DCGM Exporter 설정

variable "enable_dcgm_exporter" {
  description = <<-EOT
    GPU 메트릭용 NVIDIA DCGM Exporter 배포 여부. GPU 노드에
    NVIDIA 드라이버 >= 450.x와 DCGM 라이브러리가 필요합니다.
    제공하는 메트릭:
      - GPU 사용률, 메모리 사용량, 온도
      - Tensor Core 사용률 (양자화 워크로드에 유용)
      - 전력 소비 (비용 추정용)
  EOT
  type        = bool
  default     = true
}

variable "dcgm_exporter_version" {
  description = "NVIDIA DCGM Exporter Helm 차트 버전."
  type        = string
  default     = "3.3.5"
}

# Loki 설정

variable "enable_loki" {
  description = "Loki 로그 수집 스택 활성화 여부."
  type        = bool
  default     = true
}

variable "loki_version" {
  description = "Grafana Loki Helm 차트 버전."
  type        = string
  default     = "6.6.2"
}

variable "promtail_version" {
  description = "Promtail 로그 에이전트 Helm 차트 버전."
  type        = string
  default     = "6.16.2"
}

variable "loki_storage_type" {
  description = "Loki 스토리지 백엔드 (클라우드: s3, 온프레미스: filesystem)."
  type        = string
  default     = "s3"

  validation {
    condition     = contains(["s3", "filesystem", "gcs"], var.loki_storage_type)
    error_message = "Loki 스토리지 유형은 's3', 'filesystem', 또는 'gcs'여야 합니다."
  }
}

variable "loki_s3_bucket" {
  description = "Loki 로그 스토리지용 S3 버킷 이름."
  type        = string
  default     = ""
}

variable "loki_retention_period" {
  description = "Loki 로그 보관 기간."
  type        = string
  default     = "744h" # 744시간(31일)
}

# Tempo

variable "enable_tempo" {
  description = "Tempo 분산 트레이싱 백엔드 배포 활성화 여부."
  type        = bool
  default     = true
}

variable "tempo_version" {
  description = "Tempo Helm 차트 버전."
  type        = string
  default     = "1.10.0"
}

variable "tempo_s3_bucket" {
  description = "Tempo 트레이스 저장용 S3/MinIO 버킷명."
  type        = string
  default     = "gwp-tempo"
}

# Mimir

variable "enable_mimir" {
  description = "Mimir 장기 메트릭 저장소 배포 활성화 여부."
  type        = bool
  default     = true
}

variable "mimir_version" {
  description = "Mimir Helm 차트 버전."
  type        = string
  default     = "5.3.0"
}

variable "mimir_s3_bucket" {
  description = "Mimir 메트릭 저장용 S3/MinIO 버킷명."
  type        = string
  default     = "gwp-mimir"
}

variable "tags" {
  description = "모든 리소스에 적용할 추가 태그."
  type        = map(string)
  default     = {}
}
