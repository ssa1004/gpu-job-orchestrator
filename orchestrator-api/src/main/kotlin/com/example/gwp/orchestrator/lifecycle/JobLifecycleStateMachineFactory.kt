package com.example.gwp.orchestrator.lifecycle

import com.example.gwp.orchestrator.domain.Job
import com.example.gwp.orchestrator.domain.JobStatus
import com.example.gwp.orchestrator.domain.JobStatus.CANCELLED
import com.example.gwp.orchestrator.domain.JobStatus.DISPATCHING
import com.example.gwp.orchestrator.domain.JobStatus.FAILED
import com.example.gwp.orchestrator.domain.JobStatus.PREEMPTED
import com.example.gwp.orchestrator.domain.JobStatus.QUEUED
import com.example.gwp.orchestrator.domain.JobStatus.RUNNING
import com.example.gwp.orchestrator.domain.JobStatus.SUCCEEDED
import com.example.gwp.orchestrator.domain.JobStatus.WAITING_DEPS
import com.example.gwp.orchestrator.domain.PreemptionPolicy
import com.example.gwp.orchestrator.lifecycle.JobLifecycleEvent.CANCEL
import com.example.gwp.orchestrator.lifecycle.JobLifecycleEvent.DEPENDENCIES_BROKEN
import com.example.gwp.orchestrator.lifecycle.JobLifecycleEvent.DEPENDENCIES_RESOLVED
import com.example.gwp.orchestrator.lifecycle.JobLifecycleEvent.DISPATCH
import com.example.gwp.orchestrator.lifecycle.JobLifecycleEvent.FAIL
import com.example.gwp.orchestrator.lifecycle.JobLifecycleEvent.PREEMPT
import com.example.gwp.orchestrator.lifecycle.JobLifecycleEvent.RUN
import com.example.gwp.orchestrator.lifecycle.JobLifecycleEvent.SUCCEED
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 잡 라이프사이클의 *공식 transition table*. 도메인 메서드들과 1:1 대응되도록 정의.
 *
 * ### 전이 catalog
 * ```
 *  SUBMIT             :              → QUEUED
 *  SUBMIT_WITH_DEPS   :              → WAITING_DEPS
 *
 *  DEPENDENCIES_RESOLVED  : WAITING_DEPS → QUEUED
 *  DEPENDENCIES_BROKEN    : WAITING_DEPS → CANCELLED
 *
 *  DISPATCH           : QUEUED       → DISPATCHING
 *  RUN                : QUEUED       → RUNNING        (워커가 즉시 RUNNING 콜백을 보낼 수 있다)
 *  RUN                : DISPATCHING  → RUNNING
 *
 *  SUCCEED            : QUEUED / DISPATCHING / RUNNING / WAITING_DEPS → SUCCEEDED
 *  FAIL               : QUEUED / DISPATCHING / RUNNING / WAITING_DEPS → FAILED
 *  CANCEL             : QUEUED / DISPATCHING / RUNNING / WAITING_DEPS → CANCELLED
 *
 *  PREEMPT            : QUEUED / DISPATCHING / RUNNING / WAITING_DEPS → PREEMPTED   (PREEMPTABLE 만)
 * ```
 *
 * ### 가드
 * [JobLifecycleEvent.PREEMPT] 는 [PreemptionPolicy.PREEMPTABLE] 인 잡만 허용 —
 * 가드가 NEVER 정책을 reject. 도메인의 [Job.markPreempted] 도 같은 방어선이지만,
 * 상태 머신 단계에서 reject 하면 *왜 거절했는지* (NEVER 정책) 가 audit log 에 명확히 박힘.
 *
 * Java 호출자 (`JobLifecycleStateMachineFactory.build()`) 그대로 동작 — companion `@JvmStatic`
 * 으로 static 시그니처 보존. `plugin.spring` 이 `@Configuration` 자동 open.
 */
@Configuration
class JobLifecycleStateMachineFactory {

    @Bean
    open fun jobLifecycleStateMachine(): JobLifecycleStateMachine = build()

    companion object {

        /** 테스트 / 외부 도구 (다이어그램 export) 가 직접 호출. */
        @JvmStatic
        fun build(): JobLifecycleStateMachine {
            // SUBMIT / SUBMIT_WITH_DEPS 는 *entry transition* (source 없음). 이 머신은 source-keyed
            // 이라 entry 는 다루지 않는다 — entry 는 도메인 (`Job.submit`) 에 위임하고
            // 이 머신은 *후속 전이* 만 catalog 화. 그 결정의 근거는 ADR-0022 본문 참고.
            val transitions: List<Transition> = listOf(
                // 의존성 해소 → Queue.
                Transition.of(WAITING_DEPS, DEPENDENCIES_RESOLVED, QUEUED),
                Transition.of(WAITING_DEPS, DEPENDENCIES_BROKEN, CANCELLED),

                // 디스패치.
                Transition.of(QUEUED, DISPATCH, DISPATCHING),

                // 실행 시작 — 두 source 에서.
                Transition.of(QUEUED, RUN, RUNNING),
                Transition.of(DISPATCHING, RUN, RUNNING),

                // 성공.
                Transition.of(QUEUED, SUCCEED, SUCCEEDED),
                Transition.of(DISPATCHING, SUCCEED, SUCCEEDED),
                Transition.of(RUNNING, SUCCEED, SUCCEEDED),
                Transition.of(WAITING_DEPS, SUCCEED, SUCCEEDED),

                // 실패.
                Transition.of(QUEUED, FAIL, FAILED),
                Transition.of(DISPATCHING, FAIL, FAILED),
                Transition.of(RUNNING, FAIL, FAILED),
                Transition.of(WAITING_DEPS, FAIL, FAILED),

                // 사용자 취소.
                Transition.of(QUEUED, CANCEL, CANCELLED),
                Transition.of(DISPATCHING, CANCEL, CANCELLED),
                Transition.of(RUNNING, CANCEL, CANCELLED),
                Transition.of(WAITING_DEPS, CANCEL, CANCELLED),

                // Preempt — PREEMPTABLE 가드.
                Transition.guarded(QUEUED, PREEMPT, PREEMPTED, ::isPreemptable),
                Transition.guarded(DISPATCHING, PREEMPT, PREEMPTED, ::isPreemptable),
                Transition.guarded(RUNNING, PREEMPT, PREEMPTED, ::isPreemptable),
                Transition.guarded(WAITING_DEPS, PREEMPT, PREEMPTED, ::isPreemptable),
            )
            return JobLifecycleStateMachine(transitions)
        }

        /**
         * Preempt 가드. context 가 [Job] 이면 그 잡의 preemption policy 검사. context 가
         * null 이거나 다른 타입이면 보수적으로 false (도메인이 별도로 검증).
         *
         * source / event 는 사용하지 않지만 [Transition.Guard.test] 시그니처를 만족시켜
         * `::isPreemptable` 메서드 참조를 그대로 [Transition.guarded] 에 넘기기 위해 유지한다.
         */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun isPreemptable(source: JobStatus, event: JobLifecycleEvent, context: Any?): Boolean {
            if (context is Job) {
                return context.preemptionPolicy == PreemptionPolicy.PREEMPTABLE
            }
            return false
        }
    }
}
