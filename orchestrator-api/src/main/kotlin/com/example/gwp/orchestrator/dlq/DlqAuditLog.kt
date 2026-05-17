package com.example.gwp.orchestrator.dlq

/**
 * DLQ 콘솔 동작의 audit log port.
 *
 * 모든 replay / discard / bulk 동작은 actor (admin sub claim) + target (messageId
 * 또는 filter) + reason + 결과 + 관련 jobId / ownerId 가 함께 남아야 한다 — 사후
 * 분쟁 / 회계 감사 시 *누가 무엇을 어떻게 했는가* 추적이 가능해야 한다. 운영 구현은
 * 별 audit topic 으로 발행하는 Kafka 구현, dev / 단위 테스트는 Slf4j.
 */
interface DlqAuditLog {

    fun log(entry: Entry)

    data class Entry(
        val action: Action,
        val actor: String,
        val target: String,
        val reason: String?,
        val outcome: String,
        val jobId: String? = null,
        val ownerId: String? = null,
    )

    enum class Action {
        DLQ_REPLAY,
        DLQ_DISCARD,
        DLQ_BULK_REPLAY,
        DLQ_BULK_DISCARD,
    }
}
