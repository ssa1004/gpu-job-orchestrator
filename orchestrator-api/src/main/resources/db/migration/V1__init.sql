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
    version       BIGINT       NOT NULL
);

CREATE INDEX idx_jobs_status        ON jobs (status);
CREATE INDEX idx_jobs_owner_created ON jobs (owner, created_at);
