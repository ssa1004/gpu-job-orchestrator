package com.example.gwp.orchestrator.cost;

import com.example.gwp.orchestrator.domain.JobStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobCostRecordTest {

    private static final CostRate RATE = new CostRate(new BigDecimal("5000"));
    private static final Instant T0 = Instant.parse("2026-05-04T10:00:00Z");

    /** 정상 SUCCEEDED 잡 — runtime 정상 계산, cost 가 rate × gpuCount × hours. */
    @Test
    void forJob_succeeded_runtimeAndCostComputed() {
        UUID jobId = UUID.randomUUID();
        Instant started = T0;
        Instant finished = T0.plusSeconds(3600);   // 1시간

        JobCostRecord record = JobCostRecord.forJob(
                jobId, "alice", 2, started, finished, JobStatus.SUCCEEDED, RATE, finished);

        assertThat(record.getJobId()).isEqualTo(jobId);
        assertThat(record.getRuntimeMillis()).isEqualTo(3_600_000L);
        assertThat(record.getRatePerGpuHour()).isEqualByComparingTo("5000");
        // 5000 × 2 GPU × 1h = 10,000
        assertThat(record.getComputedCost()).isEqualByComparingTo("10000");
        assertThat(record.getCurrency()).isEqualTo("KRW");
        assertThat(record.getFinalStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    /** Dispatch 실패 잡 — startedAt null, runtime 0, cost 0, 그래도 record 생성됨. */
    @Test
    void forJob_dispatchFailed_runtimeZero() {
        UUID jobId = UUID.randomUUID();

        JobCostRecord record = JobCostRecord.forJob(
                jobId, "alice", 4, null, T0, JobStatus.FAILED, RATE, T0);

        assertThat(record.getRuntimeMillis()).isZero();
        assertThat(record.getComputedCost()).isEqualByComparingTo("0");
        assertThat(record.getJobStartedAt()).isNull();
        assertThat(record.getJobFinishedAt()).isEqualTo(T0);
    }

    /** Clock skew 방어 — finished 가 started 보다 약간 앞이어도 runtime 0 (음수 → 0). */
    @Test
    void forJob_clockSkew_runtimeNonNegative() {
        Instant started = T0.plusSeconds(1);
        Instant finished = T0;   // 거꾸로

        JobCostRecord record = JobCostRecord.forJob(
                UUID.randomUUID(), "alice", 1, started, finished, JobStatus.SUCCEEDED, RATE, T0);

        assertThat(record.getRuntimeMillis()).isZero();
        assertThat(record.getComputedCost()).isEqualByComparingTo("0");
    }

    /** PREEMPTED 잡 — 그때까지 사용한 시간만큼만 청구. */
    @Test
    void forJob_preempted_partialRuntimeBilled() {
        Instant started = T0;
        Instant preemptedAt = T0.plusSeconds(900);   // 15분

        JobCostRecord record = JobCostRecord.forJob(
                UUID.randomUUID(), "alice", 4, started, preemptedAt, JobStatus.PREEMPTED, RATE, preemptedAt);

        // 5000원 × 4 GPU × 0.25h = 5,000
        assertThat(record.getComputedCost()).isEqualByComparingTo("5000");
        assertThat(record.getFinalStatus()).isEqualTo(JobStatus.PREEMPTED);
    }
}
