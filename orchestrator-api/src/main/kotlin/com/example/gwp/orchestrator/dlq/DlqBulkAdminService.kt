package com.example.gwp.orchestrator.dlq

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executor

/**
 * bulk replay / discard 의 application service.
 *
 * - **dry-run 강제** (market ADR-0028 패턴): controller 단에서 `confirm=true` 가
 *   없는 호출이 자동으로 dry-run 으로 전환됨. 이 service 의 [bulkReplay] /
 *   [bulkDiscard] 는 confirm 인자를 받아 그대로 처리.
 * - **source 필수**: 한 번에 한 saga 단계만 — 같이 묶을 경우 멱등성 / 실패 의미가
 *   섞여 추적이 불가능해진다.
 * - **비동기 실행**: dry-run 은 sync (즉시 응답), 실제 실행은 async — controller 는
 *   PENDING / RUNNING 상태의 job 을 즉시 반환하고 콘솔이 폴링한다.
 *   bulk 의 size 가 클 때 HTTP 요청 한 개가 timeout 으로 끊기지 않게.
 *
 * `@Service` 클래스 — `plugin.spring` 이 자동 open.
 */
@Service
class DlqBulkAdminService(
    private val store: DlqMessageStore,
    private val rateLimiter: AdminRateLimiter,
    private val auditLog: DlqAuditLog,
    private val bulkJobRepository: DlqBulkJobRepository,
    private val clock: Clock,
    @Qualifier("dlqBulkExecutor") private val executor: Executor,
) : DlqBulkAdminUseCase {

    override fun bulkReplay(
        filter: DlqEntryFilter,
        confirm: Boolean,
        actor: String,
        actorKey: String,
        reason: String?,
    ): DlqBulkJob = run(
        action = DlqBulkJob.Action.REPLAY,
        filter = filter,
        confirm = confirm,
        actor = actor,
        actorKey = actorKey,
        reason = reason,
    )

    override fun bulkDiscard(
        filter: DlqEntryFilter,
        confirm: Boolean,
        actor: String,
        actorKey: String,
        reason: String?,
    ): DlqBulkJob = run(
        action = DlqBulkJob.Action.DISCARD,
        filter = filter,
        confirm = confirm,
        actor = actor,
        actorKey = actorKey,
        reason = reason,
    )

    override fun findJob(jobId: String): DlqBulkJob? = bulkJobRepository.findById(jobId)

    /**
     * 공통 entry point — rate-limit guard / source 검증 / dry-run 분기 / 비동기 실행.
     */
    private fun run(
        action: DlqBulkJob.Action,
        filter: DlqEntryFilter,
        confirm: Boolean,
        actor: String,
        actorKey: String,
        reason: String?,
    ): DlqBulkJob {
        if (!rateLimiter.allow(actorKey, AdminRateLimiter.Scope.BULK)) {
            throw DlqAdminRateLimitedException(AdminRateLimiter.Scope.BULK)
        }
        val source = filter.source
            ?: throw DlqAdminBadRequestException("bulk operations require source")
        val jobId = "bulk-" + UUID.randomUUID()
        val now = clock.instant()
        val dryRun = !confirm

        // 1. dry-run 또는 confirm 모두 *매칭 결과는 sync 로 계산* — 콘솔이 항상
        //    matched 카운트를 즉시 본다 (운영자가 yes/no 결정 가능).
        val matched = countMatched(filter)
        val initial = DlqBulkJob(
            jobId = jobId,
            action = action,
            source = source,
            filter = filter,
            dryRun = dryRun,
            status = if (dryRun) DlqBulkJob.Status.COMPLETED else DlqBulkJob.Status.PENDING,
            result = DlqBulkResult(
                matched = matched,
                succeeded = 0,
                failed = 0,
                skipped = 0,
                startedAt = now,
                finishedAt = if (dryRun) now else null,
            ),
            createdAt = now,
            actor = actor,
            reason = reason,
        )
        bulkJobRepository.save(initial)
        auditLog.log(
            DlqAuditLog.Entry(
                action = if (action == DlqBulkJob.Action.REPLAY) {
                    DlqAuditLog.Action.DLQ_BULK_REPLAY
                } else {
                    DlqAuditLog.Action.DLQ_BULK_DISCARD
                },
                actor = actor,
                target = "filter[source=$source,topic=${filter.topic},errorType=${filter.errorType}]",
                reason = reason,
                outcome = "${initial.status}/dryRun=$dryRun/matched=$matched",
            ),
        )

        if (dryRun) {
            log.info(
                "dlq bulk {} dry-run jobId={} source={} matched={} actor={}",
                action, jobId, source, matched, actor,
            )
            return initial
        }

        // 2. confirm=true — 실제 실행은 별 executor 에서 진행.
        executor.execute { execute(jobId, action, filter) }
        log.info(
            "dlq bulk {} scheduled jobId={} source={} matched={} actor={}",
            action, jobId, source, matched, actor,
        )
        return initial
    }

    private fun countMatched(filter: DlqEntryFilter): Long {
        var total = 0L
        var cursor: String? = filter.cursor
        // 운영 구현 (Kafka admin topic / DB) 은 count 만 별 query 가 가능. 여기서는
        // 동일 port 만 쓰는 generic 구현 — 페이지를 끝까지 돈다. 일반적 bulk 크기는
        // 수십~수천 건이라 큰 비용 X.
        do {
            val page = store.list(filter.copy(cursor = cursor))
            total += page.items.size
            cursor = page.nextCursor
        } while (cursor != null)
        return total
    }

    private fun execute(jobId: String, action: DlqBulkJob.Action, filter: DlqEntryFilter) {
        val started = clock.instant()
        var succeeded = 0L
        var failed = 0L
        var skipped = 0L
        val matched: Long = try {
            var cursor: String? = filter.cursor
            var total = 0L
            do {
                val page = store.list(filter.copy(cursor = cursor))
                for (msg in page.items) {
                    total += 1
                    when (action) {
                        DlqBulkJob.Action.REPLAY -> {
                            val key = "$jobId:${msg.id}"
                            when (store.replay(msg.id, key, jobId)) {
                                DlqMessageStore.ReplayOutcome.SUCCESS -> succeeded += 1
                                DlqMessageStore.ReplayOutcome.IGNORED -> skipped += 1
                                DlqMessageStore.ReplayOutcome.FAILED -> failed += 1
                            }
                        }
                        DlqBulkJob.Action.DISCARD -> {
                            when (store.discard(msg.id, "bulk:$jobId", jobId)) {
                                DlqMessageStore.DiscardOutcome.SUCCESS -> succeeded += 1
                                DlqMessageStore.DiscardOutcome.IGNORED -> skipped += 1
                            }
                        }
                    }
                }
                cursor = page.nextCursor
            } while (cursor != null)
            total
        } catch (e: RuntimeException) {
            log.error("dlq bulk {} jobId={} failed mid-flight", action, jobId, e)
            finish(jobId, DlqBulkJob.Status.FAILED, started, 0, succeeded, failed, skipped)
            return
        }
        finish(jobId, DlqBulkJob.Status.COMPLETED, started, matched, succeeded, failed, skipped)
    }

    private fun finish(
        jobId: String,
        status: DlqBulkJob.Status,
        started: java.time.Instant,
        matched: Long,
        succeeded: Long,
        failed: Long,
        skipped: Long,
    ) {
        val current = bulkJobRepository.findById(jobId) ?: return
        val finished = clock.instant()
        bulkJobRepository.save(
            current.copy(
                status = status,
                result = DlqBulkResult(
                    matched = if (matched > 0) matched else current.result.matched,
                    succeeded = succeeded,
                    failed = failed,
                    skipped = skipped,
                    startedAt = started,
                    finishedAt = finished,
                ),
            ),
        )
        log.info(
            "dlq bulk {} jobId={} {} matched={} succeeded={} failed={} skipped={} took={}ms",
            current.action, jobId, status,
            matched, succeeded, failed, skipped,
            java.time.Duration.between(started, finished).toMillis(),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(DlqBulkAdminService::class.java)
    }
}
