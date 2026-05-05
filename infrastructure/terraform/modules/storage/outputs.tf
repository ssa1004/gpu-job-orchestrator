# GPU Workload Platform - 스토리지 모듈 출력값

# S3 출력값
output "models_bucket_id" {
  description = "모델 스토리지용 S3 버킷 ID."
  value       = aws_s3_bucket.models.id
}

output "models_bucket_arn" {
  description = "모델 스토리지용 S3 버킷 ARN."
  value       = aws_s3_bucket.models.arn
}

output "models_bucket_domain_name" {
  description = "모델 스토리지 버킷의 리전별 도메인 이름."
  value       = aws_s3_bucket.models.bucket_regional_domain_name
}

output "artifacts_bucket_id" {
  description = "파이프라인 아티팩트용 S3 버킷 ID."
  value       = aws_s3_bucket.artifacts.id
}

output "artifacts_bucket_arn" {
  description = "파이프라인 아티팩트용 S3 버킷 ARN."
  value       = aws_s3_bucket.artifacts.arn
}

# EFS 출력값
output "efs_file_system_id" {
  description = "공유 모델 스토리지용 EFS 파일 시스템 ID."
  value       = var.enable_efs ? aws_efs_file_system.shared_models[0].id : ""
}

output "efs_file_system_arn" {
  description = "EFS 파일 시스템 ARN."
  value       = var.enable_efs ? aws_efs_file_system.shared_models[0].arn : ""
}

output "efs_dns_name" {
  description = "EFS 파일 시스템의 DNS 이름."
  value       = var.enable_efs ? aws_efs_file_system.shared_models[0].dns_name : ""
}

output "efs_access_point_id" {
  description = "GPU 워크로드용 EFS Access Point ID."
  value       = var.enable_efs ? aws_efs_access_point.optimizer[0].id : ""
}

output "efs_security_group_id" {
  description = "EFS 마운트 타겟의 보안 그룹 ID."
  value       = var.enable_efs ? aws_security_group.efs[0].id : ""
}
