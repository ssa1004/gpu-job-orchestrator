-- V4: Job dependencies (DAG) — Job B 가 Job A 완료 후에만 시작.
-- ML 파이프라인 / 분산 학습 / 데이터 전처리→학습 fan-out 등에 필요.
-- Airflow / Argo Workflows / Kubeflow 의 task dependency 와 같은 컨셉.

CREATE TABLE job_dependencies (
    id              UUID            PRIMARY KEY,
    child_job_id    UUID            NOT NULL,
    parent_job_id   UUID            NOT NULL,
    created_at      TIMESTAMP       NOT NULL,
    -- 같은 child→parent edge 두 번 안 들어가게
    CONSTRAINT uq_job_dep_child_parent UNIQUE (child_job_id, parent_job_id),
    -- 자기 자신 의존 불가 (cycle 의 trivial case 미리 차단)
    CONSTRAINT chk_job_dep_no_self CHECK (child_job_id <> parent_job_id)
);

-- "이 child 가 기다리는 parent 들" — DependencyResolutionService 가 매번 호출
CREATE INDEX idx_job_dep_child ON job_dependencies (child_job_id);

-- "이 parent 가 끝나면 promote 검사할 child 들"
CREATE INDEX idx_job_dep_parent ON job_dependencies (parent_job_id);
