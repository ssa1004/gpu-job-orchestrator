# VPC 모듈 - GPU Workload Platform GPU 워크로드용 네트워크 구성

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

locals {
  name_prefix = "${var.environment}-gwp"

  common_tags = merge(var.tags, {
    Environment = var.environment
    Project     = "gwp"
    ManagedBy   = "terraform"
    Module      = "vpc"
  })

  max_azs             = length(var.availability_zones)
  public_subnet_bits  = 4 # /20
  private_subnet_bits = 2 # /18 - GPU 오토스케일링을 위한 넉넉한 IP 공간
}

# VPC
resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true # EKS 서비스 디스커버리에 필수

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-vpc"
  })
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-igw"
  })
}

# 퍼블릭 서브넷
resource "aws_subnet" "public" {
  count = local.max_azs

  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, local.public_subnet_bits, count.index)
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = true

  tags = merge(local.common_tags, {
    Name                                             = "${local.name_prefix}-public-${var.availability_zones[count.index]}"
    "kubernetes.io/role/elb"                         = "1"
    "kubernetes.io/cluster/${local.name_prefix}-eks" = "shared"
    Tier                                             = "public"
  })
}

# 프라이빗 서브넷
resource "aws_subnet" "private" {
  count = local.max_azs

  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, local.private_subnet_bits, count.index + local.max_azs)
  availability_zone = var.availability_zones[count.index]

  tags = merge(local.common_tags, {
    Name                                             = "${local.name_prefix}-private-${var.availability_zones[count.index]}"
    "kubernetes.io/role/internal-elb"                = "1"
    "kubernetes.io/cluster/${local.name_prefix}-eks" = "shared"
    Tier                                             = "private"
  })
}

# NAT Gateway EIP
resource "aws_eip" "nat" {
  count  = var.single_nat_gateway ? 1 : local.max_azs
  domain = "vpc"

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-nat-eip-${count.index}"
  })

  depends_on = [aws_internet_gateway.main]
}

# NAT Gateway
# single_nat_gateway = false이면 AZ별로 NAT Gateway를 생성하여 고가용성 확보
resource "aws_nat_gateway" "main" {
  count = var.single_nat_gateway ? 1 : local.max_azs

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-nat-${count.index}"
  })

  depends_on = [aws_internet_gateway.main]
}

# 라우트 테이블

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-public-rt"
  })
}

resource "aws_route_table_association" "public" {
  count = local.max_azs

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  count  = var.single_nat_gateway ? 1 : local.max_azs
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[count.index].id
  }

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-private-rt-${count.index}"
  })
}

resource "aws_route_table_association" "private" {
  count = local.max_azs

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[var.single_nat_gateway ? 0 : count.index].id
}

# VPC Endpoint - NAT 비용 절감 (아티팩트 파일이 수 GB 단위라 효과 큼)

resource "aws_vpc_endpoint" "s3" {
  vpc_id       = aws_vpc.main.id
  service_name = "com.amazonaws.${var.aws_region}.s3"

  route_table_ids = aws_route_table.private[*].id

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-s3-endpoint"
  })
}

resource "aws_vpc_endpoint" "ecr_dkr" {
  count = var.enable_vpc_endpoints ? 1 : 0

  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.ecr.dkr"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.vpc_endpoints[0].id]
  private_dns_enabled = true

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-ecr-dkr-endpoint"
  })
}

resource "aws_vpc_endpoint" "ecr_api" {
  count = var.enable_vpc_endpoints ? 1 : 0

  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.ecr.api"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.vpc_endpoints[0].id]
  private_dns_enabled = true

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-ecr-api-endpoint"
  })
}

# NOTE: IRSA (IAM Roles for Service Accounts) 사용 시 STS VPC Endpoint 필수
resource "aws_vpc_endpoint" "sts" {
  count = var.enable_vpc_endpoints ? 1 : 0

  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.sts"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.vpc_endpoints[0].id]
  private_dns_enabled = true

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-sts-endpoint"
  })
}

# VPC Endpoint 보안 그룹
resource "aws_security_group" "vpc_endpoints" {
  count = var.enable_vpc_endpoints ? 1 : 0

  name_prefix = "${local.name_prefix}-vpc-endpoints-"
  vpc_id      = aws_vpc.main.id
  description = "Security group for VPC interface endpoints"

  ingress {
    description = "Allow HTTPS from within the VPC"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-vpc-endpoints-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

# VPC Flow Logs
resource "aws_flow_log" "main" {
  count = var.enable_flow_logs ? 1 : 0

  vpc_id                   = aws_vpc.main.id
  traffic_type             = "ALL"
  log_destination_type     = "cloud-watch-logs"
  log_destination          = aws_cloudwatch_log_group.flow_logs[0].arn
  iam_role_arn             = aws_iam_role.flow_logs[0].arn
  max_aggregation_interval = 60 # 60초: 최소 집계 간격

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-flow-logs"
  })
}

resource "aws_cloudwatch_log_group" "flow_logs" {
  count = var.enable_flow_logs ? 1 : 0

  name              = "/aws/vpc/${local.name_prefix}-flow-logs"
  retention_in_days = var.flow_log_retention_days

  tags = local.common_tags
}

resource "aws_iam_role" "flow_logs" {
  count = var.enable_flow_logs ? 1 : 0

  name_prefix = "${local.name_prefix}-flow-logs-"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "vpc-flow-logs.amazonaws.com"
      }
    }]
  })

  tags = local.common_tags
}

resource "aws_iam_role_policy" "flow_logs" {
  count = var.enable_flow_logs ? 1 : 0

  name_prefix = "${local.name_prefix}-flow-logs-"
  role        = aws_iam_role.flow_logs[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogGroups",
        "logs:DescribeLogStreams",
      ]
      Effect   = "Allow"
      Resource = "${aws_cloudwatch_log_group.flow_logs[0].arn}:*"
    }]
  })
}
