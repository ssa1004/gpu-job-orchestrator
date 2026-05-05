-- V3 (PostgreSQL only): partial index 로 active job / unpublished outbox 만 인덱싱.
-- H2 는 partial index 미지원이라 default 프로필에서는 적용 X.
-- application-prod.yml 에서 spring.flyway.locations 에 이 폴더를 추가하여 prod 만 적용.

-- 사용 시나리오: 운영 환경에서 jobs 테이블이 수십만 건 누적 + 대부분 SUCCEEDED/FAILED 일 때,
-- 디스패처/대시보드가 "현재 active 상태인 Job" 을 빈번히 조회. partial index 로 디스크 90%+ 절약.
DROP INDEX IF EXISTS idx_jobs_dispatch_order;
CREATE INDEX idx_jobs_dispatch_order_active ON jobs (priority, created_at)
    WHERE status IN ('QUEUED', 'DISPATCHING', 'RUNNING');

-- 운영 환경에서 outbox 는 published_at 기준 99% 이상이 NOT NULL (이미 발행됨).
-- relay polling 이 미발행 건만 빠르게 스캔하도록 partial index.
DROP INDEX IF EXISTS idx_outbox_unpublished;
CREATE INDEX idx_outbox_unpublished ON outbox (created_at)
    WHERE published_at IS NULL;

-- job_events 의 payload 가 JSONB 라면 GIN 인덱스로 임의 키 검색 가능 (현재 schema 는 CLOB).
-- 운영 시 job_events.payload 를 JSONB 로 마이그레이션할 때 사용:
-- ALTER TABLE job_events ALTER COLUMN payload TYPE JSONB USING payload::JSONB;
-- CREATE INDEX idx_job_events_payload_gin ON job_events USING GIN (payload);
