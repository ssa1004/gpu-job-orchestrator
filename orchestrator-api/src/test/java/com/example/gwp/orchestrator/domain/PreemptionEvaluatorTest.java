package com.example.gwp.orchestrator.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreemptionEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-05-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static Job queued(JobPriority pri, int gpu) {
        return Job.submit(new JobSpec("requestor", "s3://b/k", "img:1", gpu, pri, PreemptionPolicy.PREEMPTABLE),
                "trace", CLOCK);
    }

    private static Job running(JobPriority pri, int gpu, PreemptionPolicy pol, Instant startedAt) {
        Job j = Job.submit(new JobSpec("victim-owner", "s3://b/k", "img:1", gpu, pri, pol),
                "trace", Clock.fixed(startedAt.minus(Duration.ofSeconds(5)), ZoneOffset.UTC));
        j.markDispatched("k8s-x", Clock.fixed(startedAt.minus(Duration.ofSeconds(3)), ZoneOffset.UTC));
        j.markRunning(Clock.fixed(startedAt, ZoneOffset.UTC));
        return j;
    }

    @Test
    void noLowerPriorityRunning_noPreemption() {
        Job preemptor = queued(JobPriority.HIGH, 1);
        List<Job> active = List.of(running(JobPriority.HIGH, 1, PreemptionPolicy.PREEMPTABLE, T0));

        PreemptionDecision d = PreemptionEvaluator.evaluate(preemptor, active);

        assertThat(d.shouldPreempt()).isFalse();
    }

    @Test
    void picksLowestPriorityFirst() {
        Job preemptor = queued(JobPriority.HIGH, 1);
        Job lowOlder   = running(JobPriority.LOW,    1, PreemptionPolicy.PREEMPTABLE, T0);
        Job normalNew  = running(JobPriority.NORMAL, 1, PreemptionPolicy.PREEMPTABLE, T0.plusSeconds(60));

        PreemptionDecision d = PreemptionEvaluator.evaluate(preemptor, List.of(normalNew, lowOlder));

        // LOW 가 먼저 죽음 (priority weight 0 < 50)
        assertThat(d.victims()).containsExactly(lowOlder);
    }

    @Test
    void samePriority_picksMostRecentlyStarted() {
        Job preemptor = queued(JobPriority.HIGH, 1);
        Job older = running(JobPriority.LOW, 1, PreemptionPolicy.PREEMPTABLE, T0);
        Job newer = running(JobPriority.LOW, 1, PreemptionPolicy.PREEMPTABLE, T0.plusSeconds(120));

        PreemptionDecision d = PreemptionEvaluator.evaluate(preemptor, List.of(older, newer));

        // 늦게 시작 = 덜 진행 = 먼저 죽임 (손실 적음)
        assertThat(d.victims()).containsExactly(newer);
    }

    @Test
    void neverPreemptable_excludedEvenIfLowerPriority() {
        Job preemptor = queued(JobPriority.HIGH, 1);
        Job neverJob = running(JobPriority.LOW, 4, PreemptionPolicy.NEVER, T0);

        PreemptionDecision d = PreemptionEvaluator.evaluate(preemptor, List.of(neverJob));

        // NEVER 는 후보 아님 → preempt 안 함
        assertThat(d.shouldPreempt()).isFalse();
    }

    @Test
    void aggregatesMultipleVictimsToFreeEnoughGpu() {
        Job preemptor = queued(JobPriority.HIGH, 4);   // 4 GPU 필요
        Job v1 = running(JobPriority.LOW, 2, PreemptionPolicy.PREEMPTABLE, T0);
        Job v2 = running(JobPriority.LOW, 2, PreemptionPolicy.PREEMPTABLE, T0.plusSeconds(60));

        PreemptionDecision d = PreemptionEvaluator.evaluate(preemptor, List.of(v1, v2));

        assertThat(d.victims()).hasSize(2);
        assertThat(d.totalGpuFreed()).isEqualTo(4);
    }

    @Test
    void notEnoughCandidates_noopRatherThanPartialPreempt() {
        Job preemptor = queued(JobPriority.HIGH, 4);
        Job v1 = running(JobPriority.LOW, 1, PreemptionPolicy.PREEMPTABLE, T0);   // 1 GPU 만 풀림 — 부족

        PreemptionDecision d = PreemptionEvaluator.evaluate(preemptor, List.of(v1));

        // 부족하면 preempt 자체를 안 함 (양보해도 자리 안 남)
        assertThat(d.shouldPreempt()).isFalse();
    }

    @Test
    void higherPriorityRunningProtected() {
        Job preemptor = queued(JobPriority.NORMAL, 1);
        Job higher = running(JobPriority.HIGH, 4, PreemptionPolicy.PREEMPTABLE, T0);

        PreemptionDecision d = PreemptionEvaluator.evaluate(preemptor, List.of(higher));

        // HIGH > NORMAL — preemptor 가 못 죽임
        assertThat(d.shouldPreempt()).isFalse();
    }
}
