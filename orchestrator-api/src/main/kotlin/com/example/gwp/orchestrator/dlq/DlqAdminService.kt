package com.example.gwp.orchestrator.dlq

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 단건 DLQ admin 동작. rate limit / audit / port 호출의 *공통 조각* 만 담당하고,
 * 실제 메시지 read / replay / discard 는 [DlqMessageStore] 에 위임 (hexagonal).
 *
 * `@Service` 클래스 — `plugin.spring` 이 자동 open.
 */
@Service
class DlqAdminService(
    private val store: DlqMessageStore,
    private val rateLimiter: AdminRateLimiter,
    private val auditLog: DlqAuditLog,
) : DlqAdminUseCase {

    override fun list(filter: DlqEntryFilter, actor: String, actorKey: String): DlqListPage {
        guard(actorKey, AdminRateLimiter.Scope.READ)
        return store.list(filter)
    }

    override fun detail(messageId: String, actor: String, actorKey: String): DlqMessage? {
        guard(actorKey, AdminRateLimiter.Scope.READ)
        return store.findById(messageId)
    }

    override fun replay(
        messageId: String,
        idempotencyKey: String,
        actor: String,
        actorKey: String,
    ): DlqAdminUseCase.ReplayResult {
        guard(actorKey, AdminRateLimiter.Scope.WRITE)
        val before = store.findById(messageId) ?: throw DlqMessageNotFoundException(messageId)
        val outcome = store.replay(messageId, idempotencyKey, actor)
        auditLog.log(
            DlqAuditLog.Entry(
                action = DlqAuditLog.Action.DLQ_REPLAY,
                actor = actor,
                target = messageId,
                reason = "idempotencyKey=$idempotencyKey",
                outcome = outcome.name,
                jobId = before.jobId,
                ownerId = before.ownerId,
            ),
        )
        log.info(
            "dlq replay messageId={} source={} jobId={} owner={} outcome={}",
            messageId, before.source, before.jobId, before.ownerId, outcome,
        )
        return DlqAdminUseCase.ReplayResult(outcome, messageId)
    }

    override fun discard(
        messageId: String,
        reason: String,
        actor: String,
        actorKey: String,
    ): DlqAdminUseCase.DiscardResult {
        guard(actorKey, AdminRateLimiter.Scope.WRITE)
        if (reason.isBlank()) {
            throw DlqAdminBadRequestException("reason must not be blank")
        }
        val before = store.findById(messageId) ?: throw DlqMessageNotFoundException(messageId)
        val outcome = store.discard(messageId, reason, actor)
        auditLog.log(
            DlqAuditLog.Entry(
                action = DlqAuditLog.Action.DLQ_DISCARD,
                actor = actor,
                target = messageId,
                reason = reason,
                outcome = outcome.name,
                jobId = before.jobId,
                ownerId = before.ownerId,
            ),
        )
        log.info(
            "dlq discard messageId={} source={} jobId={} owner={} outcome={} reason={}",
            messageId, before.source, before.jobId, before.ownerId, outcome, reason,
        )
        return DlqAdminUseCase.DiscardResult(outcome, messageId)
    }

    override fun stats(filter: DlqEntryFilter, bucket: String, actor: String, actorKey: String): DlqStats {
        guard(actorKey, AdminRateLimiter.Scope.READ)
        return store.stats(filter, bucket)
    }

    private fun guard(actorKey: String, scope: AdminRateLimiter.Scope) {
        if (!rateLimiter.allow(actorKey, scope)) {
            throw DlqAdminRateLimitedException(scope)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DlqAdminService::class.java)
    }
}
