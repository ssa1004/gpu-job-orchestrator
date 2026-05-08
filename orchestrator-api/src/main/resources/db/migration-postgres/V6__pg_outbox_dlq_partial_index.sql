-- V6 (PG only): outbox partial index 를 dead_lettered_at 까지 고려한 형태로 갱신.
-- 살아있는 메시지 = published_at IS NULL AND dead_lettered_at IS NULL.
-- 운영에서 outbox 누적 행이 수백만 건이어도 relay polling 이 빠르게 끝나도록.

DROP INDEX IF EXISTS idx_outbox_unpublished;
CREATE INDEX idx_outbox_pending ON outbox (created_at)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;
