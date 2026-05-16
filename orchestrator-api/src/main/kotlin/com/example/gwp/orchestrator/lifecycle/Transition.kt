package com.example.gwp.orchestrator.lifecycle

import com.example.gwp.orchestrator.domain.JobStatus
import java.util.Objects

/**
 * "[JobStatus] S 에서 [JobLifecycleEvent] E 가 발생하면 `target` 으로
 * 전이된다" 는 한 라인의 transition 정의. [JobLifecycleStateMachine] 의 transition
 * table 의 element 로 사용된다.
 *
 * guard 는 옵션 — 같은 source/event 에 대해 여러 transition 이 등록되면 *순서대로* 평가,
 * 첫 번째 guard 통과 transition 의 target 으로 전이. 같은 source / event 에 guard 가 모두
 * false 면 transition 이 안 일어난다 ([IllegalJobLifecycleTransitionException]).
 *
 * Java 호출자 (`Transition.of(...)` / `Transition.guarded(...)` / `t.source()` 등) 그대로
 * 동작 — class + custom equals/hashCode + `@get:JvmName` accessor + `@JvmStatic` factory.
 * `@JvmRecord` 의 compact constructor 는 추가 검증 외 재할당이 없어서 사용 가능하지만,
 * Guard/Action SAM 호환을 더 자연스럽게 두기 위해 일반 class 로 통일.
 *
 * @param source     전이 가능한 시작 상태
 * @param event      트리거 이벤트
 * @param target     전이 후 목적 상태
 * @param guard      옵션 가드. null 이면 항상 통과. JobStatus 와 함께 호출 시점의 도메인
 *                   객체 / 컨텍스트 (예: PreemptionPolicy) 도 전달받을 수 있도록 functional
 *                   interface 로 둔다.
 * @param action     전이 시 실행할 사이드 액션 (옵션). audit log / metric increment 같은 보조 동작.
 */
class Transition(
    source: JobStatus,
    event: JobLifecycleEvent,
    target: JobStatus,
    guard: Guard?,
    action: Action?,
) {

    @get:JvmName("source")
    val source: JobStatus = source

    @get:JvmName("event")
    val event: JobLifecycleEvent = event

    @get:JvmName("target")
    val target: JobStatus = target

    @get:JvmName("guard")
    val guard: Guard? = guard

    @get:JvmName("action")
    val action: Action? = action

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Transition) return false
        return source == other.source &&
            event == other.event &&
            target == other.target &&
            guard == other.guard &&
            action == other.action
    }

    override fun hashCode(): Int = Objects.hash(source, event, target, guard, action)

    override fun toString(): String =
        "Transition[source=$source, event=$event, target=$target, guard=$guard, action=$action]"

    /**
     * 가드 평가 함수. context 는 호출자 (예: [JobLifecycleStateMachine.fire])
     * 가 전달하는 도메인 객체. [com.example.gwp.orchestrator.domain.Job] 인스턴스를
     * 흘려서 `job.getPreemptionPolicy()` 같은 조건 평가에 사용.
     */
    fun interface Guard {
        fun test(current: JobStatus, event: JobLifecycleEvent, context: Any?): Boolean
    }

    /** transition 시 실행되는 사이드 액션 (logging / counter / event 발행 등). */
    fun interface Action {
        fun execute(from: JobStatus, event: JobLifecycleEvent, to: JobStatus, context: Any?)
    }

    companion object {
        /** Convenience — guard / action 없이 단순 source-event-target 만. */
        @JvmStatic
        fun of(source: JobStatus, event: JobLifecycleEvent, target: JobStatus): Transition =
            Transition(source, event, target, null, null)

        /** Convenience — guard 만 있는 transition. */
        @JvmStatic
        fun guarded(
            source: JobStatus,
            event: JobLifecycleEvent,
            target: JobStatus,
            guard: Guard,
        ): Transition = Transition(source, event, target, guard, null)
    }
}
