# GPU Workload Platform - 모니터링 모듈 출력값

output "prometheus_namespace" {
  description = "Prometheus가 배포된 네임스페이스."
  value       = local.monitoring_ns
}

output "grafana_namespace" {
  description = "Grafana가 배포된 네임스페이스."
  value       = local.monitoring_ns
}

output "loki_namespace" {
  description = "Loki가 배포된 네임스페이스."
  value       = var.enable_loki ? local.loki_ns : ""
}

output "prometheus_service_name" {
  description = "Prometheus의 Kubernetes 서비스 이름."
  value       = "kube-prometheus-stack-prometheus"
}

output "grafana_service_name" {
  description = "Grafana의 Kubernetes 서비스 이름."
  value       = var.enable_grafana ? "kube-prometheus-stack-grafana" : ""
}

output "prometheus_internal_url" {
  description = "클러스터 내부에서 사용하는 Prometheus URL."
  value       = "http://kube-prometheus-stack-prometheus.${local.monitoring_ns}.svc.cluster.local:9090"
}

output "grafana_internal_url" {
  description = "클러스터 내부에서 사용하는 Grafana URL."
  value       = var.enable_grafana ? "http://kube-prometheus-stack-grafana.${local.monitoring_ns}.svc.cluster.local" : ""
}

output "loki_internal_url" {
  description = "클러스터 내부에서 사용하는 Loki URL."
  value       = var.enable_loki ? "http://loki-gateway.${local.loki_ns}.svc.cluster.local" : ""
}
