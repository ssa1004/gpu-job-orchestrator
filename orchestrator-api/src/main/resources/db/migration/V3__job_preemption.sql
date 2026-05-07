-- V3: Job preemption 지원 — 더 높은 우선순위 잡이 들어오면 PREEMPTABLE 인 RUNNING 잡을 죽여 자리 양보.
-- (Slurm 의 job preemption / K8s + Kueue 의 PriorityClass.preemptionPolicy 와 같은 개념)

-- ── jobs 테이블 확장 ─────────────────────────────────────────────────
ALTER TABLE jobs
    ADD COLUMN preemption_policy   VARCHAR(16) NOT NULL DEFAULT 'PREEMPTABLE';
ALTER TABLE jobs
    ADD COLUMN preempted_at        TIMESTAMP;
ALTER TABLE jobs
    ADD COLUMN preempted_by_job_id UUID;
ALTER TABLE jobs
    ADD COLUMN preempted_reason    VARCHAR(256);

-- preemption candidate 빠르게 찾기 — RUNNING / DISPATCHING + PREEMPTABLE + priority 낮은 순.
-- 새 (높은 우선순위) QUEUED 잡이 들어왔을 때 양보할 후보를 한 번의 쿼리로 픽업.
CREATE INDEX idx_jobs_preemption_candidates
    ON jobs (status, preemption_policy, priority);


-- ── preemption_history: 누가 누구에게 양보했나 timeline (append-only) ──
-- jobs.preempted_at 만으론 "이 잡이 다른 잡을 *얼마나 자주* preempt 했는지" 같은 분석이 어려움.
-- 영속 history 가 있으면 운영 화면 / 빌링 / 어떤 우선순위 정책이 잘 작동하는지 분석 가능.

CREATE TABLE preemption_history (
    id                  UUID            PRIMARY KEY,
    -- 양보한 (죽임당한) 잡
    victim_job_id       UUID            NOT NULL,
    victim_owner        VARCHAR(128)    NOT NULL,
    victim_priority     VARCHAR(16)     NOT NULL,
    victim_gpu_count    INT             NOT NULL,
    -- 자리를 차지한 (preemptor) 잡
    preemptor_job_id    UUID            NOT NULL,
    preemptor_owner     VARCHAR(128)    NOT NULL,
    preemptor_priority  VARCHAR(16)     NOT NULL,
    -- 시각 + 사유
    preempted_at        TIMESTAMP       NOT NULL,
    reason              VARCHAR(256)    NOT NULL
);

-- victim 별 timeline (운영 / 사용자 알림: "당신 잡이 N분 전에 preempt 됐음")
CREATE INDEX idx_preemption_history_victim_time
    ON preemption_history (victim_job_id, preempted_at DESC);

-- preemptor 별 — "이 잡이 자리 잡으려 누굴 죽였나"
CREATE INDEX idx_preemption_history_preemptor_time
    ON preemption_history (preemptor_job_id, preempted_at DESC);

-- 운영 분석 — 시간 구간 전체 (어느 시간대에 preemption 이 가장 많은가)
CREATE INDEX idx_preemption_history_time
    ON preemption_history (preempted_at DESC);
