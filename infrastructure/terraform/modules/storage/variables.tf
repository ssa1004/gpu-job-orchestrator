# GPU Workload Platform - 스토리지 모듈 변수

variable "environment" {
  description = "배포 환경 (dev, staging, prod)."
  type        = string
}

variable "aws_account_id" {
  description = "AWS 계정 ID. 전역적으로 고유한 S3 버킷 이름 생성에 사용."
  type        = string
}

variable "vpc_id" {
  description = "EFS 보안 그룹을 위한 VPC ID."
  type        = string
}

variable "vpc_cidr" {
  description = "EFS 보안 그룹 인그레스 규칙을 위한 VPC CIDR 블록."
  type        = string
}

variable "private_subnet_ids" {
  description = "EFS 마운트 타겟을 위한 프라이빗 서브넷 ID 목록."
  type        = list(string)
}

# S3 설정

variable "kms_key_arn" {
  description = "S3 및 EFS 암호화용 KMS 키 ARN. 빈 값이면 기본 암호화 사용."
  type        = string
  default     = ""
}

variable "checkpoint_retention_days" {
  description = "S3에서 최적화 체크포인트 파일을 보관할 일수."
  type        = number
  default     = 14
}

variable "artifact_retention_days" {
  description = "S3에서 파이프라인 아티팩트를 보관할 일수."
  type        = number
  default     = 90
}

variable "cors_allowed_origins" {
  description = "S3 CORS 허용 오리진 (클라이언트 직접 업로드용). 프로덕션에서는 반드시 특정 도메인으로 제한하세요."
  type        = list(string)
}

variable "optimizer_role_arn" {
  description = "GPU 워크로드 Pod의 IAM 역할 ARN (IRSA: IAM Roles for Service Accounts). S3 버킷 정책에 사용."
  type        = string
  default     = ""
}

# EFS 설정

variable "enable_efs" {
  description = <<-EOT
    공유 모델 스토리지용 EFS 활성화 여부. 필요한 경우:
      - 여러 GPU Pod가 동일한 모델에 동시 접근해야 할 때
      - 여러 노드에 걸친 분산 최적화
      - ReadWriteMany(RWX) PVC 접근 모드가 필요할 때
  EOT
  type        = bool
  default     = true
}

variable "efs_performance_mode" {
  description = "EFS 성능 모드. 고도의 병렬 GPU 워크로드에는 'maxIO'를 사용하세요."
  type        = string
  default     = "generalPurpose"

  validation {
    condition     = contains(["generalPurpose", "maxIO"], var.efs_performance_mode)
    error_message = "EFS 성능 모드는 'generalPurpose' 또는 'maxIO'여야 합니다."
  }
}

variable "efs_throughput_mode" {
  description = "EFS 처리량 모드. 예측 가능한 대용량 아티팩트 접근에는 'provisioned'를 사용하세요."
  type        = string
  default     = "bursting"

  validation {
    condition     = contains(["bursting", "elastic", "provisioned"], var.efs_throughput_mode)
    error_message = "EFS 처리량 모드는 'bursting', 'elastic', 또는 'provisioned'여야 합니다."
  }
}

variable "efs_provisioned_throughput_mibps" {
  description = "Provisioned throughput(MiB/s). throughput_mode가 'provisioned'일 때만 사용."
  type        = number
  default     = 256
}

variable "tags" {
  description = "모든 리소스에 적용할 추가 태그."
  type        = map(string)
  default     = {}
}
