package com.example.gwp.orchestrator.lifecycle;

import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobSpec;
import com.example.gwp.orchestrator.domain.JobStatus;
import com.example.gwp.orchestrator.domain.PreemptionPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle 상태 머신의 *허용 / 거절* 행동 검증. 도메인 메서드와 상관없이 *workflow 어휘*
 * 측 정합성을 단독으로 본다.
 */
class JobLifecycleStateMachineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);
    private final JobLifecycleStateMachine sm = JobLifecycleStateMachineFactory.build();

    @Test
    void fire_allowsCanonicalHappyPath_dispatchRunSucceed() {
        Transition dispatch = sm.fire(JobStatus.QUEUED, JobLifecycleEvent.DISPATCH, null);
        Transition run = sm.fire(JobStatus.DISPATCHING, JobLifecycleEvent.RUN, null);
        Transition succeed = sm.fire(JobStatus.RUNNING, JobLifecycleEvent.SUCCEED, null);

        assertThat(dispatch.target()).isEqualTo(JobStatus.DISPATCHING);
        assertThat(run.target()).isEqualTo(JobStatus.RUNNING);
        assertThat(succeed.target()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void fire_rejectsTransitionFromTerminalState() {
        // 종결 상태에서는 어떤 트리거도 등록되지 않음 → 모두 reject.
        for (JobStatus terminal : new JobStatus[]{
                JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.PREEMPTED}) {
            assertThatThrownBy(() -> sm.fire(terminal, JobLifecycleEvent.RUN, null))
                    .isInstanceOf(IllegalJobLifecycleTransitionException.class)
                    .hasMessageContaining(terminal.name());
        }
    }

    @Test
    void fire_rejectsRunFromWaitingDeps() {
        // WAITING_DEPS 는 자원 할당 안 된 상태 → RUN 으로 바로 못 감 (DEPENDENCIES_RESOLVED 후 QUEUED 거쳐야).
        assertThatThrownBy(() -> sm.fire(JobStatus.WAITING_DEPS, JobLifecycleEvent.RUN, null))
                .isInstanceOf(IllegalJobLifecycleTransitionException.class);
    }

    @Test
    void fire_rejectsDispatchFromWaitingDeps() {
        // 의존성 미해소 잡이 디스패치되면 데이터 race — 허용 안 함.
        assertThatThrownBy(() -> sm.fire(JobStatus.WAITING_DEPS, JobLifecycleEvent.DISPATCH, null))
                .isInstanceOf(IllegalJobLifecycleTransitionException.class);
    }

    @Test
    void fire_preempt_allowedForPreemptableJob() {
        Job preemptable = preemptableJob();
        Transition t = sm.fire(JobStatus.RUNNING, JobLifecycleEvent.PREEMPT, preemptable);
        assertThat(t.target()).isEqualTo(JobStatus.PREEMPTED);
    }

    @Test
    void fire_preempt_rejectsNeverPreemptableJob() {
        Job neverPreemptable = neverPreemptableJob();
        assertThatThrownBy(() -> sm.fire(JobStatus.RUNNING, JobLifecycleEvent.PREEMPT, neverPreemptable))
                .isInstanceOf(IllegalJobLifecycleTransitionException.class)
                .hasMessageContaining("guard");
    }

    @Test
    void fire_preempt_rejectsWhenContextMissing() {
        // context (Job) 가 없으면 가드가 보수적으로 false → reject.
        assertThatThrownBy(() -> sm.fire(JobStatus.RUNNING, JobLifecycleEvent.PREEMPT, null))
                .isInstanceOf(IllegalJobLifecycleTransitionException.class);
    }

    @Test
    void fire_dependenciesResolved_movesWaitingDepsToQueued() {
        Transition t = sm.fire(JobStatus.WAITING_DEPS, JobLifecycleEvent.DEPENDENCIES_RESOLVED, null);
        assertThat(t.target()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void fire_dependenciesBroken_movesWaitingDepsToCancelled() {
        Transition t = sm.fire(JobStatus.WAITING_DEPS, JobLifecycleEvent.DEPENDENCIES_BROKEN, null);
        assertThat(t.target()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void allowedEvents_listsTransitionsForActiveStates() {
        // QUEUED 에서 — DISPATCH / RUN / SUCCEED / FAIL / CANCEL / PREEMPT 가 등록.
        assertThat(sm.allowedEvents(JobStatus.QUEUED))
                .contains(JobLifecycleEvent.DISPATCH, JobLifecycleEvent.RUN,
                        JobLifecycleEvent.SUCCEED, JobLifecycleEvent.FAIL,
                        JobLifecycleEvent.CANCEL, JobLifecycleEvent.PREEMPT);
        // SUCCEEDED — 어떤 event 도 등록 안 됨.
        assertThat(sm.allowedEvents(JobStatus.SUCCEEDED)).isEmpty();
    }

    @Test
    void transitions_includeAllActiveStateExits() {
        // PREEMPTABLE 검증: 모든 active 상태에서 PREEMPT transition 이 등록되어 있어야 한다.
        var preemptSources = sm.transitions().stream()
                .filter(t -> t.event() == JobLifecycleEvent.PREEMPT)
                .map(Transition::source)
                .toList();
        assertThat(preemptSources)
                .containsExactlyInAnyOrder(JobStatus.QUEUED, JobStatus.DISPATCHING,
                        JobStatus.RUNNING, JobStatus.WAITING_DEPS);
    }

    private Job preemptableJob() {
        // PREEMPTABLE 정책으로 잡 생성 — Job.submit 의 default.
        return Job.submit(new JobSpec("alice", "s3://b/i", "eng:1", 1, null, PreemptionPolicy.PREEMPTABLE),
                null, CLOCK);
    }

    private Job neverPreemptableJob() {
        return Job.submit(new JobSpec("bob", "s3://b/i", "eng:1", 1, null, PreemptionPolicy.NEVER),
                null, CLOCK);
    }
}
