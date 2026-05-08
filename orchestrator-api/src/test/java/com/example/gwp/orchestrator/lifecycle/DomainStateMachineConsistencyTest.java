package com.example.gwp.orchestrator.lifecycle;

import com.example.gwp.orchestrator.domain.IllegalJobTransitionException;
import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobSpec;
import com.example.gwp.orchestrator.domain.JobStatus;
import com.example.gwp.orchestrator.domain.PreemptionPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 도메인 메서드 ({@code Job.mark*}) 와 라이프사이클 상태 머신의 transition table 사이의
 * 정합성 검증.
 *
 * <p>핵심 보장: <i>도메인이 허용하는 전이 = 상태 머신이 허용하는 전이</i>. 둘이 어긋나면
 * 둘 중 하나가 거짓 — 운영 시 어떤 코드 경로는 도메인은 통과하는데 머신은 막거나, 반대거나.
 * 이 테스트가 매 빌드마다 그 어긋남을 잡는다.</p>
 */
class DomainStateMachineConsistencyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);
    private final JobLifecycleStateMachine sm = JobLifecycleStateMachineFactory.build();

    @Test
    void runTransition_acceptedBy_bothDomainAndStateMachine() {
        for (JobStatus source : new JobStatus[]{JobStatus.QUEUED, JobStatus.DISPATCHING}) {
            // 머신 측 — 통과해야 한다.
            assertThat(sm.fire(source, JobLifecycleEvent.RUN, null).target()).isEqualTo(JobStatus.RUNNING);

            // 도메인 측 — 같은 source 에서 markRunning 이 통과해야 한다.
            Job job = jobIn(source);
            job.markRunning(CLOCK);
            assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        }
    }

    @Test
    void preempt_neverPolicy_rejectedBy_bothDomainAndStateMachine() {
        Job neverJob = neverPreemptableRunning();

        // 머신 측 — 가드가 reject.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> sm.fire(neverJob.getStatus(), JobLifecycleEvent.PREEMPT, neverJob))
                .isInstanceOf(IllegalJobLifecycleTransitionException.class);

        // 도메인 측 — markPreempted 가 IllegalState 로 reject.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> neverJob.markPreempted(UUID.randomUUID(), "test", CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalState_rejectsAnyTransitionInBoth() {
        // SUCCEEDED → 어떤 도메인 메서드도, 어떤 머신 event 도 막혀야 한다.
        Job done = jobIn(JobStatus.RUNNING);
        done.markSucceeded("s3://b/o", CLOCK);

        // 도메인 측 — 다른 mark* 호출 시 IllegalJobTransitionException.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> done.markRunning(CLOCK))
                .isInstanceOf(IllegalJobTransitionException.class);

        // 머신 측 — terminal 상태에는 등록된 event 가 0.
        assertThat(sm.allowedEvents(done.getStatus())).isEmpty();
    }

    private Job jobIn(JobStatus status) {
        Job job = Job.submit(new JobSpec("alice", "s3://b/i", "eng:1", 1), null, CLOCK);
        if (status == JobStatus.DISPATCHING) job.markDispatched("k8s-1", CLOCK);
        else if (status == JobStatus.RUNNING) {
            job.markDispatched("k8s-1", CLOCK);
            job.markRunning(CLOCK);
        }
        return job;
    }

    private Job neverPreemptableRunning() {
        Job job = Job.submit(new JobSpec("bob", "s3://b/i", "eng:1", 1, null, PreemptionPolicy.NEVER),
                null, CLOCK);
        job.markDispatched("k8s-1", CLOCK);
        job.markRunning(CLOCK);
        return job;
    }
}
