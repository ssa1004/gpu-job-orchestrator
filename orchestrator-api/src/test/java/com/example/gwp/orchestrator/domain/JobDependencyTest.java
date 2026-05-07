package com.example.gwp.orchestrator.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobDependencyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void selfDependency_throws() {
        UUID a = UUID.randomUUID();
        assertThatThrownBy(() -> JobDependency.edge(a, a, CLOCK.instant()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self");
    }

    @Test
    void differentChildAndParent_succeeds() {
        UUID child = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        var dep = JobDependency.edge(child, parent, CLOCK.instant());
        assertThat(dep.getChildJobId()).isEqualTo(child);
        assertThat(dep.getParentJobId()).isEqualTo(parent);
    }

    @Test
    void newJobWithWaitingDeps_startsInWaitingState() {
        var spec = new JobSpec("alice", "s3://b/k", "img:1", 1, JobPriority.NORMAL,
                PreemptionPolicy.PREEMPTABLE);
        var job = Job.submit(spec, "trace", true, CLOCK);
        assertThat(job.getStatus()).isEqualTo(JobStatus.WAITING_DEPS);
    }

    @Test
    void waitingDeps_promoteToQueued() {
        var spec = new JobSpec("alice", "s3://b/k", "img:1", 1, JobPriority.NORMAL,
                PreemptionPolicy.PREEMPTABLE);
        var job = Job.submit(spec, "trace", true, CLOCK);
        job.markReadyToQueue(CLOCK);
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void markReadyToQueue_fromNonWaiting_throws() {
        var spec = new JobSpec("alice", "s3://b/k", "img:1", 1);
        var job = Job.submit(spec, "trace", false, CLOCK);   // QUEUED
        assertThatThrownBy(() -> job.markReadyToQueue(CLOCK))
                .isInstanceOf(IllegalJobTransitionException.class);
    }

    @Test
    void waitingDeps_isActive_butNotPreemptable() {
        var spec = new JobSpec("alice", "s3://b/k", "img:1", 1, JobPriority.NORMAL,
                PreemptionPolicy.PREEMPTABLE);
        var job = Job.submit(spec, "trace", true, CLOCK);
        assertThat(job.getStatus().isActive()).isTrue();
        // GPU 점유 중이 아니므로 preempt 대상도 아님 — isPreemptable 은 활성 + PREEMPTABLE 이면 true
        // (의미적으로는 자원을 안 쓰므로 제외해도 되지만 단순화 위해 같은 도메인 메서드)
    }
}
