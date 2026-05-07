# Kyverno Cluster Policies

Kyverno = K8s 매니페스트로 정책을 표현하는 policy 엔진. 클러스터 전체에 적용되는
정책-as-code (정책을 코드로 관리). Pod 생성 시점에 admission webhook (Pod 가 만들어지기
직전에 K8s API 가 호출하는 검증 훅) 으로 차단하므로 "잘못된 매니페스트가 클러스터에
들어오는 것" 자체를 막습니다.

| 파일 | 강제 사항 | enforce 모드 |
|---|---|---|
| `01-require-image-signature.yaml` | Cosign 으로 서명된 이미지만 허용 (gwp-* namespace) | enforce |
| `02-disallow-privileged.yaml` | privileged Pod (호스트 권한까지 갖는 위험한 Pod) 차단 | enforce |
| `03-require-non-root.yaml` | runAsNonRoot 필수 (root 가 아닌 사용자로 실행) | enforce |
| `04-require-readonly-rootfs.yaml` | readOnlyRootFilesystem 필수 (컨테이너 루트 파일시스템을 읽기 전용으로) | audit |
| `05-require-resource-limits.yaml` | CPU / Memory limits 필수 (resource hog — 자원 독점 방지) | enforce |
| `06-disallow-host-namespaces.yaml` | hostPID / hostIPC / hostNetwork (호스트의 프로세스·메모리·네트워크 공간 공유) 차단 | enforce |
| `07-require-network-policy.yaml` | namespace 에 NetworkPolicy 1개 이상 필수 | audit |
| `08-disallow-latest-tag.yaml` | image tag `:latest` 금지 (어떤 버전이 떠 있는지 추적 가능, 재현성 확보) | enforce |

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

`audit` 모드는 위반을 보고서로만 남기고 통과시키며, `enforce` 모드는 위반을 실제로
차단합니다. 처음 도입 시 `audit` 모드로 시작해서 위반 사항 파악 후 `enforce` 로 전환.
04, 07 은 기존 워크로드 호환성 때문에 audit 으로 두었음.
