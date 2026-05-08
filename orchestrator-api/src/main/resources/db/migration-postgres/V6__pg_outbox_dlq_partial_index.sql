-- V6 (PG only): outbox partial index 를 dead_lettered_at 까지 고려한 형태로 갱신.
--
-- 정의:  살아있는 메시지 = published_at IS NULL AND dead_lettered_at IS NULL
-- 의도:  publish 성공한 메시지 (대부분) + DLQ 로 격리된 메시지 (영원히 skip) 는 인덱스에서 제외.
--        relay 가 polling 할 때 인덱스 스캔이 *진짜 처리할 row* 만 본다.
--
-- 효과:  outbox 누적이 수백만 건 (대부분 published_at NOT NULL) 이어도 인덱스 크기는
--        미발행 + 처리 가능한 메시지 수에 비례 → polling latency 가 일정 (잡 수와 무관).
--
-- 참고 — 다중 인스턴스 안전성: ShedLock 으로 같은 시각에 한 인스턴스만 OutboxRelay.publishPending
-- 을 돌리도록 보장하므로 이 partial index 의 row 를 두 트랜잭션이 동시에 읽고 둘 다 발행하는
-- race 는 발생하지 않는다. SKIP LOCKED 까지 가지 않는 이유.

DROP INDEX IF EXISTS idx_outbox_unpublished;
CREATE INDEX idx_outbox_pending ON outbox (created_at)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;
