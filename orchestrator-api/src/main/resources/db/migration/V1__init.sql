-- V1: jobs 테이블 초기 생성 — Job 애그리거트 본체.
--
-- version 컬럼은 JPA @Version (낙관적 락) 의 값.
-- 동작: UPDATE jobs SET ... , version=version+1 WHERE id=? AND version=?
--   → 다른 트랜잭션이 먼저 commit 했으면 version 이 이미 +1 되어 WHERE 가 0건 매칭
--   → JPA 가 OptimisticLockException 발생 → 호출자가 재시도 / 거절 결정.
-- "낙관적" 인 이유: 충돌이 드물 거란 가정으로 일단 commit 시도, 부딪힌 쪽만 살림.
-- (반대는 SELECT ... FOR UPDATE 같은 비관적 락 — 미리 행을 잠그고 시작.)
CREATE TABLE jobs (
    id            UUID         PRIMARY KEY,
    owner         VARCHAR(128) NOT NULL,
    input_uri     VARCHAR(1024) NOT NULL,
    result_uri    VARCHAR(1024),
    image         VARCHAR(256) NOT NULL,
    gpu_count     INT          NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    k8s_job_name  VARCHAR(256),
    trace_id      VARCHAR(64),
    error_message VARCHAR(2048),
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    version       BIGINT       NOT NULL   -- JPA @Version (낙관적 락용)
);

CREATE INDEX idx_jobs_status        ON jobs (status);
CREATE INDEX idx_jobs_owner_created ON jobs (owner, created_at);
