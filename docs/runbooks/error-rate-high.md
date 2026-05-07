# OrchestratorErrorRateHigh

5xx 응답 비율이 5분 이동 평균 1% 를 초과할 때 발화하는 알림입니다. SLO 99.9% 가용성 기준
보다 보수적인 임계치를 사용합니다.

```promql
sum(rate(http_server_requests_seconds_count{job="orchestrator-api", status=~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count{job="orchestrator-api"}[5m])) > 0.01
```

## 1차 확인 (5분 이내)

```bash
kubectl -n gwp get pods -l app.kubernetes.io/name=orchestrator-api
kubectl -n gwp logs -l app.kubernetes.io/name=orchestrator-api --tail=200 | grep -E "ERROR|Exception"
```

Grafana 의 `Orchestrator Overview` 대시보드에서 "5xx by endpoint" 패널을 확인하면 어떤
endpoint 에서 실패가 발생하는지 즉시 파악할 수 있습니다.

## 원인별 대응

### A. HikariCP ConnectionTimeout 다발

HikariCP (Spring 의 기본 DB 커넥션 풀) 가 DB 커넥션을 끝내 못 받아 timeout 이 잦은 경우.
PostgreSQL 의 가용성과 연결 풀 상태를 먼저 확인합니다.

```bash
kubectl -n gwp logs -l app=postgres --tail=50
kubectl -n gwp exec deploy/postgres -- \
  psql -U orchestrator -c "SELECT count(*) FROM pg_stat_activity WHERE state='active';"
```

`max_connections` 가 부족한 경우 `pg_stat_activity` 에서 hang (멈춤) 된 쿼리를 찾을 수
있습니다. 긴급 조치로 `pg_cancel_backend(pid)` (해당 백엔드 세션을 강제 취소) 를 사용한
뒤, HikariCP `maximum-pool-size` 를 일시적으로 축소하거나 PostgreSQL 측 max 값을 상향
조정합니다.

### B. KubernetesClientException

K8s API 측 문제이므로 [k8s-api-unreachable.md](k8s-api-unreachable.md) 의 절차로 진행합니다.

### C. OptimisticLockException 폭증

OptimisticLockException = 같은 row 에 동시에 두 트랜잭션이 변경을 시도해서 한쪽이
거절되는 경우. 특정 jobId 에 워커 콜백이 중복으로 들어오는 경우 자주 발생합니다.
Loki (로그 저장소) 에서 패턴을 확인할 수 있습니다.

```bash
logcli query '{app="orchestrator-api"} |= "OptimisticLockException"' --limit=200 \
  | grep -oE 'jobId=[a-f0-9-]+' | sort | uniq -c | sort -rn | head
```

특정 jobId 에 카운트가 집중된다면 워커 측 retry 로직을 점검합니다.

### D. Outbox 발행 트랜잭션 실패

`outbox` 테이블 (DB 안의 발신함 — 도메인 변경과 같은 트랜잭션에 이벤트를 INSERT 해 두면
별도 relay 가 polling 으로 Kafka 발행) 의 미발행 행 수가 급증하는 경우
[outbox-lag.md](outbox-lag.md) 절차로 이동합니다.

## 후속 조치

- Error budget (목표 미달이 허용되는 여유분) 영향을 [SLO 문서](../slo.md) 기준으로 기록합니다.
- 동일한 원인으로 두 번 연속 알람이 발생하는 경우 코드 수정 또는 ADR 작성이 필요합니다.
