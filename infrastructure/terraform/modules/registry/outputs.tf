# GPU Workload Platform - 레지스트리 모듈 출력값

# ECR 출력값
output "ecr_repository_urls" {
  description = "컴포넌트 이름별 ECR 저장소 URL 맵."
  value = var.enable_ecr ? {
    for key, repo in aws_ecr_repository.repos : key => repo.repository_url
  } : {}
}

output "ecr_repository_arns" {
  description = "컴포넌트 이름별 ECR 저장소 ARN 맵."
  value = var.enable_ecr ? {
    for key, repo in aws_ecr_repository.repos : key => repo.arn
  } : {}
}

output "ecr_registry_id" {
  description = "ECR 레지스트리 ID (AWS 계정 ID)."
  value       = var.enable_ecr ? values(aws_ecr_repository.repos)[0].registry_id : ""
}

# Harbor 출력값
output "harbor_namespace" {
  description = "Harbor의 Kubernetes 네임스페이스."
  value       = var.enable_harbor ? var.harbor_namespace : ""
}

output "harbor_external_url" {
  description = "Harbor의 외부 URL."
  value       = var.enable_harbor ? var.harbor_external_url : ""
}

# Nexus 출력값
output "nexus_namespace" {
  description = "Nexus의 Kubernetes 네임스페이스."
  value       = var.enable_nexus ? var.nexus_namespace : ""
}

output "nexus_service_url" {
  description = "Nexus 레지스트리의 내부 서비스 URL."
  value       = var.enable_nexus ? "http://${local.name_prefix}-nexus.${var.nexus_namespace}.svc.cluster.local:${var.nexus_port}" : ""
}

output "nexus_port" {
  description = "Nexus 서버 포트."
  value       = var.enable_nexus ? var.nexus_port : null
}

output "nexus_ingress_host" {
  description = "Nexus Ingress 호스트명 (Ingress 활성화 시)."
  value       = var.enable_nexus && var.nexus_ingress_enabled ? var.nexus_ingress_host : ""
}

output "pip_index_url" {
  description = "Nexus 의 pip index URL (사내 PyPI 패키지 설치용)."
  value       = var.enable_nexus ? "http://${local.name_prefix}-nexus.${var.nexus_namespace}.svc.cluster.local:${var.nexus_port}/root/pypi/+simple/" : ""
}

# Harbor 추가 출력값
output "harbor_ingress_host" {
  description = "Harbor Ingress 호스트명 (Ingress 활성화 시)."
  value       = var.enable_harbor && var.harbor_expose_type == "ingress" ? var.harbor_ingress_host : ""
}

# 요약 출력값 - 어떤 레지스트리가 활성화되어 있는지 확인
output "enabled_registries" {
  description = "활성화된 레지스트리 유형 목록."
  value = compact([
    var.enable_ecr ? "ecr" : "",
    var.enable_harbor ? "harbor" : "",
    var.enable_nexus ? "nexus" : "",
  ])
}
