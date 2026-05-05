# GPU Workload Platform - 레지스트리 모듈 변수

variable "environment" {
  description = "배포 환경 (dev, staging, prod)."
  type        = string
}

variable "helm_repository_harbor" {
  description = "Harbor Helm 차트 레포지토리 URL. 폐쇄망에서는 내부 URL로 교체."
  type        = string
  default     = "https://helm.goharbor.io"

  validation {
    condition     = contains(["dev", "staging", "prod", "onprem"], var.environment)
    error_message = "environment는 dev, staging, prod, onprem 중 하나여야 합니다."
  }
}

# ECR 설정 (AWS 클라우드)

variable "enable_ecr" {
  description = "컨테이너 이미지용 ECR 저장소 생성 여부."
  type        = bool
  default     = true
}

variable "kms_key_arn" {
  description = "ECR 이미지 암호화용 KMS 키 ARN. 빈 값이면 AES256 사용."
  type        = string
  default     = ""
}

variable "ecr_max_image_count" {
  description = "저장소당 보관할 태그된 이미지의 최대 개수."
  type        = number
  default     = 30

  validation {
    condition     = var.ecr_max_image_count >= 1 && var.ecr_max_image_count <= 1000
    error_message = "ecr_max_image_count는 1~1000 범위여야 합니다."
  }
}

variable "ecr_cross_account_ids" {
  description = "ECR 이미지 풀링을 허용할 AWS 계정 ID (크로스 계정 배포용)."
  type        = list(string)
  default     = []
}

# Harbor 설정 (온프레미스)

variable "enable_harbor" {
  description = "온프레미스 환경용 Harbor 컨테이너 레지스트리 배포 여부."
  type        = bool
  default     = false
}

variable "harbor_namespace" {
  description = "Harbor용 Kubernetes 네임스페이스."
  type        = string
  default     = "harbor"
}

variable "harbor_chart_version" {
  description = "Harbor Helm 차트 버전."
  type        = string
  default     = "1.14.0"
}

variable "harbor_external_url" {
  description = "Harbor 웹 UI 및 API의 외부 URL."
  type        = string
  default     = "https://harbor.gwp.local"
}

variable "harbor_admin_password" {
  description = "Harbor 관리자 비밀번호. 프로덕션에서는 시크릿 매니저를 사용하세요. enable_harbor = true일 때 필수."
  type        = string
  sensitive   = true
  default     = ""
}

variable "harbor_expose_type" {
  description = "Harbor 노출 방식 (ingress, nodePort, loadBalancer)."
  type        = string
  default     = "ingress"

  validation {
    condition     = contains(["ingress", "nodePort", "loadBalancer"], var.harbor_expose_type)
    error_message = "harbor_expose_type은 ingress, nodePort, loadBalancer 중 하나여야 합니다."
  }
}

variable "harbor_ingress_host" {
  description = "Harbor Ingress 호스트명."
  type        = string
  default     = "harbor.gwp.local"
}

variable "harbor_database_type" {
  description = "Harbor 데이터베이스 유형 (internal 또는 external PostgreSQL)."
  type        = string
  default     = "internal"

  validation {
    condition     = contains(["internal", "external"], var.harbor_database_type)
    error_message = "harbor_database_type은 internal 또는 external이어야 합니다."
  }
}

variable "harbor_storage_size" {
  description = "Harbor 레지스트리 데이터 스토리지 크기 (GPU 이미지는 개당 5~15GB)."
  type        = string
  default     = "200Gi"
}

variable "harbor_storage_class" {
  description = "Harbor 영구 볼륨용 StorageClass."
  type        = string
  default     = "standard"
}

# Nexus 설정 (PyPI)

variable "enable_nexus" {
  description = "Nexus 프라이빗 레지스트리 (Maven + PyPI) 배포 여부."
  type        = bool
  default     = false
}

variable "nexus_namespace" {
  description = "Nexus용 Kubernetes 네임스페이스."
  type        = string
  default     = "nexus"
}

variable "nexus_image" {
  description = "Nexus 서버 컨테이너 이미지. 프로덕션에서는 반드시 버전을 고정하세요."
  type        = string
  default     = "sonatype/nexus3:3.70.0"
}

variable "nexus_replicas" {
  description = "Nexus 레플리카 수."
  type        = number
  default     = 1
}

variable "nexus_storage_size" {
  description = "Nexus blob store 영구 볼륨 크기. PyPI + Maven 통합이라 Devpi 단독 대비 더 큼."
  type        = string
  default     = "100Gi"
}

variable "nexus_storage_class" {
  description = "Nexus 영구 볼륨용 StorageClass. AWS 환경에서는 'gp3', 온프레미스에서는 'local-path' 등으로 설정."
  type        = string
  default     = "standard"
}

variable "nexus_service_type" {
  description = "Nexus의 Kubernetes 서비스 유형 (ClusterIP, NodePort, LoadBalancer)."
  type        = string
  default     = "ClusterIP"

  validation {
    condition     = contains(["ClusterIP", "NodePort", "LoadBalancer"], var.nexus_service_type)
    error_message = "nexus_service_type은 ClusterIP, NodePort, LoadBalancer 중 하나여야 합니다."
  }
}

variable "nexus_ingress_enabled" {
  description = "Nexus용 Ingress 리소스 생성 여부."
  type        = bool
  default     = false
}

variable "nexus_ingress_host" {
  description = "Nexus Ingress 호스트명."
  type        = string
  default     = "nexus.gwp.local"
}

variable "nexus_ingress_class" {
  description = "Nexus Ingress에 사용할 IngressClass 이름."
  type        = string
  default     = "nginx"
}

variable "nexus_port" {
  description = "Nexus 서버 포트. 기본값은 Nexus 표준 포트 8081."
  type        = number
  default     = 8081

  validation {
    condition     = var.nexus_port > 0 && var.nexus_port <= 65535
    error_message = "nexus_port는 1~65535 범위의 유효한 포트 번호여야 합니다."
  }
}

variable "nexus_cpu_request" {
  description = "Nexus Pod의 CPU 요청량."
  type        = string
  default     = "500m"
}

variable "nexus_memory_request" {
  description = "Nexus Pod의 메모리 요청량. JVM 힙 2g + 컨테이너 오버헤드 고려."
  type        = string
  default     = "2.5Gi"
}

variable "nexus_cpu_limit" {
  description = "Nexus Pod의 CPU 제한량."
  type        = string
  default     = "2"
}

variable "nexus_memory_limit" {
  description = "Nexus Pod의 메모리 제한량. INSTALL4J_ADD_VM_PARAMS 의 Xmx 값과 정합."
  type        = string
  default     = "4Gi"
}

variable "tags" {
  description = "모든 리소스에 적용할 추가 태그."
  type        = map(string)
  default     = {}
}
