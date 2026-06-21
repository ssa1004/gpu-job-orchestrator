# AWS 클라우드 환경 - 전체 모듈 통합
# On-Demand + Spot GPU 노드, S3/EFS, Prometheus/Grafana, ECR

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.23"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.11"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

# 프로바이더 설정
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.common_tags
  }
}

# Kubernetes 및 Helm 프로바이더는 EKS 클러스터 인증 정보를 사용
provider "kubernetes" {
  host                   = module.eks.cluster_endpoint
  cluster_ca_certificate = base64decode(module.eks.cluster_certificate_authority_data)

  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", module.eks.cluster_name]
  }
}

provider "helm" {
  kubernetes {
    host                   = module.eks.cluster_endpoint
    cluster_ca_certificate = base64decode(module.eks.cluster_certificate_authority_data)

    exec {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", module.eks.cluster_name]
    }
  }
}

# 데이터 소스
data "aws_caller_identity" "current" {}

data "aws_availability_zones" "available" {
  state = "available"

  # GPU 인스턴스를 지원하는 AZ만 필터링합니다.
  # 모든 AZ에서 p3/g4dn/g5 인스턴스를 사용할 수 있는 것은 아닙니다.
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

# 로컬 변수
locals {
  common_tags = {
    Environment = var.environment
    Project     = "gwp"
    ManagedBy   = "terraform"
    Team        = "platform"
  }

  # 사용 가능한 처음 3개 AZ를 사용
  azs = slice(data.aws_availability_zones.available.names, 0, min(3, length(data.aws_availability_zones.available.names)))
}

# VPC 모듈
module "vpc" {
  source = "../../modules/vpc"

  environment        = var.environment
  vpc_cidr           = var.vpc_cidr
  availability_zones = local.azs
  aws_region         = var.aws_region

  # 비 프로덕션 환경에서는 단일 NAT Gateway로 비용 절감
  single_nat_gateway   = var.environment != "prod"
  enable_vpc_endpoints = true
  # 프로덕션에서만 Flow Logs 활성화 (비용 고려)
  enable_flow_logs = var.environment == "prod"

  tags = local.common_tags
}

# EKS 모듈
module "eks" {
  source = "../../modules/eks"

  environment        = var.environment
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  public_subnet_ids  = module.vpc.public_subnet_ids
  aws_region         = var.aws_region

  kubernetes_version        = var.kubernetes_version
  enable_public_endpoint    = var.eks_public_endpoint
  cluster_api_allowed_cidrs = var.eks_api_allowed_cidrs
  enable_cluster_autoscaler = true

  # NVIDIA Device Plugin 버전은 GPU AMI의 드라이버와 일치해야 함
  # EKS GPU AMI(AL2): 드라이버 535.x -> Device Plugin 0.15.x
  nvidia_device_plugin_version = var.nvidia_device_plugin_version

  tags = local.common_tags
}

# GPU 노드 그룹
module "gpu_nodes" {
  source = "../../modules/gpu-nodes"

  environment        = var.environment
  cluster_name       = module.eks.cluster_name
  vpc_id             = module.vpc.vpc_id
  vpc_cidr           = var.vpc_cidr
  private_subnet_ids = module.vpc.private_subnet_ids

  # GPU 워크로드용 인스턴스 유형
  gpu_instance_types      = var.gpu_instance_types
  gpu_spot_instance_types = var.gpu_spot_instance_types

  # SLA 워크로드를 위한 On-Demand
  on_demand_desired_size = var.gpu_on_demand_desired
  on_demand_min_size     = var.gpu_on_demand_min
  on_demand_max_size     = var.gpu_on_demand_max

  # 비용 최적화를 위한 Spot
  spot_desired_size = var.gpu_spot_desired
  spot_min_size     = var.gpu_spot_min
  spot_max_size     = var.gpu_spot_max

  # GPU 노드 디스크 및 캐시 설정
  # 100GB: CUDA 라이브러리, 컨테이너 이미지, 임시 아티팩트에 적합한 루트 볼륨 크기
  node_disk_size_gb         = 100
  enable_model_cache_volume = var.enable_model_cache
  # 200GB: 여러 대규모 모델을 로컬 캐시하기에 충분한 크기
  model_cache_volume_size_gb = 200

  tags = local.common_tags

  depends_on = [module.eks]
}

# 스토리지 모듈
module "storage" {
  source = "../../modules/storage"

  environment        = var.environment
  aws_account_id     = data.aws_caller_identity.current.account_id
  vpc_id             = module.vpc.vpc_id
  vpc_cidr           = var.vpc_cidr
  private_subnet_ids = module.vpc.private_subnet_ids

  enable_efs           = var.enable_efs
  cors_allowed_origins = var.cors_allowed_origins
  # 프로덕션: provisioned throughput으로 예측 가능한 성능, 개발: bursting으로 비용 절감
  efs_throughput_mode = var.environment == "prod" ? "provisioned" : "bursting"

  # 프로덕션: 체크포인트 30일, 아티팩트 180일 / 개발: 각각 7일, 30일
  checkpoint_retention_days = var.environment == "prod" ? 30 : 7
  artifact_retention_days   = var.environment == "prod" ? 180 : 30

  tags = local.common_tags
}

# 모니터링 모듈
module "monitoring" {
  source = "../../modules/monitoring"

  environment = var.environment
  aws_region  = var.aws_region

  # Prometheus - 프로덕션: 30일 보관/100Gi, 개발: 7일 보관/30Gi
  prometheus_retention    = var.environment == "prod" ? "30d" : "7d"
  prometheus_storage_size = var.environment == "prod" ? "100Gi" : "30Gi"

  # Grafana
  enable_grafana         = true
  grafana_admin_password = var.grafana_admin_password
  grafana_service_type   = "LoadBalancer"
  grafana_ingress_host   = var.grafana_ingress_host

  # GPU 모니터링
  enable_dcgm_exporter = true
  enable_gpu_alerts    = true

  # Loki 로그 수집
  enable_loki       = true
  loki_storage_type = "s3"
  loki_s3_bucket    = "${var.environment}-gwp-loki-${data.aws_caller_identity.current.account_id}"
  # 프로덕션: 2160시간(90일), 개발: 168시간(7일)
  loki_retention_period = var.environment == "prod" ? "2160h" : "168h"

  tags = local.common_tags

  depends_on = [module.eks]
}

# 레지스트리 모듈
module "registry" {
  source = "../../modules/registry"

  environment = var.environment

  # 컨테이너 이미지를 위한 ECR
  enable_ecr = true
  # 프로덕션: 50개(안정적인 릴리스 이력 보관), 개발: 20개(최근 빌드만 보관)
  ecr_max_image_count = var.environment == "prod" ? 50 : 20

  # Maven/PyPI 패키지를 위한 Nexus
  enable_nexus       = var.enable_nexus
  nexus_storage_size = "50Gi"

  # 클라우드 전용 배포에서는 Harbor 불필요
  enable_harbor = false

  tags = local.common_tags

  depends_on = [module.eks]
}

# Loki 로그용 S3 버킷
resource "aws_s3_bucket" "loki_logs" {
  bucket        = "${var.environment}-gwp-loki-${data.aws_caller_identity.current.account_id}"
  force_destroy = var.environment != "prod"

  tags = merge(local.common_tags, {
    Name    = "${var.environment}-gwp-loki-logs"
    Purpose = "log-storage"
  })
}

resource "aws_s3_bucket_server_side_encryption_configuration" "loki_logs" {
  bucket = aws_s3_bucket.loki_logs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "loki_logs" {
  bucket = aws_s3_bucket.loki_logs.id

  rule {
    id     = "expire-old-logs"
    status = "Enabled"

    filter {
      prefix = ""
    }

    # 프로덕션: 90일(감사 요구사항 충족), 개발: 14일(비용 절감)
    expiration {
      days = var.environment == "prod" ? 90 : 14
    }
  }
}

resource "aws_s3_bucket_public_access_block" "loki_logs" {
  bucket = aws_s3_bucket.loki_logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
