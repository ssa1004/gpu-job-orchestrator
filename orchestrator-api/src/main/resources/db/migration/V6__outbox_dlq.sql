-- V6: Outbox DLQ (Dead Letter Queue — 더 이상 처리할 수 없는 메시지를 따로 격리하는 영역).
-- poison pill (영구적으로 발행 실패하는 메시지 1건) 이 polling 큐를 막는 문제를 막기 위해
-- attempt_count 가 임계치를 넘으면 dead_lettered_at 으로 격리 → relay 가 건너뛴다.
-- 운영자가 페이로드를 보고 수동 재발행 또는 폐기.

ALTER TABLE outbox ADD COLUMN attempt_count   INT       NOT NULL DEFAULT 0;
ALTER TABLE outbox ADD COLUMN last_attempt_at TIMESTAMP;
ALTER TABLE outbox ADD COLUMN last_error      VARCHAR(2048);
ALTER TABLE outbox ADD COLUMN dead_lettered_at TIMESTAMP;

-- DLQ 운영 화면에서 "격리된 메시지 목록" 조회용 (H2/PG 공통 일반 인덱스).
-- relay 가 polling 으로 끌어가는 부분의 partial index 는 PG 전용 마이그레이션에서 갱신.
CREATE INDEX idx_outbox_dlq ON outbox (dead_lettered_at);
