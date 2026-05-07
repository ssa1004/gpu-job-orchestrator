package com.example.gwp.orchestrator.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobPreemptionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    private static Job runningPreemptable() {
        Job j = Job.submit(new JobSpec("alice", "s3://b/k", "img:1", 1, JobPriority.LOW, PreemptionPolicy.PREEMPTABLE),
                "trace", CLOCK);
        j.markDispatched("k8s-1", CLOCK);
        j.markRunning(CLOCK);
        return j;
    }

    private static Job runningNeverPreempt() {
        Job j = Job.submit(new JobSpec("bob", "s3://b/k", "img:1", 1, JobPriority.LOW, PreemptionPolicy.NEVER),
                "trace", CLOCK);
        j.markDispatched("k8s-2", CLOCK);
        j.markRunning(CLOCK);
        return j;
    }

    @Test
    void newJob_defaultsToPreemptable() {
        Job j = Job.submit(new JobSpec("alice", "s3://b/k", "img:1", 1), "trace", CLOCK);
        assertThat(j.getPreemptionPolicy()).isEqualTo(PreemptionPolicy.PREEMPTABLE);
    }

    @Test
    void markPreempted_transitionsRunningToPreempted() {
        Job j = runningPreemptable();
        UUID byId = UUID.randomUUID();

        j.markPreempted(byId, "preempted by HIGH job", CLOCK);

        assertThat(j.getStatus()).isEqualTo(JobStatus.PREEMPTED);
        assertThat(j.getPreemptedAt()).isEqualTo(CLOCK.instant());
        assertThat(j.getPreemptedByJobId()).isEqualTo(byId);
        assertThat(j.getPreemptedReason()).isEqualTo("preempted by HIGH job");
        assertThat(j.getFinishedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void markPreempted_neverPolicy_throws() {
        Job j = runningNeverPreempt();
        assertThatThrownBy(() -> j.markPreempted(UUID.randomUUID(), "reason", CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEVER");
    }

    @Test
    void markPreempted_terminalStateThrows() {
        Job j = runningPreemptable();
        j.markSucceeded("s3://result", CLOCK);
        assertThatThrownBy(() -> j.markPreempted(UUID.randomUUID(), "reason", CLOCK))
                .isInstanceOf(IllegalJobTransitionException.class);
    }

    @Test
    void isPreemptable_reflectsStatusAndPolicy() {
        Job preemptable = runningPreemptable();
        assertThat(preemptable.isPreemptable()).isTrue();

        Job never = runningNeverPreempt();
        assertThat(never.isPreemptable()).isFalse();

        Job done = runningPreemptable();
        done.markSucceeded("s3://x", CLOCK);
        assertThat(done.isPreemptable()).isFalse();   // terminal 은 후보 아님
    }
}
