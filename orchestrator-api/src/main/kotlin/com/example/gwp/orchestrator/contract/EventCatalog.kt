package com.example.gwp.orchestrator.contract

import com.example.gwp.orchestrator.outbox.JobEvent
import java.util.LinkedHashMap

/**
 * 발행 이벤트들의 schema catalog — AsyncAPI spec 생성과 consumer-driven contract test 의
 * 단일 진실 (single source of truth).
 *
 * 왜 이 카탈로그를 코드로 두는가:
 * - [JobEvent] record 는 producer 측 *직렬화 형태* 의 source. consumer 가 보는
 *   *공개 contract* 는 따로 명시적으로 선언해야 — record 의 모든 component 가 contract 의
 *   일부일 필요는 없다 (예: 내부 디버깅 필드는 schema 에서 빼는 식).
 * - 반대로 schema 는 있는데 record 에 component 가 없으면 직렬화에 누락 — 그래서
 *   `EventCatalogConsistencyTest` 가 두 정의의 정합성을 컴파일 후 매 빌드마다 검증한다.
 *
 * 스키마 진화 규칙:
 * - **OK**: optional 필드 추가 (consumer 는 모르는 필드 무시).
 * - **BREAKING**: 기존 필수 필드 제거 / 이름 변경 / 타입 변경.
 * - **주의**: required → optional 로 완화는 forward-compatible 이지만 consumer 가
 *   이미 non-null 가정했다면 런타임 NPE. 신중히.
 *
 * ADR-0020 참고.
 *
 * Java 호출자 (`EventCatalog.all()` / `EventCatalog.jobSubmitted()` 등) 그대로 동작 —
 * Kotlin `object` 의 `@JvmStatic` 메서드가 같은 static 시그니처를 노출.
 */
object EventCatalog {

    /** 이 producer 가 발행하는 모든 이벤트의 schema. 순서 보존 ([LinkedHashMap]). */
    @JvmStatic
    fun all(): List<EventSchema> = listOf(jobSubmitted(), jobCompleted(), jobPreempted())

    /** [JobEvent.JobSubmitted]. consumer: worker pool / dashboard / billing pre-check. */
    @JvmStatic
    fun jobSubmitted(): EventSchema {
        val props = LinkedHashMap<String, Map<String, Any>>()
        props["jobId"] = strField("uuid")
        props["owner"] = strField(null)
        props["image"] = strField(null)
        val gpuCountSchema = LinkedHashMap<String, Any>()
        gpuCountSchema["type"] = "integer"
        gpuCountSchema["minimum"] = 1
        props["gpuCount"] = gpuCountSchema
        props["priority"] = enumField("LOW", "NORMAL", "HIGH", "CRITICAL")
        props["status"] = strField(null)
        props["traceId"] = strField(null)
        return EventSchema(
            "JobSubmitted",
            "신규 잡이 제출되어 QUEUED 상태로 들어왔다 (의존성이 있으면 WAITING_DEPS).",
            props,
            listOf("jobId", "owner", "image", "gpuCount", "priority", "status"),
        )
    }

    /** [JobEvent.JobCompleted]. consumer: result-listener / billing finalize / 알림. */
    @JvmStatic
    fun jobCompleted(): EventSchema {
        val props = LinkedHashMap<String, Map<String, Any>>()
        props["jobId"] = strField("uuid")
        props["status"] = enumField("SUCCEEDED", "FAILED", "CANCELLED")
        props["resultUri"] = strField(null)
        props["errorMessage"] = strField(null)
        props["finishedAt"] = strField("date-time")
        return EventSchema(
            "JobCompleted",
            "잡이 종료되었다. SUCCEEDED 면 resultUri 가, FAILED 면 errorMessage 가 채워진다.",
            props,
            listOf("jobId", "status", "finishedAt"),
        )
    }

    /** [JobEvent.JobPreempted]. consumer: 알림 / 빌링 (양보 보상) / 분석. */
    @JvmStatic
    fun jobPreempted(): EventSchema {
        val props = LinkedHashMap<String, Map<String, Any>>()
        props["jobId"] = strField("uuid")
        props["owner"] = strField(null)
        props["preemptorJobId"] = strField("uuid")
        props["preemptorPriority"] = enumField("LOW", "NORMAL", "HIGH", "CRITICAL")
        props["reason"] = strField(null)
        props["preemptedAt"] = strField("date-time")
        return EventSchema(
            "JobPreempted",
            "더 높은 우선순위 잡에게 GPU 를 양보당해 죽었다 (잡 자체에는 잘못 없음).",
            props,
            listOf("jobId", "owner", "preemptorJobId", "preemptorPriority", "preemptedAt"),
        )
    }

    /** Helper — string 타입 + 선택적 format. LinkedHashMap 으로 순서 보존 (baseline 안정성). */
    private fun strField(format: String?): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        m["type"] = "string"
        if (format != null) m["format"] = format
        return m
    }

    /** Helper — enum 필드. */
    private fun enumField(vararg values: String): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        m["type"] = "string"
        m["enum"] = listOf(*values)
        return m
    }
}
