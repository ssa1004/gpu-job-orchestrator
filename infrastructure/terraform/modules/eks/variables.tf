# GPU Workload Platform - EKS 모듈 변수

variable "environment" {
  description = "배포 환경 (dev, staging, prod)."
  type        = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment는 dev, staging, prod 중 하나여야 합니다."
  }
}

# Helm 차트 레포지토리 URL (폐쇄망에서는 내부 Harbor OCI URL로 교체)
variable "helm_repository_nvidia" {
  description = "NVIDIA Device Plugin Helm 차트 레포지토리 URL."
  type        = string
  default     = "https://nvidia.github.io/k8s-device-plugin"
}

variable "helm_repository_autoscaler" {
  description = "Cluster Autoscaler Helm 차트 레포지토리 URL."
  type        = string
  default     = "https://kubernetes.github.io/autoscaler"
}

variable "helm_repository_external_secrets" {
  description = "External Secrets Operator Helm 차트 레포지토리 URL."
  type        = string
  default     = "https://charts.external-secrets.io"
}

variable "helm_repository_kyverno" {
  description = "Kyverno Helm 차트 레포지토리 URL."
  type        = string
  default     = "https://kyverno.github.io/kyverno"
}

variable "vpc_id" {
  description = "EKS 클러스터를 생성할 VPC의 ID."
  type        = string
}

variable "private_subnet_ids" {
  description = "EKS 워커 노드용 프라이빗 서브넷 ID 목록."
  type        = list(string)
}

variable "public_subnet_ids" {
  description = "EKS 퍼블릭 리소스용 퍼블릭 서브넷 ID 목록."
  type        = list(string)
}

variable "aws_region" {
  description = "AWS 리전."
  type        = string
}

variable "kubernetes_version" {
  description = <<-EOT
    EKS 클러스터의 Kubernetes 버전. NVIDIA Device Plugin 버전 및
    GPU AMI와 호환되어야 합니다. 지원 버전은 EKS 릴리스 일정을 확인하세요.
  EOT
  type        = string
  default     = "1.29"
}

variable "enable_public_endpoint" {
  description = "EKS API 퍼블릭 엔드포인트 활성화 여부. 프로덕션에서는 false 권장."
  type        = bool
  default     = false
}

variable "cluster_api_allowed_cidrs" {
  description = "EKS API 접근 허용 CIDR 목록. 퍼블릭 엔드포인트 활성화 시 반드시 지정해야 합니다."
  type        = list(string)
  default     = []
}

variable "cluster_log_types" {
  description = <<-EOT
    활성화할 EKS 컨트롤 플레인 로그 유형 목록. 'scheduler' 로그는
    GPU Pod 스케줄링 실패를 디버깅할 때 특히 유용합니다.
  EOT
  type        = list(string)
  default     = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
}

variable "cluster_encryption_key_arn" {
  description = "Kubernetes Secret 암호화용 KMS 키 ARN. 빈 값이면 암호화 비활성화."
  type        = string
  default     = ""
}

# EKS 애드온 버전
# 선택한 Kubernetes 버전과 호환되는 버전으로 고정해야 합니다.
# 확인 명령: aws eks describe-addon-versions --kubernetes-version X.XX

variable "vpc_cni_version" {
  description = "VPC CNI 애드온 버전."
  type        = string
  default     = null
}

variable "coredns_version" {
  description = "CoreDNS 애드온 버전."
  type        = string
  default     = null
}

variable "kube_proxy_version" {
  description = "kube-proxy 애드온 버전."
  type        = string
  default     = null
}

# NVIDIA Device Plugin

variable "nvidia_device_plugin_version" {
  description = <<-EOT
    NVIDIA k8s Device Plugin Helm 차트 버전.
    호환성 참고:
      - v0.14.x: NVIDIA 드라이버 >= 470.x, CUDA >= 11.x 지원
      - v0.15.x: NVIDIA 드라이버 >= 525.x, CUDA >= 12.x 지원
    Device Plugin 버전은 GPU AMI의 NVIDIA 드라이버와 일치해야 합니다.
  EOT
  type        = string
  default     = "0.15.0"
}

# Cluster Autoscaler

variable "enable_cluster_autoscaler" {
  description = "GPU 노드 동적 스케일링용 Kubernetes Cluster Autoscaler 배포 여부."
  type        = bool
  default     = true
}

variable "cluster_autoscaler_version" {
  description = "Cluster Autoscaler Helm 차트 버전."
  type        = string
  default     = "9.35.0"
}

# External Secrets Operator

variable "enable_external_secrets" {
  description = "External Secrets Operator 배포 활성화 여부."
  type        = bool
  default     = true
}

variable "external_secrets_version" {
  description = "External Secrets Operator Helm 차트 버전."
  type        = string
  default     = "0.9.0"
}

# Kyverno

variable "enable_kyverno" {
  description = "Kyverno 정책 엔진 배포 활성화 여부."
  type        = bool
  default     = true
}

variable "kyverno_version" {
  description = "Kyverno Helm 차트 버전."
  type        = string
  default     = "3.2.0"
}

variable "tags" {
  description = "모든 리소스에 적용할 추가 태그."
  type        = map(string)
  default     = {}
}
