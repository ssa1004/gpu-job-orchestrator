# GPU Workload Platform - EKS 모듈 출력값

output "cluster_id" {
  description = "EKS 클러스터 ID."
  value       = aws_eks_cluster.main.id
}

output "cluster_name" {
  description = "EKS 클러스터 이름."
  value       = aws_eks_cluster.main.name
}

output "cluster_arn" {
  description = "EKS 클러스터의 ARN."
  value       = aws_eks_cluster.main.arn
}

output "cluster_endpoint" {
  description = "EKS API 서버의 엔드포인트 URL."
  value       = aws_eks_cluster.main.endpoint
}

output "cluster_certificate_authority_data" {
  description = "클러스터 CA 인증서의 Base64 인코딩 데이터."
  value       = aws_eks_cluster.main.certificate_authority[0].data
}

output "cluster_security_group_id" {
  description = "EKS 클러스터에 연결된 보안 그룹 ID."
  value       = aws_security_group.cluster.id
}

output "cluster_oidc_issuer_url" {
  description = "EKS 클러스터의 OIDC 발급자 URL. IRSA (IAM Roles for Service Accounts) 설정에 사용."
  value       = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

output "oidc_provider_arn" {
  description = "IRSA용 OIDC 프로바이더 ARN."
  value       = aws_iam_openid_connect_provider.cluster.arn
}

output "cluster_version" {
  description = "클러스터에서 실행 중인 Kubernetes 버전."
  value       = aws_eks_cluster.main.version
}

output "cluster_primary_security_group_id" {
  description = "EKS에서 자동 생성한 클러스터 Primary 보안 그룹 ID. 노드-to-컨트롤플레인 통신에 사용."
  value       = aws_eks_cluster.main.vpc_config[0].cluster_security_group_id
}

output "cluster_platform_version" {
  description = "EKS 플랫폼 버전 (디버깅 및 지원 요청 시 유용)."
  value       = aws_eks_cluster.main.platform_version
}

output "oidc_provider_url" {
  description = "OIDC 프로바이더 URL (https:// 제거). IRSA IAM 조건 키에 사용."
  value       = replace(aws_eks_cluster.main.identity[0].oidc[0].issuer, "https://", "")
}

output "cluster_autoscaler_role_arn" {
  description = "Cluster Autoscaler의 IAM 역할 ARN."
  value       = var.enable_cluster_autoscaler ? aws_iam_role.cluster_autoscaler[0].arn : ""
}

output "node_security_group_id" {
  description = "EKS 노드 보안 그룹 ID (워커 노드 통신용)."
  value       = aws_security_group.cluster.id
}
