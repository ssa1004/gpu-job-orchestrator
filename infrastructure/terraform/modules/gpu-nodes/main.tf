# GPU 노드 그룹 - EKS 관리형 On-Demand/Spot 혼합
# GPU taint로 비 GPU 워크로드 격리, EKS GPU AMI 기본값 (NVIDIA 530.x+/CUDA 12.1)

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

# 로컬 변수
locals {
  name_prefix = "${var.environment}-gwp"

  common_tags = merge(var.tags, {
    Environment = var.environment
    Project     = "gwp"
    ManagedBy   = "terraform"
    Module      = "gpu-nodes"
  })

  # 커스텀 레이블과 필수 GPU 레이블 병합
  gpu_node_labels = merge(var.additional_node_labels, {
    "nvidia.com/gpu.present" = "true"
    "example.com/role"     = "gpu-worker"
    "example.com/workload" = "optimizer"
  })
}

# GPU 노드 그룹 IAM 역할
resource "aws_iam_role" "gpu_node" {
  name_prefix = "${local.name_prefix}-gpu-node-"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
    }]
  })

  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "gpu_node_worker" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
  role       = aws_iam_role.gpu_node.name
}

resource "aws_iam_role_policy_attachment" "gpu_node_cni" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
  role       = aws_iam_role.gpu_node.name
}

resource "aws_iam_role_policy_attachment" "gpu_node_ecr" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
  role       = aws_iam_role.gpu_node.name
}

# SSM 접근 허용 - GPU 드라이버 문제 디버깅 시 활용
resource "aws_iam_role_policy_attachment" "gpu_node_ssm" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
  role       = aws_iam_role.gpu_node.name
}

# GPU 노드 Launch Template (AMI, 디스크, User Data 설정)
resource "aws_launch_template" "gpu_nodes" {
  name_prefix = "${local.name_prefix}-gpu-"

  # 커스텀 AMI 미지정 시 EKS 최적화 GPU AMI 자동 선택
  image_id = var.custom_ami_id != "" ? var.custom_ami_id : null

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_size           = var.node_disk_size_gb
      volume_type           = "gp3"
      # 250 MiB/s: GPU 워크로드의 모델 로딩에 적합한 기본 처리량
      throughput            = 250
      # 3000 IOPS: gp3 기본값으로, 일반적인 모델 I/O에 충분
      iops                  = 3000
      encrypted             = true
      delete_on_termination = true
    }
  }

  # GPU 워크로드는 모델 캐싱을 위해 높은 처리량의 로컬 스토리지가 필요합니다.
  # 이 추가 볼륨은 User Data 스크립트에 의해 /mnt/models에 마운트됩니다.
  dynamic "block_device_mappings" {
    for_each = var.enable_model_cache_volume ? [1] : []
    content {
      device_name = "/dev/xvdb"
      ebs {
        volume_size           = var.model_cache_volume_size_gb
        volume_type           = "gp3"
        # 500 MiB/s: 대용량 아티팩트 읽기에 최적화된 높은 처리량
        throughput            = 500
        # 6000 IOPS: 여러 아티팩트 파일의 동시 읽기를 지원하기 위한 높은 IOPS
        iops                  = 6000
        encrypted             = true
        delete_on_termination = true
      }
    }
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required" # 보안 강화를 위해 IMDSv2 필수
    http_put_response_hop_limit = 2          # EKS 컨테이너에서 필요한 홉 수
  }

  monitoring {
    enabled = true
  }

  tag_specifications {
    resource_type = "instance"
    tags          = merge(local.common_tags, {
      Name = "${local.name_prefix}-gpu-node"
    })
  }

  tag_specifications {
    resource_type = "volume"
    tags          = merge(local.common_tags, {
      Name = "${local.name_prefix}-gpu-node-volume"
    })
  }

  # GPU 노드 초기화를 위한 User Data:
  # - 모델 캐시 볼륨 마운트 (활성화된 경우)
  # - NVIDIA 드라이버 로딩 확인
  # - GPU 워크로드 컨테이너 이미지 사전 풀링 (선택)
  user_data = base64encode(templatefile("${path.module}/templates/gpu-userdata.sh.tpl", {
    cluster_name              = var.cluster_name
    enable_model_cache_volume = var.enable_model_cache_volume
    model_cache_mount_path    = "/mnt/models"
  }))

  lifecycle {
    create_before_destroy = true
  }

  tags = local.common_tags
}

# On-Demand GPU 노드 그룹 - SLA 보장이 필요한 프로덕션 워크로드용
resource "aws_eks_node_group" "gpu_on_demand" {
  count = var.on_demand_desired_size > 0 ? 1 : 0

  cluster_name    = var.cluster_name
  node_group_name = "${local.name_prefix}-gpu-on-demand"
  node_role_arn   = aws_iam_role.gpu_node.arn
  subnet_ids      = var.private_subnet_ids

  capacity_type  = "ON_DEMAND"
  instance_types = var.gpu_instance_types
  ami_type       = var.custom_ami_id != "" ? "CUSTOM" : "AL2_x86_64_GPU"

  scaling_config {
    desired_size = var.on_demand_desired_size
    min_size     = var.on_demand_min_size
    max_size     = var.on_demand_max_size
  }

  update_config {
    # 25%: 업데이트 시 전체 GPU 용량의 3/4를 유지하여 서비스 영향 최소화
    max_unavailable_percentage = 25
  }

  launch_template {
    id      = aws_launch_template.gpu_nodes.id
    version = aws_launch_template.gpu_nodes.latest_version
  }

  labels = merge(local.gpu_node_labels, {
    "example.com/capacity-type" = "on-demand"
  })

  # GPU 노드에 taint를 설정하여 비 GPU 워크로드가 스케줄링되지 않도록 합니다.
  # GPU 워크로드 Pod에 매칭되는 toleration이 포함되어야 합니다.
  taint {
    key    = "nvidia.com/gpu"
    value  = "true"
    effect = "NO_SCHEDULE"
  }

  tags = merge(local.common_tags, {
    "k8s.io/cluster-autoscaler/enabled"             = "true"
    "k8s.io/cluster-autoscaler/${var.cluster_name}"  = "owned"
    "k8s.io/cluster-autoscaler/node-template/label/nvidia.com/gpu.present" = "true"
  })

  depends_on = [
    aws_iam_role_policy_attachment.gpu_node_worker,
    aws_iam_role_policy_attachment.gpu_node_cni,
    aws_iam_role_policy_attachment.gpu_node_ecr,
  ]

  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }
}

# Spot GPU 노드 그룹 - SLA 불필요한 배치 워크로드용 (60~90% 비용 절감, 체크포인팅 필요)
resource "aws_eks_node_group" "gpu_spot" {
  count = var.spot_desired_size > 0 ? 1 : 0

  cluster_name    = var.cluster_name
  node_group_name = "${local.name_prefix}-gpu-spot"
  node_role_arn   = aws_iam_role.gpu_node.arn
  subnet_ids      = var.private_subnet_ids

  capacity_type  = "SPOT"
  instance_types = var.gpu_spot_instance_types
  ami_type       = var.custom_ami_id != "" ? "CUSTOM" : "AL2_x86_64_GPU"

  scaling_config {
    desired_size = var.spot_desired_size
    min_size     = var.spot_min_size
    max_size     = var.spot_max_size
  }

  update_config {
    # 50%: Spot은 이미 중단 가능하므로, On-Demand(25%)보다 높은 비율 허용
    max_unavailable_percentage = 50
  }

  launch_template {
    id      = aws_launch_template.gpu_nodes.id
    version = aws_launch_template.gpu_nodes.latest_version
  }

  labels = merge(local.gpu_node_labels, {
    "example.com/capacity-type" = "spot"
  })

  taint {
    key    = "nvidia.com/gpu"
    value  = "true"
    effect = "NO_SCHEDULE"
  }

  # Spot 인스턴스 추가 taint - 워크로드가 Spot 사용을 명시적으로 허용해야 함
  taint {
    key    = "example.com/spot"
    value  = "true"
    effect = "PREFER_NO_SCHEDULE"
  }

  tags = merge(local.common_tags, {
    "k8s.io/cluster-autoscaler/enabled"            = "true"
    "k8s.io/cluster-autoscaler/${var.cluster_name}" = "owned"
    "k8s.io/cluster-autoscaler/node-template/label/nvidia.com/gpu.present" = "true"
  })

  depends_on = [
    aws_iam_role_policy_attachment.gpu_node_worker,
    aws_iam_role_policy_attachment.gpu_node_cni,
    aws_iam_role_policy_attachment.gpu_node_ecr,
  ]

  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }
}

# GPU 노드 보안 그룹 (NCCL/EFA 통신, Prometheus 메트릭 수집)
resource "aws_security_group" "gpu_nodes" {
  name_prefix = "${local.name_prefix}-gpu-nodes-"
  vpc_id      = var.vpc_id
  description = "GPU 워커 노드용 추가 보안 그룹"

  # GPU 노드 간 NCCL 통신 (멀티 노드 GPU 워크로드용)
  ingress {
    description = "NCCL 노드 간 GPU 통신"
    # 29400-29500: NCCL이 멀티 노드 GPU 통신에 사용하는 기본 포트 범위
    from_port   = 29400
    to_port     = 29500
    protocol    = "tcp"
    self        = true
  }

  # 고대역폭 GPU 통신을 위한 EFA 트래픽 (p3dn, p4d 인스턴스)
  ingress {
    description = "멀티 노드 GPU 워크로드를 위한 EFA 트래픽"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }

  # Prometheus 노드 익스포터 메트릭
  ingress {
    description = "Prometheus GPU 메트릭 (DCGM Exporter)"
    # 9400: DCGM Exporter의 기본 메트릭 노출 포트
    from_port   = 9400
    to_port     = 9400
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "모든 아웃바운드 트래픽 허용"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-gpu-nodes-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

# NOTE: GPU 노드 User Data 템플릿은 templates/gpu-userdata.sh.tpl 에 정의되어 있습니다.
# aws_launch_template.gpu_nodes의 user_data에서 templatefile()로 참조합니다.
