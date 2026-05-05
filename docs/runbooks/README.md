# Runbooks

장애 발생 시 빠른 대응을 위한 문서들입니다. PrometheusRule 의 `runbook_url` annotation 이
이 디렉토리의 개별 파일을 직접 가리킵니다.

| 파일 | 알림 | 발화 조건 |
|---|---|---|
| [error-rate-high.md](error-rate-high.md) | `OrchestratorErrorRateHigh` | 5xx 비율이 1% 초과 |
| [outbox-lag.md](outbox-lag.md) | `OutboxPublishLagging` | 이벤트 발행이 5초 이상 지연 |
| [callback-missing.md](callback-missing.md) | `JobCallbackMissing` | RUNNING 상태로 30분 이상 머무는 Job 누적 |
| [k8s-api-unreachable.md](k8s-api-unreachable.md) | `KubernetesAPIUnreachable` | Job 디스패치 실패율 5% 초과 |
| [gpu-oom.md](gpu-oom.md) | (대시보드 기반) | Job 실패율 폭증, GPU OOM 또는 quota 우회 의심 |

## 새 Runbook 작성 가이드

- 파일명은 알림 이름의 kebab-case 로 작성합니다.
- 본문 구성은 "증상 → 1차 확인 명령 → 원인별 대응" 순서가 일반적이지만, 알림 성격에 따라
  자유롭게 조정해도 무방합니다.
- 운영 중 새로 발견된 케이스나 막혔던 부분이 있다면 다음 사람을 위해 추가로 기록합니다.
