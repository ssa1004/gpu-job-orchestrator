# KubernetesAPIUnreachable

Kubernetes API 로의 dispatch 실패율이 5분 이동 평균 5% 를 초과하면 발화하는 알림입니다.
새 Job 제출이 503 으로 거절되기 시작합니다.

```promql
sum(rate(gwp_orchestrator_k8s_dispatch_total{result="error"}[5m]))
/
sum(rate(gwp_orchestrator_k8s_dispatch_total[5m])) > 0.05
```

영향 범위:

- 이미 RUNNING 중인 Job 은 영향이 없습니다 (콜백은 별도 endpoint).
- 사용자 취소 요청만 일시적으로 처리되지 않습니다. DB 상태는 CANCELLED 로 마킹되며 K8s
  측 cleanup 은 별도 GC 가 처리합니다.

## 1차 확인

orchestrator-api Pod 에서 K8s API 도달 가능 여부를 확인합니다.

```bash
kubectl -n gwp exec deploy/orchestrator-api -- \
  curl -sv https://kubernetes.default.svc/version \
    --cacert /var/run/secrets/kubernetes.io/serviceaccount/ca.crt \
    -H "Authorization: Bearer $(cat /var/run/secrets/kubernetes.io/serviceaccount/token)"
```

Control plane 자체 상태:

```bash
kubectl get --raw /readyz?verbose | head -20
```

## 원인별 대응

### A. ServiceAccount RBAC 누락

가장 흔한 원인입니다. SA 의 `batch/jobs` 생성 권한을 확인합니다.

```bash
kubectl auth can-i create jobs \
  --as=system:serviceaccount:gwp:orchestrator-api -n gwp-jobs
```

`no` 가 반환되는 경우
[serviceaccount.yaml](../../orchestrator-api/k8s/serviceaccount.yaml) 의 Role / RoleBinding
을 재적용합니다.

### B. Token Mount 실패

projected ServiceAccount token 이 Pod 시작 시점에 마운트되지 않은 경우 발생합니다.

```bash
kubectl -n gwp exec deploy/orchestrator-api -- \
  ls -la /var/run/secrets/kubernetes.io/serviceaccount/
```

디렉토리가 비어있는 경우 Pod 재시작으로 해결됩니다.

### C. Control Plane 장애

EKS / GKE 콘솔 또는 자체 클러스터 모니터링에서 control plane 상태를 확인합니다. 클라이언트
측에서 직접 조치할 수 있는 부분은 없습니다.

다행히 Resilience4j 서킷 브레이커가 동작 중이라면 사용자에게는 빠른 503 응답이 반환되어
long timeout 을 회피할 수 있습니다.

```bash
kubectl -n gwp exec deploy/orchestrator-api -- \
  curl -s localhost:8080/actuator/health/circuitBreakers | jq
```

### D. NetworkPolicy Egress 차단

Calico / Cilium 정책이 K8s API 로의 egress 를 차단하는 경우입니다.

```bash
kubectl -n gwp get networkpolicy
```

[network-policy.yaml](../../orchestrator-api/k8s/security/network-policy.yaml) 의 egress 에
`kubernetes.default.svc` 또는 control plane CIDR 가 명시적으로 허용되어 있는지 확인합니다.

## 후속 조치

- 서킷 브레이커가 closed 상태로 자동 복귀하는지 확인합니다.
- 거절된 사용자 요청은 client 측 재시도로 처리됩니다. (`Idempotency-Key` 도입 후 재시도
  안전성이 더욱 향상될 예정입니다.)
