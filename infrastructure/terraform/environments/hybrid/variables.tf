# GPU Workload Platform - 하이브리드 환경 변수

variable "environment" {
  description = "배포 환경 이름."
  type        = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment는 dev, staging, prod 중 하나여야 합니다."
  }
}

variable "aws_region" {
  description = "클라우드 컴포넌트용 AWS 리전."
  type        = string
}

# 네트워크

variable "vpc_cidr" {
  description = "클라우드 측 VPC CIDR 블록."
  type        = string
  default     = "10.0.0.0/16"
}

# VPN / 온프레미스 연결

variable "enable_vpn" {
  description = "온프레미스 네트워크로의 AWS Site-to-Site VPN 활성화 여부."
  type        = bool
  default     = true
}

variable "enable_vpn_bgp" {
  description = "VPN 라우팅에 BGP 사용 여부 (false = 정적 라우트)."
  type        = bool
  default     = false
}

variable "onprem_vpn_ip" {
  description = "온프레미스 VPN 엔드포인트의 퍼블릭 IP 주소."
  type        = string
  default     = ""
}

variable "onprem_bgp_asn" {
  description = "온프레미스 네트워크의 BGP ASN."
  type        = number
  default     = 65000
}

variable "onprem_cidr_blocks" {
  description = "온프레미스 네트워크의 CIDR 블록 (VPN 라우팅용)."
  type        = list(string)
  default     = ["192.168.0.0/16"]
}

# 온프레미스 Kubernetes

variable "onprem_kubeconfig_path" {
  description = "온프레미스 Kubernetes 클러스터의 kubeconfig 파일 경로."
  type        = string
  default     = "~/.kube/config"
}

variable "onprem_kubeconfig_context" {
  description = "온프레미스 클러스터의 kubeconfig 컨텍스트."
  type        = string
  default     = "onprem-gwp"
}

variable "onprem_storage_class" {
  description = "온프레미스 Kubernetes 클러스터의 StorageClass 이름."
  type        = string
  default     = "local-path"
}

# EKS (클라우드)

variable "kubernetes_version" {
  description = "EKS용 Kubernetes 버전."
  type        = string
  default     = "1.29"
}

variable "eks_public_endpoint" {
  description = "EKS API 서버 퍼블릭 접근 활성화 여부."
  type        = bool
  default     = false
}

variable "eks_api_allowed_cidrs" {
  description = "EKS API 접근을 허용할 CIDR 블록 목록."
  type        = list(string)
  default     = []
}

variable "nvidia_device_plugin_version" {
  description = <<-EOT
    NVIDIA Device Plugin Helm 차트 버전.
    중요: 일관된 GPU 스케줄링 동작을 보장하기 위해 클라우드와
    온프레미스 클러스터 모두에서 동일한 버전을 사용해야 합니다.

    드라이버/CUDA 호환성 (양쪽 환경에서 일치해야 함):
      - v0.14.x: NVIDIA 드라이버 >= 470 (CUDA 11.x)
      - v0.15.x: NVIDIA 드라이버 >= 525 (CUDA 12.x)
  EOT
  type        = string
  default     = "0.15.0"
}

# GPU 노드 (클라우드) - 버스트 용량

variable "gpu_instance_types" {
  description = "클라우드 On-Demand 노드용 GPU 인스턴스 유형."
  type        = list(string)
  default     = ["g4dn.xlarge", "g4dn.2xlarge"]
}

variable "gpu_spot_instance_types" {
  description = "클라우드 Spot 노드용 GPU 인스턴스 유형."
  type        = list(string)
  default     = ["g4dn.xlarge", "g4dn.2xlarge", "g5.xlarge"]
}

variable "cloud_gpu_on_demand_desired" {
  description = "클라우드 On-Demand GPU 노드 희망 개수 (0 = 유휴 시 0으로 축소)."
  type        = number
  default     = 0
}

variable "cloud_gpu_on_demand_min" {
  description = "클라우드 On-Demand GPU 노드 최소 개수."
  type        = number
  default     = 0
}

variable "cloud_gpu_on_demand_max" {
  description = "버스트 스케일링을 위한 클라우드 On-Demand GPU 노드 최대 개수."
  type        = number
  default     = 10
}

variable "cloud_gpu_spot_desired" {
  description = "클라우드 Spot GPU 노드 희망 개수."
  type        = number
  default     = 0
}

variable "cloud_gpu_spot_min" {
  description = "클라우드 Spot GPU 노드 최소 개수."
  type        = number
  default     = 0
}

variable "cloud_gpu_spot_max" {
  description = "클라우드 Spot GPU 노드 최대 개수."
  type        = number
  default     = 20
}

# 모니터링

variable "grafana_admin_password" {
  description = "Grafana 관리자 비밀번호 (클라우드와 온프레미스 간 공유)."
  type        = string
  sensitive   = true
}

# 레지스트리 - 클라우드

variable "ecr_cross_account_ids" {
  description = "ECR 크로스 계정 접근을 허용할 AWS 계정 ID."
  type        = list(string)
  default     = []
}

# 레지스트리 - 온프레미스

variable "harbor_external_url" {
  description = "온프레미스 Harbor 레지스트리의 외부 URL."
  type        = string
  default     = "https://harbor.gwp.local"
}

variable "harbor_admin_password" {
  description = "Harbor 관리자 비밀번호."
  type        = string
  sensitive   = true
}

variable "harbor_storage_size" {
  description = "Harbor 레지스트리 스토리지 크기 (GPU 이미지는 개당 5~15GB)."
  type        = string
  default     = "500Gi"
}
