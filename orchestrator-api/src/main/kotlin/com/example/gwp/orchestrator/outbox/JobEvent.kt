package com.example.gwp.orchestrator.outbox

/**
 * Job 애그리거트가 발행하는 도메인 이벤트의 sealed marker.
 *
 * typed event 의 장점:
 * - 외부 컨슈머와의 contract 가 코드로 명시됨
 * - 스키마 진화 시 컴파일러가 미사용 필드 catch
 * - `Map<String, Object>` payload 보다 IDE/리뷰 친화적
 *
 * 각 record 는 [OutboxWriter.write] 로 발행되며, JSON 직렬화 시 `_type`
 * 필드로 식별 가능하도록 [type] 을 노출한다.
 *
 * Java 호출자 (`new JobEvent.JobSubmitted(...)`) 그대로 동작 — `@JvmRecord data class` 가
 * 실제 JVM record 로 컴파일되어 `Class#getPermittedSubclasses()` / `getRecordComponents()`
 * 가 인식한다 (contract 의 EventCatalogConsistencyTest 가 요구).
 */
sealed interface JobEvent {

    fun aggregateId(): String

    fun type(): String

    @JvmRecord
    data class JobSubmitted(
        val jobId: String,
        val owner: String,
        val image: String,
        val gpuCount: Int,
        val priority: String,
        val status: String,
        val traceId: String?,
    ) : JobEvent {
        override fun aggregateId(): String = jobId
        override fun type(): String = "JobSubmitted"
    }

    @JvmRecord
    data class JobCompleted(
        val jobId: String,
        val status: String,           // SUCCEEDED | FAILED | CANCELLED
        val resultUri: String?,       // SUCCEEDED 일 때만 not blank
        val errorMessage: String?,    // FAILED 일 때만 not blank
        val finishedAt: String,       // ISO-8601
    ) : JobEvent {
        override fun aggregateId(): String = jobId
        override fun type(): String = "JobCompleted"
    }

    /**
     * Preempt 발생 — 시스템이 더 높은 우선순위 잡에게 GPU 양보 시키느라 이 잡을 죽임.
     * customer 알림 / 빌링 (양보 횟수 보상) / 분석 (어느 priority 가 너무 자주 죽이나).
     */
    @JvmRecord
    data class JobPreempted(
        val jobId: String,
        val owner: String,
        val preemptorJobId: String,
        val preemptorPriority: String,
        val reason: String?,
        val preemptedAt: String,      // ISO-8601
    ) : JobEvent {
        override fun aggregateId(): String = jobId
        override fun type(): String = "JobPreempted"
    }
}
