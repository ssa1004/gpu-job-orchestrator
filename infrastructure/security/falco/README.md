# Falco Runtime Security

Falco 는 Linux syscall 을 실시간으로 감시해 의심스러운 동작을 탐지합니다 (CNCF graduated
project). 본 디렉터리는 gwp 워크로드 특화 custom rules 만 포함 — Falco 자체 설치는 별도
[helm chart](https://github.com/falcosecurity/charts) 로 진행.

## 적용 방법

```bash
helm install falco falcosecurity/falco \
  --namespace monitoring --create-namespace \
  --set tty=true \
  --set "extraVolumes[0].name=custom-rules" \
  --set "extraVolumes[0].configMap.name=falco-custom-rules" \
  --set "extraVolumeMounts[0].name=custom-rules" \
  --set "extraVolumeMounts[0].mountPath=/etc/falco/rules.d"

kubectl apply -f infrastructure/security/falco/falco-rules.yaml
```

## 현재 정의된 rule

| rule | 탐지 대상 | severity |
|---|---|---|
| Unexpected Outbound Connection from orchestrator-api | 허용 안 된 외부 endpoint 호출 | WARNING |
| Worker Spawned Shell | GPU 워커가 shell 실행 (침투 의심) | CRITICAL |
| Sensitive File Access in Worker | `/etc/shadow`, kubeconfig 등 접근 | CRITICAL |
| Privilege Escalation via setuid | setuid syscall 호출 | HIGH |

## 알림 흐름

Falco → falcosidekick → Loki / Slack / PagerDuty.
falcosidekick 설정은 별도 (생략).

## 관리 원칙

- 새 rule 은 처음에 `priority: NOTICE` 로 시작해서 false positive 측정 후 격상
- 운영 환경에서 발생한 실제 alerts 는 [`docs/runbooks/`](../../../docs/runbooks/) 에 분석
  결과를 누적
