# Chaos Mesh Experiments

Chaos Mesh (CNCF graduated) 로 운영 환경의 복원력을 정기적으로 검증합니다. 각 실험은
**가설** 을 검증하는 목적이 있어야 하며, 검증 후 결과를 기록합니다.

## 실험 목록

| 파일 | 가설 | 빈도 |
|---|---|---|
| `01-orchestrator-pod-kill.yaml` | PDB(minAvailable=1) 가 동작하여 다운타임 0 | 주 1회 |
| `02-postgres-network-delay.yaml` | API ↔ DB 사이 200ms 지연 시 p95 SLO 유지 | 월 1회 |
| `03-kafka-partition-isolated.yaml` | Kafka 일시 격리 시 Outbox 재시도 정상 동작 | 월 1회 |
| `04-worker-cpu-stress.yaml` | 워커 CPU 90% 부하에서도 콜백 재시도 성공 | 분기 1회 |
| `05-gpu-node-drain.yaml` | GPU 노드 강제 drain 시 Job 자동 재제출 | 분기 1회 |

## 운영 원칙

- **운영 환경에서 직접 실행** — staging 만으로는 진짜 검증이 안 됨
- **business hour 회피** — 새벽 시간대 (KST 03~06시) 에 실행
- **사전 공지** — Slack `#gwp-platform` 에 자동 알림
- **자동 abort 기준** — error rate > 5% 또는 p99 latency > 5s 이면 즉시 중단
- **결과 기록** — `docs/dr/chaos-results.md` 에 실험 후 매번 기록

## 실행 방법

```bash
# Chaos Mesh 설치 (최초 1회)
helm install chaos-mesh chaos-mesh/chaos-mesh \
  --namespace chaos-mesh --create-namespace

# 실험 적용
kubectl apply -f infrastructure/chaos/01-orchestrator-pod-kill.yaml

# 실시간 메트릭 확인 (다른 터미널)
watch 'kubectl get pods -n gwp; echo; \
  curl -s http://orchestrator-api.gwp:8080/actuator/health'

# 실험 종료 (자동으로 schedule 따라 끝나거나 수동 삭제)
kubectl delete -f infrastructure/chaos/01-orchestrator-pod-kill.yaml
```

## Game Day

분기별로 모든 실험을 한 번에 실행하는 game day 진행. 결과를
[`docs/dr/dr-runbook.md`](../../docs/dr/dr-runbook.md) 의 검증 결과 표에 누적.
