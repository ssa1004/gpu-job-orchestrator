package com.example.gwp.orchestrator.domain

import java.util.Objects

/**
 * Preemption 평가 결과 — 한 QUEUED 잡 (preemptor) 을 위해 어떤 RUNNING 잡들 (victims) 을
 * 죽일지 결정.
 *
 * `victims` 가 비어 있으면 "preempt 할 필요 없음" — 이 경우 호출자는 그냥 일반
 * dispatch 시도. 비어 있지 않으면 victim 들을 markPreempted 한 후 GPU 가 비면 preemptor 를
 * dispatch.
 *
 * victim 선정 책임은 [PreemptionEvaluator] — 본 record 는 결과만 담는 단순 DTO.
 *
 * `@JvmRecord` 의 compact constructor 가 `victims = List.copyOf(victims)` 같은 재대입을
 * 지원하지 않아, 일반 class + custom equals/hashCode + `@get:JvmName` accessor 로
 * Java record-style 호환을 유지한다 (Java: `decision.victims()` / `decision.preemptor()` 그대로).
 */
class PreemptionDecision(preemptor: Job, victims: List<Job>) {

    @get:JvmName("preemptor")
    val preemptor: Job = preemptor

    @get:JvmName("victims")
    val victims: List<Job> = java.util.List.copyOf(victims)

    fun shouldPreempt(): Boolean = victims.isNotEmpty()

    fun totalGpuFreed(): Int = victims.sumOf { it.gpuCount }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreemptionDecision) return false
        return preemptor == other.preemptor && victims == other.victims
    }

    override fun hashCode(): Int = Objects.hash(preemptor, victims)

    override fun toString(): String = "PreemptionDecision[preemptor=$preemptor, victims=$victims]"

    companion object {
        @JvmStatic
        fun noop(preemptor: Job): PreemptionDecision = PreemptionDecision(preemptor, emptyList())
    }
}
