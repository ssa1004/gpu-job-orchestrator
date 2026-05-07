-- V1: jobs 테이블 초기 생성 — Job 애그리거트 본체.
-- version 컬럼은 JPA @Version 의 값. UPDATE 시 version 을 조건에 끼워 넣어 다른 트랜잭션
-- 이 먼저 바꾼 row 면 0건 update 가 나도록 만드는 패턴 (낙관적 락).
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
