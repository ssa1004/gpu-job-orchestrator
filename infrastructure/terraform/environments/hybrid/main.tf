# 하이브리드 환경 - AWS EKS + 온프레미스 K8s (VPN 연결)
# 클라우드 버스트/데이터 주권/비용 최적화 지원, NVIDIA 530.x/CUDA 12.1 표준

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

      configuration_aliases = [
        kubernetes.cloud,
        kubernetes.onprem,
      ]
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.11"

      configuration_aliases = [
        helm.cloud,
        helm.onprem,
      ]
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

# 클라우드 Kubernetes 프로바이더 (EKS)
provider "kubernetes" {
  alias = "cloud"

  host                   = module.eks.cluster_endpoint
  cluster_ca_certificate = base64decode(module.eks.cluster_certificate_authority_data)

  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", module.eks.cluster_name]
  }
}

provider "helm" {
  alias = "cloud"

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

# 온프레미스 Kubernetes 프로바이더
provider "kubernetes" {
  alias = "onprem"

  config_path    = var.onprem_kubeconfig_path
  config_context = var.onprem_kubeconfig_context
}

provider "helm" {
  alias = "onprem"

  kubernetes {
    config_path    = var.onprem_kubeconfig_path
    config_context = var.onprem_kubeconfig_context
  }
}

# 데이터 소스
data "aws_caller_identity" "current" {}

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

# 로컬 변수
locals {
  common_tags = {
    Environment  = var.environment
    Project      = "gwp"
    ManagedBy    = "terraform"
    Team         = "platform"
    Architecture = "hybrid"
  }

  azs = slice(data.aws_availability_zones.available.names, 0, min(3, length(data.aws_availability_zones.available.names)))
}

# 클라우드 컴포넌트 (AWS)

# VPC (클라우드)
# 온프레미스 연결을 위한 VPN Gateway 연결 포함
module "vpc" {
  source = "../../modules/vpc"

  environment        = var.environment
  vpc_cidr           = var.vpc_cidr
  availability_zones = local.azs
  aws_region         = var.aws_region

  # 하이브리드 환경에서는 온프레미스 연결 보장을 위해 항상 HA NAT Gateway 사용
  single_nat_gateway   = false
  enable_vpc_endpoints = true
  enable_flow_logs     = true

  tags = local.common_tags
}

# 온프레미스 연결을 위한 VPN Gateway
# 클라우드 EKS와 온프레미스 K8s 클러스터 간 보안 통신 구성.
resource "aws_vpn_gateway" "main" {
  count  = var.enable_vpn ? 1 : 0
  vpc_id = module.vpc.vpc_id

  tags = merge(local.common_tags, {
    Name = "${var.environment}-gwp-vpn-gw"
  })
}

resource "aws_customer_gateway" "onprem" {
  count = var.enable_vpn ? 1 : 0

  bgp_asn    = var.onprem_bgp_asn
  ip_address = var.onprem_vpn_ip
  type       = "ipsec.1"

  tags = merge(local.common_tags, {
    Name = "${var.environment}-gwp-onprem-cgw"
  })
}

resource "aws_vpn_connection" "onprem" {
  count = var.enable_vpn ? 1 : 0

  vpn_gateway_id      = aws_vpn_gateway.main[0].id
  customer_gateway_id = aws_customer_gateway.onprem[0].id
  type                = "ipsec.1"
  static_routes_only  = !var.enable_vpn_bgp

  tags = merge(local.common_tags, {
    Name = "${var.environment}-gwp-vpn"
  })
}

# 온프레미스 네트워크로의 정적 라우트 (BGP 미사용 시)
resource "aws_vpn_connection_route" "onprem" {
  count = var.enable_vpn && !var.enable_vpn_bgp ? length(var.onprem_cidr_blocks) : 0

  destination_cidr_block = var.onprem_cidr_blocks[count.index]
  vpn_connection_id      = aws_vpn_connection.onprem[0].id
}

# VPN 라우트를 프라이빗 라우트 테이블에 전파
resource "aws_vpn_gateway_route_propagation" "private" {
  count = var.enable_vpn ? length(module.vpc.private_route_table_ids) : 0

  vpn_gateway_id = aws_vpn_gateway.main[0].id
  route_table_id = module.vpc.private_route_table_ids[count.index]
}

# EKS (클라우드)
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

  nvidia_device_plugin_version = var.nvidia_device_plugin_version

  tags = local.common_tags
}

# GPU 노드 (클라우드) - 버스트 워크로드를 위한 탄력적 스케일링
# 온프레미스가 기본 처리를 담당하고, 클라우드가 피크 시 확장합니다.
module "gpu_nodes" {
  source = "../../modules/gpu-nodes"

  environment        = var.environment
  cluster_name       = module.eks.cluster_name
  vpc_id             = module.vpc.vpc_id
  vpc_cidr           = var.vpc_cidr
  private_subnet_ids = module.vpc.private_subnet_ids

  gpu_instance_types      = var.gpu_instance_types
  gpu_spot_instance_types = var.gpu_spot_instance_types

  # 하이브리드 모드에서는 클라우드 GPU 노드가 온프레미스의 오버플로우를 처리
  on_demand_desired_size = var.cloud_gpu_on_demand_desired
  on_demand_min_size     = var.cloud_gpu_on_demand_min
  on_demand_max_size     = var.cloud_gpu_on_demand_max

  # 비용 효율적인 버스트 용량을 위한 Spot 인스턴스
  spot_desired_size = var.cloud_gpu_spot_desired
  spot_min_size     = var.cloud_gpu_spot_min
  spot_max_size     = var.cloud_gpu_spot_max

  # 100GB: 루트 볼륨
  node_disk_size_gb          = 100
  enable_model_cache_volume  = true
  # 200GB: 모델 캐시 볼륨
  model_cache_volume_size_gb = 200

  tags = local.common_tags

  depends_on = [module.eks]
}

# 스토리지 (클라우드)
module "storage" {
  source = "../../modules/storage"

  environment        = var.environment
  aws_account_id     = data.aws_caller_identity.current.account_id
  vpc_id             = module.vpc.vpc_id
  vpc_cidr           = var.vpc_cidr
  private_subnet_ids = module.vpc.private_subnet_ids

  enable_efs                       = true
  cors_allowed_origins             = ["https://*.example.com"]
  efs_throughput_mode              = "provisioned"
  # 256 MiB/s: 하이브리드 환경에서 여러 GPU 노드의 동시 모델 접근에 적합한 처리량
  efs_provisioned_throughput_mibps = 256

  tags = local.common_tags
}

# 모니터링 (클라우드)
# 클라우드 Prometheus → Mimir Remote Write로 온프레미스 Mimir에 메트릭 통합
module "monitoring_cloud" {
  source = "../../modules/monitoring"

  environment = var.environment
  aws_region  = var.aws_region

  prometheus_retention    = "30d"
  prometheus_storage_size = "100Gi"

  enable_grafana         = true
  grafana_admin_password = var.grafana_admin_password
  grafana_service_type   = "LoadBalancer"

  enable_dcgm_exporter = true
  enable_gpu_alerts    = true

  enable_loki       = true
  loki_storage_type = "s3"
  loki_s3_bucket    = "${var.environment}-gwp-loki-${data.aws_caller_identity.current.account_id}"

  tags = local.common_tags

  depends_on = [module.eks]
}

# 레지스트리 (클라우드) - 컨테이너 이미지용 ECR
module "registry_cloud" {
  source = "../../modules/registry"

  environment = var.environment

  enable_ecr            = true
  ecr_max_image_count   = 50
  ecr_cross_account_ids = var.ecr_cross_account_ids

  # Harbor는 온프레미스에 배포하므로 클라우드에서는 불필요
  enable_harbor = false
  enable_nexus  = false

  tags = local.common_tags

  depends_on = [module.eks]
}

# 온프레미스 컴포넌트
# 온프레미스 프로바이더를 통해 온프레미스 Kubernetes 클러스터에 배포합니다.

# 모니터링 (온프레미스)
# 온프레미스 Prometheus → Mimir Remote Write + Grafana 통합 뷰
module "monitoring_onprem" {
  source = "../../modules/monitoring"

  providers = {
    kubernetes = kubernetes.onprem
    helm       = helm.onprem
  }

  environment = var.environment

  prometheus_retention    = "15d"
  prometheus_storage_size = "50Gi"
  storage_class_name      = var.onprem_storage_class

  enable_grafana         = true
  grafana_admin_password = var.grafana_admin_password
  grafana_service_type   = "NodePort"

  enable_dcgm_exporter = true
  enable_gpu_alerts    = true

  enable_loki       = true
  loki_storage_type = "s3"
  loki_s3_bucket    = "gwp-logs"
  tempo_s3_bucket   = "gwp-traces"
  mimir_s3_bucket   = "gwp-metrics"

  tags = local.common_tags
}

# 레지스트리 (온프레미스) - Harbor + Nexus
module "registry_onprem" {
  source = "../../modules/registry"

  providers = {
    kubernetes = kubernetes.onprem
    helm       = helm.onprem
  }

  environment = var.environment

  # 온프레미스에서는 ECR 불필요
  enable_ecr = false

  # 컨테이너 이미지용 Harbor (ECR과 양방향 복제)
  enable_harbor         = true
  harbor_external_url   = var.harbor_external_url
  harbor_admin_password = var.harbor_admin_password
  harbor_storage_size   = var.harbor_storage_size
  harbor_storage_class  = var.onprem_storage_class

  # GPU Workload Platform 패키지를 위한 Nexus
  enable_nexus        = true
  nexus_storage_size  = "100Gi"
  nexus_storage_class = var.onprem_storage_class
  nexus_service_type  = "NodePort"

  tags = local.common_tags
}
