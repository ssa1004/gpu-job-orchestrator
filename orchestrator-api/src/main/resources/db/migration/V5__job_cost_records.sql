-- V5: Job cost records — FinOps / chargeback 기반.
-- Job 종착 시 정확히 1건 INSERT. 누구 (owner) 가 GPU 얼마나 썼고 얼마인지 영구 기록.
-- billing 시스템으로 export / 분석 / 운영 dashboard 의 입력.

CREATE TABLE job_cost_records (
    id                  UUID            PRIMARY KEY,
    job_id              UUID            NOT NULL,
    owner               VARCHAR(128)    NOT NULL,
    gpu_count           INT             NOT NULL,
    runtime_millis      BIGINT          NOT NULL,
    rate_per_gpu_hour   DECIMAL(18, 0)  NOT NULL,        -- 계산 시점 GPU-hour 단가 (KRW)
    computed_cost       DECIMAL(18, 0)  NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    final_status        VARCHAR(32)     NOT NULL,        -- SUCCEEDED / FAILED / CANCELLED / PREEMPTED 등
    job_started_at      TIMESTAMP,                       -- nullable: dispatch 실패 잡 등 RUNNING 한 적 없는 경우
    job_finished_at     TIMESTAMP       NOT NULL,
    recorded_at         TIMESTAMP       NOT NULL,
    -- 한 jobId 당 정확히 1건 — 호출 측 (lifecycle hook) 이 한 번만 호출하면 OK,
    -- 두 번 호출돼도 두 번째는 거절 (멱등성).
    CONSTRAINT uq_job_cost_job_id UNIQUE (job_id),
    CONSTRAINT chk_job_cost_runtime_nonneg CHECK (runtime_millis >= 0),
    CONSTRAINT chk_job_cost_amount_nonneg  CHECK (computed_cost >= 0)
);

-- 가장 빈번한 query — owner 별 시간 구간 집계
CREATE INDEX idx_job_cost_owner_time
    ON job_cost_records (owner, recorded_at DESC);

-- 운영 / 빌링 export — 시간 구간 전체
CREATE INDEX idx_job_cost_time
    ON job_cost_records (recorded_at DESC);
