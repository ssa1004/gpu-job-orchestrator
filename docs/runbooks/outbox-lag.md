# OutboxPublishLagging

`OutboxRelay` 가 매 polling 마다 `now() - created_at` 의 p95 를 gauge 로 노출합니다.
이 값이 5초를 5분 이상 유지하면 발화하는 알림입니다.

```promql
gwp_orchestrator_outbox_publish_lag_seconds{quantile="0.95"} > 5
```

영향: 다운스트림 컨슈머 (포인트 적립, 알림, 분석) 가 받는 이벤트가 지연됩니다. 사용자에게는
즉시 영향이 없지만 컨슈머 측에서 누적이 발생합니다.

## 1차 확인

미발행 outbox 의 누적량과 가장 오래된 미발행 row 의 경과 시간을 확인합니다.

```sql
SELECT count(*) AS pending,
       EXTRACT(EPOCH FROM now() - min(created_at)) AS oldest_seconds
FROM outbox
WHERE published_at IS NULL;
```

`OutboxRelay` 의 polling 동작 여부는 로그에서 확인할 수 있습니다.

```bash
kubectl -n gwp logs -l app.kubernetes.io/name=orchestrator-api --tail=200 \
  | grep -E "OutboxRelay|Kafka"
```

## 원인별 대응

### A. Kafka 일시 장애

가장 흔한 원인입니다. Kafka 브로커가 복구되면 `OutboxRelay` 가 자동으로 미발행 건을 따라
잡습니다. 사람이 별도로 조치할 필요가 없습니다.

### B. Poison Message (특정 row 만 반복 실패)

Poison message = 영구적으로 발행 실패하는 메시지. `OutboxRelay` 로그에서 동일한 outboxId
가 반복적으로 출력되는 경우입니다.

```sql
-- 어떤 event_type 이 막혀있는지 확인
SELECT event_type, count(*) FROM outbox
WHERE published_at IS NULL
GROUP BY event_type ORDER BY 2 DESC;
```

payload 가 schema 위반인 경우 컨슈머 측 dedup (중복 제거) 키 확인 후 해당 row 를
published 로 강제 마킹합니다. 컨슈머가 막혀있는 경우라면 DLQ (dead-letter queue —
처리 실패한 메시지를 따로 모아두는 큐) replay endpoint 를 활용합니다.

### C. OutboxRelay 자체가 동작하지 않는 경우

prod profile 에서 OutboxRelay 활성화 여부를 확인합니다.

```bash
kubectl -n gwp exec deploy/orchestrator-api -- \
  curl -s localhost:8080/actuator/configprops | jq '.contexts.application.beans.gwpProperties'
```

다중 인스턴스 환경에서는 `SKIP LOCKED` (다른 트랜잭션이 잠근 row 는 건너뛰는 PostgreSQL
기능) 또는 ShedLock (DB 행 락 등으로 한 번에 한 인스턴스만 스케줄러를 돌리도록 보장하는
라이브러리) 으로 중복 처리 방지가 적용되어 있어야 합니다.

### D. Polling 처리량 부족

`poll-interval` 단축 또는 `batch-size` 증가가 가능하지만, 일반적으로는 replica (인스턴스
복제본) 를 추가하는 편이 더 깔끔한 해결책입니다. 다중 인스턴스 시 SKIP LOCKED 보장을
함께 확인해야 합니다.

## 후속 조치

- 미발행 카운트가 0 으로 수렴하는지 확인합니다.
- p95 lag 이 5초 미만으로 회복되면 알람이 자동 해제됩니다.
- 5분 단위 대시보드에서 잔여 영향을 모니터링합니다.
