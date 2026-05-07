# Falco Runtime Security

Falco 는 Linux syscall (시스템 호출 — 프로세스가 OS 커널에 요청하는 동작) 을 실시간으로
감시해 의심스러운 동작 (예: shell 실행, 민감 파일 접근) 을 탐지합니다 (CNCF graduated —
Cloud Native Computing Foundation 의 graduated 등급 프로젝트). 본 디렉터리는 gwp 워크로드
특화 custom rules 만 포함 — Falco 자체 설치는 별도
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
| Privilege Escalation via setuid | setuid (사용자 권한 변경) syscall 호출 | HIGH |

## 알림 흐름

Falco → falcosidekick (Falco 알림을 외부 시스템으로 전달하는 사이드카) → Loki / Slack /
PagerDuty. falcosidekick 설정은 별도 (생략).

## 관리 원칙

- 새 rule 은 처음에 `priority: NOTICE` 로 시작해서 false positive (정상 동작인데 의심으로
  잡히는 오탐) 측정 후 격상
- 운영 환경에서 발생한 실제 alerts 는 [`docs/runbooks/`](../../../docs/runbooks/) 에 분석
  결과를 누적
