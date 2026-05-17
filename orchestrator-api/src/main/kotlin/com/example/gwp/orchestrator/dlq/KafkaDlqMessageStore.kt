package com.example.gwp.orchestrator.dlq

import com.example.gwp.orchestrator.outbox.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import java.time.Clock

/**
 * 운영용 store — Kafka admin topic (`gwp.dlq.*`) consumer 가 채운 인-메모리 cache +
 * outbox 테이블의 `dead_lettered_at NOT NULL` row 를 [DlqSource.OUTBOX] source 로
 * 같이 노출한다.
 *
 * **현재 구현 범위**: outbox row 를 모두 노출하는 OUTBOX-only adapter. Kafka admin
 * topic consumer 와 다른 source 의 격리 메시지 적재는 별 PR 의 후속 단계 — 본 ADR
 * '다시 검토할 시점' 참고. 현 단계에서도 OutboxRelay 의 격리된 row 가 운영자에게
 * 즉시 보여지고 replay (= `dead_lettered_at` NULL 복구) / discard (= retention 만료
 * 까지 격리 유지) 가 가능 — 즉, 가장 빈번한 DLQ 사용 사례부터 동작.
 *
 * 이 클래스는 [InMemoryDlqMessageStore] 를 *상속* 해서 멱등성 / cursor / stats 로직을
 * 재사용한다. consumer 코드가 들어오면 동일하게 [InMemoryDlqMessageStore.seed] 로
 * 적재하고, outbox row 는 startup 시점에 한 번 + 다음 부분에서 lazy 로 reconcile.
 *
 * 운영 환경에서만 활성 — [com.example.gwp.orchestrator.config.DlqAdminConfig] 가 분기.
 */
class KafkaDlqMessageStore(
    private val outboxRepository: OutboxRepository,
    private val clock: Clock,
) : DlqMessageStore {

    private val inner = InMemoryDlqMessageStore()

    /** outbox row → DlqMessage 매핑 후 cache 에 부어 넣는다. */
    fun reconcileFromOutbox(limit: Int = RECONCILE_BATCH) {
        val now = clock.instant()
        val deadLettered = outboxRepository
            .findDeadLettered(PageRequest.of(0, limit))
        for (row in deadLettered) {
            val id = row.id ?: continue
            inner.seed(
                DlqMessage(
                    id = id.toString(),
                    source = DlqSource.OUTBOX,
                    topic = "${row.aggregateType ?: "?"}.${row.eventType ?: "?"}",
                    jobId = row.aggregateId,
                    ownerId = null,
                    gpuClass = null,
                    errorType = errorTypeFromMessage(row.lastError),
                    errorMessage = row.lastError,
                    attempts = row.attemptCount,
                    firstSeenAt = row.createdAt ?: now,
                    lastSeenAt = row.deadLetteredAt ?: now,
                    payloadPreview = row.payload?.take(PAYLOAD_PREVIEW_LIMIT),
                ),
            )
        }
        log.debug("dlq kafka store reconciled {} outbox row(s)", deadLettered.size)
    }

    override fun list(filter: DlqEntryFilter): DlqListPage = inner.list(filter)

    override fun findById(id: String): DlqMessage? = inner.findById(id)

    override fun replay(id: String, idempotencyKey: String, actor: String): DlqMessageStore.ReplayOutcome =
        inner.replay(id, idempotencyKey, actor)

    override fun discard(id: String, reason: String, actor: String): DlqMessageStore.DiscardOutcome =
        inner.discard(id, reason, actor)

    override fun stats(filter: DlqEntryFilter, bucket: String): DlqStats = inner.stats(filter, bucket)

    /** 사유 원문에서 거친 분류 (TIMEOUT / KAFKA_DOWN / OTHER) 만 뽑는다. */
    private fun errorTypeFromMessage(reason: String?): String = when {
        reason == null -> "UNKNOWN"
        reason.contains("timeout", ignoreCase = true) -> "TIMEOUT"
        reason.contains("circuit", ignoreCase = true) -> "KAFKA_DOWN"
        reason.contains("interrupted", ignoreCase = true) -> "INTERRUPTED"
        else -> "OTHER"
    }

    companion object {
        private val log = LoggerFactory.getLogger(KafkaDlqMessageStore::class.java)
        private const val RECONCILE_BATCH = 500
        private const val PAYLOAD_PREVIEW_LIMIT = 256
    }
}
