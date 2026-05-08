package com.example.gwp.orchestrator.lifecycle;

import com.example.gwp.orchestrator.domain.JobStatus;

import java.util.Objects;

/**
 * "{@link JobStatus} S 에서 {@link JobLifecycleEvent} E 가 발생하면 {@code target} 으로
 * 전이된다" 는 한 라인의 transition 정의. {@link JobLifecycleStateMachine} 의 transition
 * table 의 element 로 사용된다.
 *
 * <p>guard 는 옵션 — 같은 source/event 에 대해 여러 transition 이 등록되면 *순서대로* 평가,
 * 첫 번째 guard 통과 transition 의 target 으로 전이. 같은 source / event 에 guard 가 모두
 * false 면 transition 이 안 일어난다 ({@link IllegalJobLifecycleTransitionException}).</p>
 *
 * @param source     전이 가능한 시작 상태
 * @param event      트리거 이벤트
 * @param target     전이 후 목적 상태
 * @param guard      옵션 가드. null 이면 항상 통과. JobStatus 와 함께 호출 시점의 도메인
 *                   객체 / 컨텍스트 (예: PreemptionPolicy) 도 전달받을 수 있도록 functional
 *                   interface 로 둔다.
 * @param action     전이 시 실행할 사이드 액션 (옵션). audit log / metric increment 같은 보조 동작.
 */
public record Transition(
        JobStatus source,
        JobLifecycleEvent event,
        JobStatus target,
        Guard guard,
        Action action
) {

    public Transition {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(target, "target");
    }

    /** Convenience — guard / action 없이 단순 source-event-target 만. */
    public static Transition of(JobStatus source, JobLifecycleEvent event, JobStatus target) {
        return new Transition(source, event, target, null, null);
    }

    /** Convenience — guard 만 있는 transition. */
    public static Transition guarded(JobStatus source, JobLifecycleEvent event,
                                     JobStatus target, Guard guard) {
        return new Transition(source, event, target, guard, null);
    }

    /**
     * 가드 평가 함수. context 는 호출자 (예: {@link JobLifecycleStateMachine#fire})
     * 가 전달하는 도메인 객체. {@link com.example.gwp.orchestrator.domain.Job} 인스턴스를
     * 흘려서 {@code job.getPreemptionPolicy()} 같은 조건 평가에 사용.
     */
    @FunctionalInterface
    public interface Guard {
        boolean test(JobStatus current, JobLifecycleEvent event, Object context);
    }

    /** transition 시 실행되는 사이드 액션 (logging / counter / event 발행 등). */
    @FunctionalInterface
    public interface Action {
        void execute(JobStatus from, JobLifecycleEvent event, JobStatus to, Object context);
    }
}
