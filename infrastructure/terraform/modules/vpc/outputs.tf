# VPC 모듈 출력값

output "vpc_id" {
  description = "VPC ID."
  value       = aws_vpc.main.id
}

output "vpc_cidr_block" {
  description = "VPC CIDR 블록."
  value       = aws_vpc.main.cidr_block
}

output "public_subnet_ids" {
  description = "퍼블릭 서브넷 ID 목록."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "프라이빗 서브넷 ID 목록."
  value       = aws_subnet.private[*].id
}

output "public_subnet_cidr_blocks" {
  description = "퍼블릭 서브넷 CIDR 목록."
  value       = aws_subnet.public[*].cidr_block
}

output "private_subnet_cidr_blocks" {
  description = "프라이빗 서브넷 CIDR 목록."
  value       = aws_subnet.private[*].cidr_block
}

output "nat_gateway_ids" {
  description = "NAT Gateway ID 목록."
  value       = aws_nat_gateway.main[*].id
}

output "nat_gateway_public_ips" {
  description = "NAT Gateway 퍼블릭 IP (외부 허용 목록 등록용)."
  value       = aws_eip.nat[*].public_ip
}

output "internet_gateway_id" {
  description = "Internet Gateway ID."
  value       = aws_internet_gateway.main.id
}

output "vpc_endpoint_s3_id" {
  description = "S3 VPC Gateway Endpoint ID."
  value       = aws_vpc_endpoint.s3.id
}

output "private_route_table_ids" {
  description = "프라이빗 라우트 테이블 ID 목록."
  value       = aws_route_table.private[*].id
}

output "public_route_table_id" {
  description = "퍼블릭 라우트 테이블 ID."
  value       = aws_route_table.public.id
}

output "availability_zones" {
  description = "서브넷이 생성된 가용 영역 목록."
  value       = var.availability_zones
}

output "flow_log_group_name" {
  description = "VPC Flow Logs CloudWatch 로그 그룹 이름."
  value       = var.enable_flow_logs ? aws_cloudwatch_log_group.flow_logs[0].name : ""
}
