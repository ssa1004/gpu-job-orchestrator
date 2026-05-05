# GPU Workload Platform 온프레미스 환경
# 온프레미스 환경에서는 Terraform으로 Kubernetes 리소스와 Helm 차트만 관리합니다.
# 물리 서버 프로비저닝은 Ansible이 담당합니다.

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.25"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.12"
    }
    # registry 모듈이 ECR 리소스를 포함하므로 AWS 프로바이더가 필요합니다.
    # enable_ecr = false이므로 실제 AWS 리소스는 생성되지 않습니다.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # 온프레미스: 로컬 또는 Consul 백엔드
  backend "local" {
    path = "terraform.tfstate"
  }
}

# Kubernetes 프로바이더 (k3s 클러스터 연결)
provider "kubernetes" {
  config_path = var.kubeconfig_path
}

provider "helm" {
  kubernetes {
    config_path = var.kubeconfig_path
  }
}

# registry 모듈이 aws_ecr_* 리소스를 포함하므로 AWS 프로바이더가 필요합니다.
# enable_ecr = false이므로 실제 AWS API 호출은 발생하지 않습니다.
provider "aws" {
  region                      = "us-east-1"
  skip_credentials_validation = true
  skip_requesting_account_id  = true
}

# 변수
variable "kubeconfig_path" {
  description = "k3s kubeconfig 파일 경로"
  type        = string
  default     = "~/.kube/config"
}

variable "environment" {
  description = "환경 이름"
  type        = string
  default     = "onprem"
}

variable "domain" {
  description = "서비스 내부 도메인"
  type        = string
  default     = "np.internal"
}

variable "grafana_admin_password" {
  description = "Grafana 관리자 비밀번호."
  type        = string
  sensitive   = true
}

# Helm 차트 레포지토리 (폐쇄망에서는 내부 Harbor URL로 교체)
variable "helm_repository_nvidia" {
  type    = string
  default = "https://nvidia.github.io/k8s-device-plugin"
}

variable "helm_repository_metallb" {
  type    = string
  default = "https://metallb.github.io/metallb"
}

variable "harbor_admin_password" {
  description = "Harbor 관리자 비밀번호."
  type        = string
  sensitive   = true
}

# 네임스페이스
resource "kubernetes_namespace" "gwp" {
  metadata {
    name   = "gwp"
    labels = {
      environment = var.environment
      managed-by  = "terraform"
    }
  }
}

# NVIDIA Device Plugin (GPU 스케줄링)
resource "helm_release" "nvidia_device_plugin" {
  name       = "nvidia-device-plugin"
  repository = var.helm_repository_nvidia
  chart      = "nvidia-device-plugin"
  # 0.14.3: 온프레미스 NVIDIA 드라이버(470.x 이상)와 호환되는 버전
  version    = "0.14.3"
  namespace  = "kube-system"

  set {
    name  = "runtimeClassName"
    value = "nvidia"
  }

  # GPU 노드 taint를 tolerate하여 GPU taint 설정된 노드에서도 실행
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
}

# MetalLB (온프레미스 LoadBalancer)
resource "helm_release" "metallb" {
  name       = "metallb"
  repository = var.helm_repository_metallb
  chart      = "metallb"
  # 0.14.3: 안정적인 L2/BGP 모드를 지원하는 최신 안정 버전
  version    = "0.14.3"
  namespace  = "metallb-system"

  create_namespace = true
}

# 모니터링 스택
module "monitoring" {
  source = "../../modules/monitoring"

  environment            = var.environment
  monitoring_namespace   = "monitoring"
  loki_namespace         = "loki"
  storage_class_name     = "local-path"
  grafana_admin_password = var.grafana_admin_password
  grafana_service_type   = "ClusterIP"
  grafana_ingress_host   = "grafana.${var.domain}"
  enable_dcgm_exporter   = true
  enable_gpu_alerts      = true
  loki_storage_type      = "s3"  # MinIO (S3 호환) 백엔드 사용
  loki_s3_bucket         = "gwp-logs"
  tempo_s3_bucket        = "gwp-traces"
  mimir_s3_bucket        = "gwp-metrics"
  loki_retention_period  = "744h"
}

# Harbor 레지스트리
module "registry" {
  source = "../../modules/registry"

  environment          = var.environment
  enable_ecr           = false
  enable_harbor         = true
  harbor_namespace      = "harbor"
  harbor_external_url   = "https://harbor.${var.domain}"
  harbor_admin_password = var.harbor_admin_password
  harbor_ingress_host   = "harbor.${var.domain}"
  harbor_storage_class  = "local-path"
  enable_nexus          = true
  nexus_namespace      = "nexus"
  nexus_storage_class  = "local-path"
  nexus_service_type   = "ClusterIP"
}
