package com.example.gwp.orchestrator.domain

import java.util.Objects

/**
 * 사용자가 Job 제출 시 명시하는 명세.
 *
 * priority / preemptionPolicy 는 nullable — 미지정 시 도메인 기본값 (NORMAL / PREEMPTABLE).
 * 기존 호출자 (테스트 / 옛 controller) backward compat 를 위해 편의 생성자 유지.
 *
 * `@JvmRecord` 의 compact constructor 가 파라미터 재대입을 지원하지 않아 (priority / policy
 * null → default 정규화 불가), 일반 class + custom equals/hashCode + `@get:JvmName`
 * accessor 로 Java record-style 호환을 유지한다 (Java 호출자: `spec.owner()` /
 * `spec.gpuCount()` 등 그대로).
 */
class JobSpec(
    owner: String,
    inputUri: String,
    image: String,
    gpuCount: Int,
    priority: JobPriority?,
    preemptionPolicy: PreemptionPolicy?,
) {

    @get:JvmName("owner")
    val owner: String = owner

    @get:JvmName("inputUri")
    val inputUri: String = inputUri

    @get:JvmName("image")
    val image: String = image

    @get:JvmName("gpuCount")
    val gpuCount: Int = gpuCount

    @get:JvmName("priority")
    val priority: JobPriority = priority ?: JobPriority.NORMAL

    @get:JvmName("preemptionPolicy")
    val preemptionPolicy: PreemptionPolicy = preemptionPolicy ?: PreemptionPolicy.PREEMPTABLE

    /** 우선순위 / preemption policy 미지정 시 default. */
    constructor(owner: String, inputUri: String, image: String, gpuCount: Int) :
        this(owner, inputUri, image, gpuCount, JobPriority.NORMAL, PreemptionPolicy.PREEMPTABLE)

    constructor(owner: String, inputUri: String, image: String, gpuCount: Int, priority: JobPriority?) :
        this(owner, inputUri, image, gpuCount, priority, PreemptionPolicy.PREEMPTABLE)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JobSpec) return false
        return owner == other.owner &&
            inputUri == other.inputUri &&
            image == other.image &&
            gpuCount == other.gpuCount &&
            priority == other.priority &&
            preemptionPolicy == other.preemptionPolicy
    }

    override fun hashCode(): Int =
        Objects.hash(owner, inputUri, image, gpuCount, priority, preemptionPolicy)

    override fun toString(): String =
        "JobSpec[owner=$owner, inputUri=$inputUri, image=$image, gpuCount=$gpuCount, " +
            "priority=$priority, preemptionPolicy=$preemptionPolicy]"
}
