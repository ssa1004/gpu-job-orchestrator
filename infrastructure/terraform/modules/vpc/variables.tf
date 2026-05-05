# VPC 모듈 변수

variable "environment" {
  description = "배포 환경."
  type        = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment는 dev, staging, prod 중 하나여야 합니다."
  }
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록. 서브넷 분할을 위해 /16 ~ /20 범위를 권장합니다."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "VPC CIDR은 유효한 IPv4 CIDR 블록이어야 합니다."
  }

  validation {
    condition     = tonumber(split("/", var.vpc_cidr)[1]) >= 16 && tonumber(split("/", var.vpc_cidr)[1]) <= 20
    error_message = "VPC CIDR 프리픽스는 /16 ~ /20 범위여야 합니다. 서브넷 분할에 충분한 IP 공간이 필요합니다."
  }
}

variable "availability_zones" {
  description = "가용 영역 목록."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) >= 2
    error_message = "고가용성을 위해 최소 2개의 가용 영역이 필요합니다."
  }
}

variable "aws_region" {
  description = "AWS 리전."
  type        = string
}

variable "single_nat_gateway" {
  description = "단일 NAT Gateway 사용 (개발/스테이징 비용 절감용)"
  type        = bool
  default     = false
}

variable "enable_vpc_endpoints" {
  description = "ECR/STS VPC Interface Endpoint 생성 여부"
  type        = bool
  default     = true
}

variable "enable_flow_logs" {
  description = "VPC Flow Logs 활성화 여부"
  type        = bool
  default     = true
}

variable "flow_log_retention_days" {
  description = "Flow Logs 보관 일수."
  type        = number
  default     = 30
}

variable "tags" {
  description = "추가 태그."
  type        = map(string)
  default     = {}
}
