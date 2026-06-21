# 스토리지 모듈 - S3 (모델/아티팩트) + EFS (GPU 노드 간 공유)
# EFS는 provisioned throughput 사용 (1~10GB 아티팩트 읽기 성능 확보)

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
    Module      = "storage"
  })
}

# S3 버킷 - 입출력 모델 및 최적화 체크포인트 저장
resource "aws_s3_bucket" "models" {
  bucket = "${local.name_prefix}-models-${var.aws_account_id}"

  # 프로덕션에서는 모델 저장소 실수 삭제 방지
  force_destroy = var.environment != "prod"

  tags = merge(local.common_tags, {
    Name    = "${local.name_prefix}-models"
    Purpose = "ai-model-storage"
  })
}

resource "aws_s3_bucket_versioning" "models" {
  bucket = aws_s3_bucket.models.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "models" {
  bucket = aws_s3_bucket.models.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = var.kms_key_arn != "" ? "aws:kms" : "AES256"
      kms_master_key_id = var.kms_key_arn != "" ? var.kms_key_arn : null
    }
    bucket_key_enabled = var.kms_key_arn != "" ? true : false
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "models" {
  bucket = aws_s3_bucket.models.id

  # 이전 모델 버전을 저비용 스토리지 계층으로 전환
  rule {
    id     = "archive-old-model-versions"
    status = "Enabled"

    filter {
      prefix = "models/"
    }

    # 30일: 최근 모델 버전은 빠른 접근이 필요하므로, 30일 후 STANDARD_IA로 전환
    noncurrent_version_transition {
      noncurrent_days = 30
      storage_class   = "STANDARD_IA"
    }

    # 90일: 3개월 이상 된 모델 버전은 거의 접근하지 않으므로 Glacier로 아카이브
    noncurrent_version_transition {
      noncurrent_days = 90
      storage_class   = "GLACIER"
    }

    # 365일: 1년 이상 된 이전 버전은 삭제하여 스토리지 비용 절감
    noncurrent_version_expiration {
      noncurrent_days = 365
    }
  }

  # 미완료 멀티파트 업로드 정리 (대용량 아티팩트에서 빈번히 발생)
  rule {
    id     = "cleanup-incomplete-uploads"
    status = "Enabled"

    filter {
      prefix = ""
    }

    # 7일: 일주일 이상 미완료 상태인 멀티파트 업로드는 실패로 간주하여 정리
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # 보관 기간 초과 최적화 체크포인트 자동 만료
  rule {
    id     = "expire-checkpoints"
    status = "Enabled"

    filter {
      prefix = "checkpoints/"
    }

    expiration {
      days = var.checkpoint_retention_days
    }
  }
}

resource "aws_s3_bucket_public_access_block" "models" {
  bucket = aws_s3_bucket.models.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 클라이언트 직접 업로드용 CORS 설정
resource "aws_s3_bucket_cors_configuration" "models" {
  bucket = aws_s3_bucket.models.id

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET", "PUT", "POST"]
    allowed_origins = var.cors_allowed_origins
    expose_headers  = ["ETag", "x-amz-request-id"]
    # 3600초(1시간): CORS preflight 캐시 시간으로, 빈번한 preflight 요청 방지
    max_age_seconds = 3600
  }
}

# S3 버킷 - 파이프라인 아티팩트 (프로파일링, 중간 표현, 벤치마크)
resource "aws_s3_bucket" "artifacts" {
  bucket        = "${local.name_prefix}-artifacts-${var.aws_account_id}"
  force_destroy = var.environment != "prod"

  tags = merge(local.common_tags, {
    Name    = "${local.name_prefix}-artifacts"
    Purpose = "pipeline-artifacts"
  })
}

resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id

  rule {
    id     = "expire-old-artifacts"
    status = "Enabled"

    filter {
      prefix = ""
    }

    expiration {
      days = var.artifact_retention_days
    }

    # 30일: 아티팩트는 초기 분석 이후 접근 빈도가 낮아지므로 STANDARD_IA로 전환
    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# S3 버킷 정책 - IRSA (IAM Roles for Service Accounts)를 통한 접근 허용
# GPU 워크로드 Pod에 모델 버킷 접근 권한 부여 (최소 권한 원칙)
resource "aws_s3_bucket_policy" "models" {
  count  = var.optimizer_role_arn != "" ? 1 : 0
  bucket = aws_s3_bucket.models.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowOptimizerReadWrite"
        Effect = "Allow"
        Principal = {
          AWS = var.optimizer_role_arn
        }
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:ListBucket",
        ]
        Resource = [
          aws_s3_bucket.models.arn,
          "${aws_s3_bucket.models.arn}/*",
        ]
      },
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.models.arn,
          "${aws_s3_bucket.models.arn}/*",
        ]
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      }
    ]
  })
}

# S3 버킷 정책 - 아티팩트 버킷 (TLS 필수 + 워크로드 접근)
resource "aws_s3_bucket_policy" "artifacts" {
  count  = var.optimizer_role_arn != "" ? 1 : 0
  bucket = aws_s3_bucket.artifacts.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowOptimizerArtifactAccess"
        Effect = "Allow"
        Principal = {
          AWS = var.optimizer_role_arn
        }
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:ListBucket",
        ]
        Resource = [
          aws_s3_bucket.artifacts.arn,
          "${aws_s3_bucket.artifacts.arn}/*",
        ]
      },
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.artifacts.arn,
          "${aws_s3_bucket.artifacts.arn}/*",
        ]
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      }
    ]
  })
}

# EFS - GPU Pod 간 공유 모델 캐시 (ReadWriteMany 접근)
resource "aws_efs_file_system" "shared_models" {
  count = var.enable_efs ? 1 : 0

  creation_token = "${local.name_prefix}-shared-models"
  encrypted      = true
  kms_key_id     = var.kms_key_arn != "" ? var.kms_key_arn : null

  performance_mode = var.efs_performance_mode
  throughput_mode  = var.efs_throughput_mode

  # 모델 로딩 시 예측 가능한 성능을 위해 provisioned throughput 설정
  provisioned_throughput_in_mibps = var.efs_throughput_mode == "provisioned" ? var.efs_provisioned_throughput_mibps : null

  # 30일: 30일간 접근하지 않은 파일을 IA 스토리지 클래스로 자동 전환하여 비용 절감
  lifecycle_policy {
    transition_to_ia = "AFTER_30_DAYS"
  }

  # 1회 접근: IA에 있는 파일이 1번이라도 접근되면 즉시 표준 스토리지로 복원
  lifecycle_policy {
    transition_to_primary_storage_class = "AFTER_1_ACCESS"
  }

  tags = merge(local.common_tags, {
    Name    = "${local.name_prefix}-shared-models"
    Purpose = "shared-model-storage"
  })
}

# EFS 마운트 타겟 - 프라이빗 서브넷(AZ)마다 하나씩 생성
resource "aws_efs_mount_target" "shared_models" {
  count = var.enable_efs ? length(var.private_subnet_ids) : 0

  file_system_id  = aws_efs_file_system.shared_models[0].id
  subnet_id       = var.private_subnet_ids[count.index]
  security_groups = [aws_security_group.efs[0].id]
}

# EFS 보안 그룹
resource "aws_security_group" "efs" {
  count = var.enable_efs ? 1 : 0

  name_prefix = "${local.name_prefix}-efs-"
  vpc_id      = var.vpc_id
  description = "Security group for EFS shared model storage"

  ingress {
    description = "Allow NFS from within the VPC"
    # 2049: NFS 프로토콜의 표준 포트
    from_port   = 2049
    to_port     = 2049
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-efs-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

# Kubernetes CSI 드라이버용 EFS Access Point
# 워크로드 Pod 전용 범위 제한 진입점 제공
resource "aws_efs_access_point" "optimizer" {
  count = var.enable_efs ? 1 : 0

  file_system_id = aws_efs_file_system.shared_models[0].id

  # UID/GID 1000: 컨테이너 내 비루트 사용자의 일반적인 기본값
  posix_user {
    gid = 1000
    uid = 1000
  }

  root_directory {
    path = "/gwp/models"
    creation_info {
      owner_gid = 1000
      owner_uid = 1000
      # 755: 소유자 전체 권한, 그룹/기타 읽기+실행 권한
      permissions = "755"
    }
  }

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-efs-optimizer-ap"
  })
}
