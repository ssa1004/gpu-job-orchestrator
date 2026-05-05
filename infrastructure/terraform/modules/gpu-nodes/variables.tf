# GPU Workload Platform - GPU 노드 그룹 모듈 변수

variable "environment" {
  description = "배포 환경 (dev, staging, prod)."
  type        = string
}

variable "cluster_name" {
  description = "GPU 노드 그룹을 연결할 EKS 클러스터의 이름."
  type        = string
}

variable "vpc_id" {
  description = "VPC의 ID."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC의 CIDR 블록 (보안 그룹 규칙에 사용)."
  type        = string
}

variable "private_subnet_ids" {
  description = "GPU 노드를 배치할 프라이빗 서브넷 ID 목록."
  type        = list(string)
}

# 인스턴스 유형 설정

variable "gpu_instance_types" {
  description = <<-EOT
    On-Demand 노드 그룹의 GPU 인스턴스 유형 목록. 순서가 중요합니다:
    선택된 AZ에서 첫 번째로 사용 가능한 유형이 사용됩니다.

    GPU Workload Platform GPU 워크로드 권장 사항:
      - g4dn.xlarge:  1x T4 (16GB), 추론 최적화에 비용 효율적
      - g5.xlarge:    1x A10G (24GB), 중간 규모 GPU 워크로드에 적합
      - p3.2xlarge:   1x V100 (16GB), 양자화에 높은 FP16 처리량
  EOT
  type        = list(string)
  default     = ["g4dn.xlarge", "g4dn.2xlarge"]
}

variable "gpu_spot_instance_types" {
  description = <<-EOT
    Spot 노드 그룹의 GPU 인스턴스 유형 목록. 인스턴스 풀 간
    Spot 용량 확보율을 높이기 위해 여러 유형을 포함하세요.
  EOT
  type        = list(string)
  default     = ["g4dn.xlarge", "g4dn.2xlarge", "g5.xlarge"]
}

# 스케일링 설정 - On-Demand

variable "on_demand_desired_size" {
  description = "On-Demand GPU 노드의 희망 개수."
  type        = number
  default     = 1
}

variable "on_demand_min_size" {
  description = "On-Demand GPU 노드의 최소 개수."
  type        = number
  default     = 0
}

variable "on_demand_max_size" {
  description = "On-Demand GPU 노드의 최대 개수."
  type        = number
  default     = 5
}

# 스케일링 설정 - Spot

variable "spot_desired_size" {
  description = "Spot GPU 노드의 희망 개수."
  type        = number
  default     = 0
}

variable "spot_min_size" {
  description = "Spot GPU 노드의 최소 개수."
  type        = number
  default     = 0
}

variable "spot_max_size" {
  description = "Spot GPU 노드의 최대 개수."
  type        = number
  default     = 10
}

# 노드 설정

variable "node_disk_size_gb" {
  description = <<-EOT
    GPU 노드의 루트 볼륨 크기(GB). GPU 노드는 CPU 노드보다 더 많은
    스토리지가 필요합니다:
      - NVIDIA 드라이버 및 CUDA 라이브러리 (~5-10 GB)
      - ML 프레임워크가 포함된 컨테이너 이미지 (~10-20 GB)
      - 최적화 과정에서 발생하는 임시 모델 아티팩트
  EOT
  type        = number
  default     = 100
}

variable "custom_ami_id" {
  description = <<-EOT
    GPU 노드용 커스텀 AMI ID. 빈 값이면 EKS 최적화 GPU AMI 사용.
    커스텀 AMI가 필요한 경우:
      - 특정 CUDA 버전이 필요할 때 (예: TensorRT 8.6 호환을 위한 CUDA 11.8)
      - GPU Workload Platform 최적화 라이브러리가 사전 설치된 AMI
      - 특정 NVIDIA 드라이버 버전이 필요한 경우
  EOT
  type        = string
  default     = ""
}

variable "enable_model_cache_volume" {
  description = "GPU 노드에 모델 아티팩트 캐싱용 추가 EBS 볼륨 연결 여부."
  type        = bool
  default     = false
}

variable "model_cache_volume_size_gb" {
  description = "모델 캐시 EBS 볼륨의 크기(GB)."
  type        = number
  default     = 200
}

variable "additional_node_labels" {
  description = "GPU 노드에 적용할 추가 Kubernetes 레이블."
  type        = map(string)
  default     = {}
}

variable "tags" {
  description = "모든 리소스에 적용할 추가 태그."
  type        = map(string)
  default     = {}
}
