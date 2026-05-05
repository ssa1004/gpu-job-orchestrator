# GPU Workload Platform - 클라우드 환경 변수

variable "environment" {
  description = "배포 환경 이름."
  type        = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment는 dev, staging, prod 중 하나여야 합니다."
  }
}

variable "aws_region" {
  description = <<-EOT
    배포할 AWS 리전. GPU 인스턴스를 사용할 수 있는 리전을 선택하세요.
    GPU 워크로드 권장 리전:
      - us-east-1, us-west-2 (가장 다양한 GPU 인스턴스 제공)
      - eu-west-1, ap-northeast-1 (APAC/EU 접근성)
    배포 전 AZ별 인스턴스 유형 가용 여부를 확인하세요.
  EOT
  type        = string
}

# 네트워크

variable "vpc_cidr" {
  description = "VPC CIDR 블록."
  type        = string
  default     = "10.0.0.0/16"
}

# EKS

variable "kubernetes_version" {
  description = "EKS용 Kubernetes 버전."
  type        = string
  default     = "1.29"
}

variable "eks_public_endpoint" {
  description = "EKS API 서버 퍼블릭 접근 활성화 여부."
  type        = bool
  default     = true
}

variable "eks_api_allowed_cidrs" {
  description = "EKS API 접근을 허용할 CIDR 블록 목록. 반드시 사무실/VPN CIDR로 제한하세요."
  type        = list(string)
}

variable "nvidia_device_plugin_version" {
  description = <<-EOT
    NVIDIA Device Plugin Helm 차트 버전.
    GPU AMI의 NVIDIA 드라이버와 일치해야 합니다:
      - v0.14.x: 드라이버 >= 470 (CUDA 11.x)
      - v0.15.x: 드라이버 >= 525 (CUDA 12.x)
  EOT
  type        = string
  default     = "0.15.0"
}

# GPU 노드

variable "gpu_instance_types" {
  description = "On-Demand 노드용 GPU 인스턴스 유형."
  type        = list(string)
  default     = ["g4dn.xlarge", "g4dn.2xlarge"]
}

variable "gpu_spot_instance_types" {
  description = "Spot 노드용 GPU 인스턴스 유형 (가용성을 위해 여러 유형 포함)."
  type        = list(string)
  default     = ["g4dn.xlarge", "g4dn.2xlarge", "g5.xlarge"]
}

variable "gpu_on_demand_desired" {
  description = "On-Demand GPU 노드의 희망 개수."
  type        = number
  default     = 1
}

variable "gpu_on_demand_min" {
  description = "On-Demand GPU 노드 최소 개수."
  type        = number
  default     = 0
}

variable "gpu_on_demand_max" {
  description = "On-Demand GPU 노드 최대 개수."
  type        = number
  default     = 5
}

variable "gpu_spot_desired" {
  description = "Spot GPU 노드의 희망 개수."
  type        = number
  default     = 0
}

variable "gpu_spot_min" {
  description = "Spot GPU 노드 최소 개수."
  type        = number
  default     = 0
}

variable "gpu_spot_max" {
  description = "Spot GPU 노드 최대 개수."
  type        = number
  default     = 10
}

variable "enable_model_cache" {
  description = "GPU 노드에 모델 캐시용 EBS 볼륨 연결 여부."
  type        = bool
  default     = false
}

# 스토리지

variable "cors_allowed_origins" {
  description = "S3 CORS 허용 오리진 (클라이언트 직접 업로드용)."
  type        = list(string)
  default     = ["https://*.example.com"]
}

variable "enable_efs" {
  description = "GPU Pod 간 공유 모델 스토리지용 EFS 활성화 여부."
  type        = bool
  default     = true
}

# 모니터링

variable "grafana_admin_password" {
  description = "Grafana 관리자 비밀번호."
  type        = string
  sensitive   = true
}

variable "grafana_ingress_host" {
  description = "Grafana Ingress 호스트명."
  type        = string
  default     = ""
}

# 레지스트리

variable "enable_nexus" {
  description = "Nexus 레지스트리 (Maven + PyPI 통합) 활성화 여부."
  type        = bool
  default     = true
}
