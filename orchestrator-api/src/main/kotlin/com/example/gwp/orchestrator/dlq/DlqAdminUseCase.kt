package com.example.gwp.orchestrator.dlq

/**
 * 단건 DLQ admin 동작. 컨트롤러가 의존하는 application port (port in).
 *
 * 멱등성: [replay] / [discard] 가 idempotencyKey 를 받아 같은 키로 재호출되면
 * IGNORED 로 끝난다 — gpu 도메인의 callback / dispatch 가 *이미 종료된 잡* 에 대해
 * no-op 으로 떨어지는 short-circuit (PreemptionService 의 victim 종료 검사 패턴) 과
 * 한 결합으로 동작.
 */
interface DlqAdminUseCase {

    fun list(filter: DlqEntryFilter, actor: String, actorKey: String): DlqListPage

    fun detail(messageId: String, actor: String, actorKey: String): DlqMessage?

    fun replay(messageId: String, idempotencyKey: String, actor: String, actorKey: String): ReplayResult

    fun discard(messageId: String, reason: String, actor: String, actorKey: String): DiscardResult

    fun stats(filter: DlqEntryFilter, bucket: String, actor: String, actorKey: String): DlqStats

    data class ReplayResult(val outcome: DlqMessageStore.ReplayOutcome, val messageId: String)

    data class DiscardResult(val outcome: DlqMessageStore.DiscardOutcome, val messageId: String)
}
