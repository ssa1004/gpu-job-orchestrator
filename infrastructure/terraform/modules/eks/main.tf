# EKS 모듈 - GPU 노드 그룹 + NVIDIA Device Plugin + IRSA + Cluster Autoscaler + External Secrets + Kyverno

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = ">= 2.23"
    }
    helm = {
      source  = "hashicorp/helm"
      version = ">= 2.11"
    }
    tls = {
      source  = "hashicorp/tls"
      version = ">= 4.0"
    }
  }
}

# 로컬 변수
locals {
  name_prefix  = "${var.environment}-gwp"
  cluster_name = "${local.name_prefix}-eks"

  common_tags = merge(var.tags, {
    Environment = var.environment
    Project     = "gwp"
    ManagedBy   = "terraform"
    Module      = "eks"
  })
}

# EKS 클러스터 IAM 역할
resource "aws_iam_role" "cluster" {
  name_prefix = "${local.name_prefix}-eks-cluster-"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "eks.amazonaws.com"
      }
    }]
  })

  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "cluster_policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
  role       = aws_iam_role.cluster.name
}

resource "aws_iam_role_policy_attachment" "cluster_vpc_controller" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSVPCResourceController"
  role       = aws_iam_role.cluster.name
}

# EKS 클러스터 보안 그룹
# Kubernetes API 서버 접근 및 GPU 워크로드 간 노드 통신을 제어합니다.
resource "aws_security_group" "cluster" {
  name_prefix = "${local.name_prefix}-eks-cluster-"
  vpc_id      = var.vpc_id
  description = "EKS cluster security group - controls API server access"

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-eks-cluster-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group_rule" "cluster_egress" {
  type              = "egress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.cluster.id
  description       = "Allow all outbound traffic"
}

resource "aws_security_group_rule" "cluster_api_ingress" {
  count = length(var.cluster_api_allowed_cidrs) > 0 ? 1 : 0

  type              = "ingress"
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = var.cluster_api_allowed_cidrs
  security_group_id = aws_security_group.cluster.id
  description       = "Allow HTTPS access to the Kubernetes API from allowed CIDRs"
}

# EKS 클러스터
resource "aws_eks_cluster" "main" {
  name     = local.cluster_name
  version  = var.kubernetes_version
  role_arn = aws_iam_role.cluster.arn

  vpc_config {
    subnet_ids              = concat(var.private_subnet_ids, var.public_subnet_ids)
    endpoint_private_access = true
    endpoint_public_access  = var.enable_public_endpoint
    public_access_cidrs     = var.enable_public_endpoint ? var.cluster_api_allowed_cidrs : []
    security_group_ids      = [aws_security_group.cluster.id]
  }

  # GPU 스케줄링 문제 디버깅용 컨트롤 플레인 로깅 활성화
  enabled_cluster_log_types = var.cluster_log_types

  # Kubernetes Secret 저장 시 암호화 설정
  dynamic "encryption_config" {
    for_each = var.cluster_encryption_key_arn != "" ? [1] : []
    content {
      provider {
        key_arn = var.cluster_encryption_key_arn
      }
      resources = ["secrets"]
    }
  }

  tags = merge(local.common_tags, {
    Name = local.cluster_name
  })

  timeouts {
    create = "30m"
    update = "30m"
    delete = "15m"
  }

  depends_on = [
    aws_iam_role_policy_attachment.cluster_policy,
    aws_iam_role_policy_attachment.cluster_vpc_controller,
  ]
}

# IRSA를 위한 OIDC 프로바이더
# Kubernetes Pod가 IAM 역할을 위임받을 수 있게 합니다. GPU 워크로드이
# S3 모델 스토리지와 ECR 레지스트리에 접근할 때 사용됩니다.
data "tls_certificate" "cluster" {
  url = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "cluster" {
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.cluster.certificates[0].sha1_fingerprint]
  url             = aws_eks_cluster.main.identity[0].oidc[0].issuer

  tags = local.common_tags
}

# EKS 애드온
resource "aws_eks_addon" "vpc_cni" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "vpc-cni"
  addon_version               = var.vpc_cni_version
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = local.common_tags
}

resource "aws_eks_addon" "coredns" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "coredns"
  addon_version               = var.coredns_version
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = local.common_tags
}

resource "aws_eks_addon" "kube_proxy" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "kube-proxy"
  addon_version               = var.kube_proxy_version
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = local.common_tags
}

# NVIDIA Device Plugin - nvidia.com/gpu 리소스를 Kubernetes 스케줄러에 노출
resource "helm_release" "nvidia_device_plugin" {
  name             = "nvidia-device-plugin"
  repository       = var.helm_repository_nvidia
  chart            = "nvidia-device-plugin"
  version          = var.nvidia_device_plugin_version
  namespace        = "kube-system"
  create_namespace = false

  # GPU 노드에서만 실행 (레이블 기반 매칭)
  set {
    name  = "affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms[0].matchExpressions[0].key"
    value = "nvidia.com/gpu.present"
  }
  set {
    name  = "affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms[0].matchExpressions[0].operator"
    value = "In"
  }
  set {
    name  = "affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms[0].matchExpressions[0].values[0]"
    value = "true"
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

  # GPU Feature Discovery 활성화
  set {
    name  = "gfd.enabled"
    value = "true"
  }

  depends_on = [aws_eks_cluster.main]
}

# Cluster Autoscaler IAM 역할 (IRSA)
# 대기 중인 GPU 워크로드에 따라 GPU 노드 그룹 동적 스케일링
resource "aws_iam_role" "cluster_autoscaler" {
  count       = var.enable_cluster_autoscaler ? 1 : 0
  name_prefix = "${local.name_prefix}-autoscaler-"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRoleWithWebIdentity"
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.cluster.arn
      }
      Condition = {
        StringEquals = {
          "${replace(aws_eks_cluster.main.identity[0].oidc[0].issuer, "https://", "")}:sub" = "system:serviceaccount:kube-system:cluster-autoscaler"
          "${replace(aws_eks_cluster.main.identity[0].oidc[0].issuer, "https://", "")}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })

  tags = local.common_tags
}

resource "aws_iam_role_policy" "cluster_autoscaler" {
  count       = var.enable_cluster_autoscaler ? 1 : 0
  name_prefix = "${local.name_prefix}-autoscaler-"
  role        = aws_iam_role.cluster_autoscaler[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = [
          "autoscaling:DescribeAutoScalingGroups",
          "autoscaling:DescribeAutoScalingInstances",
          "autoscaling:DescribeLaunchConfigurations",
          "autoscaling:DescribeScalingActivities",
          "autoscaling:DescribeTags",
          "ec2:DescribeImages",
          "ec2:DescribeInstanceTypes",
          "ec2:DescribeLaunchTemplateVersions",
          "ec2:GetInstanceTypesFromInstanceRequirements",
          "eks:DescribeNodegroup",
        ]
        Effect   = "Allow"
        Resource = "*"
      },
      {
        Action = [
          "autoscaling:SetDesiredCapacity",
          "autoscaling:TerminateInstanceInAutoScalingGroup",
        ]
        Effect   = "Allow"
        Resource = "*"
        Condition = {
          StringEquals = {
            "autoscaling:ResourceTag/kubernetes.io/cluster/${local.cluster_name}" = "owned"
          }
        }
      },
    ]
  })
}

# Cluster Autoscaler Helm 릴리스
resource "helm_release" "cluster_autoscaler" {
  count = var.enable_cluster_autoscaler ? 1 : 0

  name             = "cluster-autoscaler"
  repository       = var.helm_repository_autoscaler
  chart            = "cluster-autoscaler"
  version          = var.cluster_autoscaler_version
  namespace        = "kube-system"
  create_namespace = false

  set {
    name  = "autoDiscovery.clusterName"
    value = local.cluster_name
  }

  set {
    name  = "awsRegion"
    value = var.aws_region
  }

  set {
    name  = "rbac.serviceAccount.annotations.eks\\.amazonaws\\.com/role-arn"
    value = aws_iam_role.cluster_autoscaler[0].arn
  }

  # GPU 전용 오토스케일러 설정:
  # - Expander: 우선순위 기반으로 Spot을 On-Demand보다 선호
  # - 스케일 다운 지연: GPU 초기화 시간을 고려하여 노드 thrashing 방지
  set {
    name  = "extraArgs.expander"
    value = "priority"
  }

  # 10분: GPU 노드 추가 후 바로 축소되는 것을 방지 (GPU 드라이버 초기화에 시간 소요)
  set {
    name  = "extraArgs.scale-down-delay-after-add"
    value = "10m"
  }

  # 10분: 유휴 상태 유지 시간 - GPU 워크로드의 간헐적 특성을 고려한 설정
  set {
    name  = "extraArgs.scale-down-unneeded-time"
    value = "10m"
  }

  # 로컬 스토리지가 있는 노드 축소 방지 (캐시된 모델 보존)
  set {
    name  = "extraArgs.skip-nodes-with-local-storage"
    value = "true"
  }

  depends_on = [aws_eks_cluster.main]
}

# External Secrets Operator - 시크릿 자동 동기화
# Secrets Manager(클라우드) / Vault(온프레미스)의 시크릿을 K8s Secret으로 동기화
resource "helm_release" "external_secrets" {
  count = var.enable_external_secrets ? 1 : 0

  name             = "external-secrets"
  repository       = var.helm_repository_external_secrets
  chart            = "external-secrets"
  version          = var.external_secrets_version
  namespace        = "external-secrets"
  create_namespace = true

  depends_on = [aws_eks_cluster.main]
}

# Kyverno - K8s 정책 엔진
# GPU 리소스 미지정 Pod 차단, 미서명 이미지 배포 차단
resource "helm_release" "kyverno" {
  count = var.enable_kyverno ? 1 : 0

  name             = "kyverno"
  repository       = var.helm_repository_kyverno
  chart            = "kyverno"
  version          = var.kyverno_version
  namespace        = "kyverno"
  create_namespace = true

  depends_on = [aws_eks_cluster.main]
}
