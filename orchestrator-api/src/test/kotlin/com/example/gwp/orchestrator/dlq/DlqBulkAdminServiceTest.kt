package com.example.gwp.orchestrator.dlq

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor

class DlqBulkAdminServiceTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var store: InMemoryDlqMessageStore
    private lateinit var bulkRepo: InMemoryDlqBulkJobRepository
    private lateinit var audit: RecordingAuditLog
    private lateinit var service: DlqBulkAdminService

    @BeforeEach
    fun setUp() {
        store = InMemoryDlqMessageStore()
        bulkRepo = InMemoryDlqBulkJobRepository()
        audit = RecordingAuditLog()

        // dispatch / outbox 두 source 섞어서 — bulk 의 source 필터링 검증
        repeat(3) {
            store.seed(
                msg("d$it", DlqSource.JOB_DISPATCH, "k8s",
                    ts = Instant.parse("2026-05-15T0$it:00:00Z")),
            )
        }
        store.seed(msg("o1", DlqSource.OUTBOX, "gwp.job"))

        service = DlqBulkAdminService(
            store = store,
            rateLimiter = NoopAdminRateLimiter(),
            auditLog = audit,
            bulkJobRepository = bulkRepo,
            clock = clock,
            executor = SameThreadExecutor,
        )
    }

    @Test
    fun `bulk requires source`() {
        assertThatThrownBy {
            service.bulkReplay(
                DlqEntryFilter(null, null, null, null, null, null, 50),
                confirm = false, actor = "a", actorKey = "k", reason = "r",
            )
        }.isInstanceOf(DlqAdminBadRequestException::class.java)
    }

    @Test
    fun `bulk dry-run counts matched but does not execute`() {
        val job = service.bulkReplay(
            DlqEntryFilter(DlqSource.JOB_DISPATCH, null, null, null, null, null, 50),
            confirm = false, actor = "alice", actorKey = "k", reason = "preview",
        )

        assertThat(job.dryRun).isTrue()
        assertThat(job.status).isEqualTo(DlqBulkJob.Status.COMPLETED)
        assertThat(job.result.matched).isEqualTo(3)
        assertThat(job.result.succeeded).isEqualTo(0)
        // dry-run 도 audit 에 기록되어야 한다 — 어떤 운영자가 어떤 필터로 미리보기했는지 추적
        val entry = audit.entries.single()
        assertThat(entry.action).isEqualTo(DlqAuditLog.Action.DLQ_BULK_REPLAY)
        assertThat(entry.outcome).contains("dryRun=true").contains("matched=3")
    }

    @Test
    fun `bulk confirmed replay invokes store replay per matched message`() {
        val job = service.bulkReplay(
            DlqEntryFilter(DlqSource.JOB_DISPATCH, null, null, null, null, null, 50),
            confirm = true, actor = "alice", actorKey = "k", reason = "K8s recovered",
        )

        // SameThreadExecutor 라 service 호출이 끝난 시점에 실행이 완료됨.
        val completed = service.findJob(job.jobId)!!
        assertThat(completed.status).isEqualTo(DlqBulkJob.Status.COMPLETED)
        assertThat(completed.result.succeeded).isEqualTo(3)
        assertThat(completed.result.failed).isEqualTo(0)
    }

    @Test
    fun `bulk confirmed discard skips already-discarded (멱등)`() {
        // 외부에서 m1 을 미리 discard — bulk 가 다시 만나도 SKIPPED (실패 아님)
        store.discard("d0", "external discard", "ops")

        val job = service.bulkDiscard(
            DlqEntryFilter(DlqSource.JOB_DISPATCH, null, null, null, null, null, 50),
            confirm = true, actor = "alice", actorKey = "k", reason = "obsolete jobs",
        )

        val completed = service.findJob(job.jobId)!!
        // discard 가 미리 된 d0 는 list 에서 빠져서 matched 가 2건. 두 건 모두 succeeded.
        assertThat(completed.result.matched).isEqualTo(2)
        assertThat(completed.result.succeeded).isEqualTo(2)
    }

    @Test
    fun `bulk source filter ignores other sources`() {
        val job = service.bulkReplay(
            DlqEntryFilter(DlqSource.OUTBOX, null, null, null, null, null, 50),
            confirm = false, actor = "alice", actorKey = "k", reason = "outbox only",
        )
        // outbox 만 매칭 — o1 한 건만
        assertThat(job.result.matched).isEqualTo(1)
        assertThat(job.source).isEqualTo(DlqSource.OUTBOX)
    }

    private fun msg(
        id: String, source: DlqSource, topic: String,
        ts: Instant = Instant.parse("2026-05-15T10:00:00Z"),
    ) = DlqMessage(
        id = id, source = source, topic = topic,
        jobId = "j-$id", ownerId = "team-a", gpuClass = "H100",
        errorType = "OTHER", errorMessage = "x", attempts = 3,
        firstSeenAt = ts, lastSeenAt = ts, payloadPreview = "{}",
    )

    private class RecordingAuditLog : DlqAuditLog {
        val entries = mutableListOf<DlqAuditLog.Entry>()
        override fun log(entry: DlqAuditLog.Entry) {
            entries += entry
        }
    }

    private object SameThreadExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }
}
