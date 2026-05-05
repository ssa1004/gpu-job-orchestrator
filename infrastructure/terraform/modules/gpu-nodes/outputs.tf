# GPU Workload Platform - GPU 노드 그룹 모듈 출력값

output "on_demand_node_group_name" {
  description = "On-Demand GPU 노드 그룹의 이름."
  value       = var.on_demand_desired_size > 0 ? aws_eks_node_group.gpu_on_demand[0].node_group_name : ""
}

output "spot_node_group_name" {
  description = "Spot GPU 노드 그룹의 이름."
  value       = var.spot_desired_size > 0 ? aws_eks_node_group.gpu_spot[0].node_group_name : ""
}

output "on_demand_node_group_arn" {
  description = "On-Demand GPU 노드 그룹의 ARN."
  value       = var.on_demand_desired_size > 0 ? aws_eks_node_group.gpu_on_demand[0].arn : ""
}

output "spot_node_group_arn" {
  description = "Spot GPU 노드 그룹의 ARN."
  value       = var.spot_desired_size > 0 ? aws_eks_node_group.gpu_spot[0].arn : ""
}

output "gpu_node_role_arn" {
  description = "GPU 워커 노드의 IAM 역할 ARN."
  value       = aws_iam_role.gpu_node.arn
}

output "gpu_node_security_group_id" {
  description = "GPU 노드 보안 그룹 ID (NCCL/EFA/메트릭)."
  value       = aws_security_group.gpu_nodes.id
}

output "launch_template_id" {
  description = "GPU 노드 Launch Template ID."
  value       = aws_launch_template.gpu_nodes.id
}

output "launch_template_latest_version" {
  description = "GPU 노드 Launch Template의 최신 버전."
  value       = aws_launch_template.gpu_nodes.latest_version
}

output "gpu_node_labels" {
  description = "GPU 노드에 적용된 Kubernetes 레이블."
  value       = local.gpu_node_labels
}
