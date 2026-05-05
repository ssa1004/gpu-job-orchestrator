# 레지스트리 모듈 - ECR(클라우드) + Harbor(온프레미스) + Nexus(Maven/PyPI)
# 프로덕션 ECR은 immutable tag 적용, Harbor는 Helm으로 배포

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    # NOTE: enable_ecr = false 시에도 aws_ecr_* 리소스 정의가 존재하므로
    # AWS 프로바이더 선언이 필요합니다. 온프레미스 전용 환경에서는
    # skip_credentials_validation = true 로 더미 프로바이더를 설정하세요.
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = ">= 2.11"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = ">= 2.23"
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
    Module      = "registry"
  })

  # GPU Workload Platform 컴포넌트별 ECR 저장소 이름
  ecr_repos = {
    # GPU 워커 - 오케스트레이터가 K8s Job 으로 디스패치하는 GPU 컴퓨팅 컨테이너
    # 기본 이미지에 CUDA 런타임 + cuDNN 포함
    optimizer = "${local.name_prefix}/gpu-worker"

    # API 서버 - 클라이언트로부터 작업 요청을 수신
    sdk_api = "${local.name_prefix}/orchestrator-api"

    # 워커 사이드카 - 모델 I/O, 체크포인팅, 결과 수집을 담당
    worker = "${local.name_prefix}/worker"

    # GPU 기본 이미지 - CUDA/cuDNN/TensorRT 런타임의 공유 레이어
    # 다른 이미지들이 이 이미지를 FROM으로 사용하여 풀링 시간 단축
    gpu_base = "${local.name_prefix}/gpu-base"
  }
}

# ECR 저장소 (AWS 클라우드)
#
# 각 저장소는 특정 플랫폼 컴포넌트의 이미지를 저장합니다.
# 프로덕션 환경에서는 릴리스된 이미지의 실수 덮어쓰기를 방지하기 위해
# immutable tag가 적용됩니다.
resource "aws_ecr_repository" "repos" {
  for_each = var.enable_ecr ? local.ecr_repos : {}

  name                 = each.value
  image_tag_mutability = var.environment == "prod" ? "IMMUTABLE" : "MUTABLE"
  force_delete         = var.environment != "prod"

  image_scanning_configuration {
    # 푸시 시 스캔하여 GPU 런타임 의존성의 취약점 감지
    # (예: CUDA 베이스 이미지의 OpenSSL, glibc 문제)
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = var.kms_key_arn != "" ? "KMS" : "AES256"
    kms_key         = var.kms_key_arn != "" ? var.kms_key_arn : null
  }

  tags = merge(local.common_tags, {
    Name      = each.value
    Component = each.key
  })
}

# ECR lifecycle 정책 (GPU 이미지 5~15GB이므로 보관 관리 중요)
resource "aws_ecr_lifecycle_policy" "repos" {
  for_each = var.enable_ecr ? local.ecr_repos : {}

  repository = aws_ecr_repository.repos[each.key].name

  policy = jsonencode({
    rules = [
      {
        # 7일: 태그 없는 이미지(실패/중간 빌드)를 7일 후 제거
        rulePriority = 1
        description  = "태그 없는 이미지를 7일 후 제거"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = {
          type = "expire"
        }
      },
      {
        # 스토리지 비용 관리를 위해 최근 N개의 태그된 이미지만 보관
        # GPU 이미지는 개당 5~15GB이므로 보관 정책이 비용에 큰 영향
        rulePriority = 2
        description  = "최근 ${var.ecr_max_image_count}개의 태그된 이미지만 보관"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["v", "latest", "release"]
          countType     = "imageCountMoreThan"
          countNumber   = var.ecr_max_image_count
        }
        action = {
          type = "expire"
        }
      },
      {
        # 14일: 개발/피처 브랜치 이미지를 14일 후 만료
        rulePriority = 3
        description  = "개발 이미지를 14일 후 만료"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["dev-", "feature-", "pr-"]
          countType     = "sinceImagePushed"
          countUnit     = "days"
          countNumber   = 14
        }
        action = {
          type = "expire"
        }
      },
    ]
  })
}

# ECR 저장소 정책 - 크로스 계정 접근 (선택사항)
# 다른 AWS 계정(예: 공유 서비스)에서 이미지를 풀링할 수 있도록 허용합니다.
resource "aws_ecr_repository_policy" "cross_account" {
  for_each = var.enable_ecr && length(var.ecr_cross_account_ids) > 0 ? local.ecr_repos : {}

  repository = aws_ecr_repository.repos[each.key].name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowCrossAccountPull"
        Effect = "Allow"
        Principal = {
          AWS = [for id in var.ecr_cross_account_ids : "arn:aws:iam::${id}:root"]
        }
        Action = [
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "ecr:BatchCheckLayerAvailability",
        ]
      }
    ]
  })
}

# Harbor (온프레미스 레지스트리, Trivy 스캔/이미지 복제 포함)
resource "kubernetes_namespace" "harbor" {
  count = var.enable_harbor ? 1 : 0

  metadata {
    name   = var.harbor_namespace
    labels = {
      "app.kubernetes.io/managed-by" = "terraform"
      "app.kubernetes.io/part-of"    = "gwp-registry"
    }
  }
}

resource "helm_release" "harbor" {
  count = var.enable_harbor ? 1 : 0

  name             = "harbor"
  repository       = var.helm_repository_harbor
  chart            = "harbor"
  version          = var.harbor_chart_version
  namespace        = var.harbor_namespace
  create_namespace = false
  # 900초(15분): Harbor 다수 컴포넌트 설치에 충분한 타임아웃
  timeout          = 900

  # Harbor UI 및 API의 외부 URL
  set {
    name  = "externalURL"
    value = var.harbor_external_url
  }

  # Harbor 관리자 비밀번호
  set_sensitive {
    name  = "harborAdminPassword"
    value = var.harbor_admin_password
  }

  # 노출 유형 (프로덕션: ingress, 개발: nodePort)
  set {
    name  = "expose.type"
    value = var.harbor_expose_type
  }

  # TLS 설정
  dynamic "set" {
    for_each = var.harbor_expose_type == "ingress" ? [1] : []
    content {
      name  = "expose.ingress.hosts.core"
      value = var.harbor_ingress_host
    }
  }

  # 데이터베이스 - 프로덕션에서는 외부 PostgreSQL 사용
  set {
    name  = "database.type"
    value = var.harbor_database_type
  }

  # Harbor 레지스트리 스토리지 백엔드
  set {
    name  = "persistence.persistentVolumeClaim.registry.size"
    value = var.harbor_storage_size
  }

  set {
    name  = "persistence.persistentVolumeClaim.registry.storageClass"
    value = var.harbor_storage_class
  }

  # 취약점 스캔 활성화 (Trivy)
  set {
    name  = "trivy.enabled"
    value = "true"
  }

  # Harbor v2.8+ 부터 ChartMuseum은 제거됨
  # OCI 호환 레지스트리로 Helm 차트를 직접 저장 (별도 설정 불필요)

  depends_on = [kubernetes_namespace.harbor]
}

# Nexus - 프라이빗 레지스트리 (Maven 아티팩트 + PyPI 패키지 + 업스트림 캐시)
resource "kubernetes_namespace" "nexus" {
  count = var.enable_nexus ? 1 : 0

  metadata {
    name   = var.nexus_namespace
    labels = {
      "app.kubernetes.io/managed-by" = "terraform"
      "app.kubernetes.io/part-of"    = "gwp-registry"
    }
  }
}

resource "kubernetes_deployment" "nexus" {
  count = var.enable_nexus ? 1 : 0

  metadata {
    name      = "${local.name_prefix}-nexus"
    namespace = var.nexus_namespace
    labels    = {
      "app.kubernetes.io/name"       = "nexus"
      "app.kubernetes.io/instance"   = local.name_prefix
      "app.kubernetes.io/component"  = "pypi-registry"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }

  spec {
    replicas = var.nexus_replicas

    selector {
      match_labels = {
        "app.kubernetes.io/name"     = "nexus"
        "app.kubernetes.io/instance" = local.name_prefix
      }
    }

    template {
      metadata {
        labels = {
          "app.kubernetes.io/name"     = "nexus"
          "app.kubernetes.io/instance" = local.name_prefix
        }
      }

      spec {
        # Nexus3 컨테이너는 UID 200 으로 실행됨 - PVC 디렉토리 권한 보정
        security_context {
          fs_group = 200
        }

        container {
          name  = "nexus"
          image = var.nexus_image

          port {
            container_port = var.nexus_port
            name           = "nexus"
          }

          # Nexus 힙 설정 - 컨테이너 메모리 제한의 약 50%로 맞춤 (Sonatype 권장)
          env {
            name  = "INSTALL4J_ADD_VM_PARAMS"
            value = "-Xms2g -Xmx2g -XX:MaxDirectMemorySize=2g -Djava.util.prefs.userRoot=/nexus-data/javaprefs"
          }

          volume_mount {
            name       = "nexus-data"
            mount_path = "/nexus-data"
          }

          resources {
            requests = {
              cpu    = var.nexus_cpu_request
              memory = var.nexus_memory_request
            }
            limits = {
              cpu    = var.nexus_cpu_limit
              memory = var.nexus_memory_limit
            }
          }

          # Nexus3 는 시작 시 인덱스 빌드/마이그레이션으로 1~3분 소요되므로
          # 별도의 startup_probe 로 첫 기동을 보호하고 liveness 는 짧게 유지
          startup_probe {
            http_get {
              path = "/service/rest/v1/status"
              port = var.nexus_port
            }
            initial_delay_seconds = 30
            period_seconds        = 10
            failure_threshold     = 30   # 최대 5분 대기
          }

          liveness_probe {
            http_get {
              path = "/service/rest/v1/status"
              port = var.nexus_port
            }
            period_seconds = 30
          }

          readiness_probe {
            http_get {
              path = "/service/rest/v1/status"
              port = var.nexus_port
            }
            initial_delay_seconds = 10
            period_seconds        = 5
          }
        }

        volume {
          name = "nexus-data"
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim.nexus[0].metadata[0].name
          }
        }
      }
    }
  }

  depends_on = [kubernetes_namespace.nexus]
}

resource "kubernetes_persistent_volume_claim" "nexus" {
  count = var.enable_nexus ? 1 : 0

  metadata {
    name      = "${local.name_prefix}-nexus-data"
    namespace = var.nexus_namespace
  }

  spec {
    access_modes       = ["ReadWriteOnce"]
    storage_class_name = var.nexus_storage_class

    resources {
      requests = {
        storage = var.nexus_storage_size
      }
    }
  }

  depends_on = [kubernetes_namespace.nexus]
}

resource "kubernetes_service" "nexus" {
  count = var.enable_nexus ? 1 : 0

  metadata {
    name      = "${local.name_prefix}-nexus"
    namespace = var.nexus_namespace
    labels    = {
      "app.kubernetes.io/name"     = "nexus"
      "app.kubernetes.io/instance" = local.name_prefix
    }
  }

  spec {
    type = var.nexus_service_type

    selector = {
      "app.kubernetes.io/name"     = "nexus"
      "app.kubernetes.io/instance" = local.name_prefix
    }

    port {
      port        = var.nexus_port
      target_port = var.nexus_port
      name        = "nexus"
    }
  }

  depends_on = [kubernetes_namespace.nexus]
}

# Nexus Ingress (선택사항 - 외부 접근이 필요한 경우)
resource "kubernetes_ingress_v1" "nexus" {
  count = var.enable_nexus && var.nexus_ingress_enabled ? 1 : 0

  metadata {
    name      = "${local.name_prefix}-nexus"
    namespace = var.nexus_namespace
    labels = {
      "app.kubernetes.io/name"       = "nexus"
      "app.kubernetes.io/instance"   = local.name_prefix
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }

  spec {
    ingress_class_name = var.nexus_ingress_class

    rule {
      host = var.nexus_ingress_host

      http {
        path {
          path      = "/"
          path_type = "Prefix"

          backend {
            service {
              name = kubernetes_service.nexus[0].metadata[0].name
              port {
                number = var.nexus_port
              }
            }
          }
        }
      }
    }
  }

  depends_on = [kubernetes_namespace.nexus]
}
