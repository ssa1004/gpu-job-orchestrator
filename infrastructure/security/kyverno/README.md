# Kyverno Cluster Policies

클러스터 전체에 적용되는 정책-as-code. Pod 생성 시점에 admission webhook 으로 차단하므로
"잘못된 매니페스트가 클러스터에 들어오는 것" 자체를 막습니다.

| 파일 | 강제 사항 | enforce 모드 |
|---|---|---|
| `01-require-image-signature.yaml` | Cosign 으로 서명된 이미지만 허용 (gwp-* namespace) | enforce |
| `02-disallow-privileged.yaml` | privileged Pod 차단 | enforce |
| `03-require-non-root.yaml` | runAsNonRoot 필수 | enforce |
| `04-require-readonly-rootfs.yaml` | readOnlyRootFilesystem 필수 | audit |
| `05-require-resource-limits.yaml` | CPU/Memory limits 필수 (resource hog 방지) | enforce |
| `06-disallow-host-namespaces.yaml` | hostPID/hostIPC/hostNetwork 차단 | enforce |
| `07-require-network-policy.yaml` | namespace 에 NetworkPolicy 1개 이상 필수 | audit |
| `08-disallow-latest-tag.yaml` | image tag :latest 금지 (재현성) | enforce |

## 적용

```bash
kubectl apply -f infrastructure/security/kyverno/
```

Kyverno 자체 설치는 [Kyverno docs](https://kyverno.io/docs/installation/) 참고.

## 정책 위반 확인

```bash
kubectl get policyreport -A
kubectl get clusterpolicyreport
```

## audit vs enforce

처음 도입 시 `audit` 모드로 시작해서 위반 사항 파악 후 `enforce` 로 전환. 04, 07 은 기존
워크로드 호환성 때문에 audit 으로 두었음.
