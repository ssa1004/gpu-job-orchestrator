package com.example.gwp.orchestrator.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Job 엔티티 단위 테스트 — 상태 천이의 invariant 검증.
 * Clock 을 fixed 로 주입하여 시간 결정성 확보.
 */
class JobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);
    private static final JobSpec SPEC = new JobSpec("alice", "s3://bucket/in.bin", "engine:1.0", 1, JobPriority.NORMAL);

    @Test
    void submit_createsJobInQueuedState() {
        Job job = Job.submit(SPEC, "trace-1", CLOCK);

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getOwner()).isEqualTo("alice");
        assertThat(job.getCreatedAt()).isEqualTo(CLOCK.instant());
        assertThat(job.getUpdatedAt()).isEqualTo(CLOCK.instant());
        assertThat(job.getTraceId()).isEqualTo("trace-1");
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getFinishedAt()).isNull();
    }

    @Test
    void markDispatched_setsK8sJobNameAndStatus() {
        Job job = Job.submit(SPEC, null, CLOCK);
        job.markDispatched("k8s-job-abc", CLOCK);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DISPATCHING);
        assertThat(job.getK8sJobName()).isEqualTo("k8s-job-abc");
    }

    @Test
    void markDispatched_failsIfNotQueued() {
        Job job = Job.submit(SPEC, null, CLOCK);
        job.markDispatched("k8s-1", CLOCK);

        assertThatThrownBy(() -> job.markDispatched("k8s-2", CLOCK))
                .isInstanceOf(IllegalJobTransitionException.class);
    }

    @Test
    void markRunning_setsStartedAt() {
        Job job = Job.submit(SPEC, null, CLOCK);
        job.markDispatched("k8s-1", CLOCK);
        job.markRunning(CLOCK);

        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getStartedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void markSucceeded_recordsResult() {
        Job job = Job.submit(SPEC, null, CLOCK);
        job.markDispatched("k8s-1", CLOCK);
        job.markRunning(CLOCK);
        job.markSucceeded("s3://bucket/out.bin", CLOCK);

        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getResultUri()).isEqualTo("s3://bucket/out.bin");
        assertThat(job.getFinishedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void markCancelled_failsIfAlreadyTerminal() {
        Job job = Job.submit(SPEC, null, CLOCK);
        job.markDispatched("k8s-1", CLOCK);
        job.markRunning(CLOCK);
        job.markSucceeded("s3://bucket/out.bin", CLOCK);

        assertThatThrownBy(() -> job.markCancelled(CLOCK))
                .isInstanceOf(IllegalJobTransitionException.class);
    }

    @Test
    void markFailed_capturesReason() {
        Job job = Job.submit(SPEC, null, CLOCK);
        job.markFailed("dispatch failed: timeout", CLOCK);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("timeout");
        assertThat(job.getFinishedAt()).isEqualTo(CLOCK.instant());
    }
}
