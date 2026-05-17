package com.example.gwp.orchestrator.dlq

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class DlqAdminServiceTest {

    private lateinit var store: InMemoryDlqMessageStore
    private lateinit var rateLimiter: ToggleRateLimiter
    private lateinit var audit: CapturingAuditLog
    private lateinit var service: DlqAdminService

    @BeforeEach
    fun setUp() {
        store = InMemoryDlqMessageStore()
        rateLimiter = ToggleRateLimiter()
        audit = CapturingAuditLog()
        service = DlqAdminService(store, rateLimiter, audit)
        store.seed(
            DlqMessage(
                id = "m1",
                source = DlqSource.CALLBACK,
                topic = "internal/jobs/x/status",
                jobId = "j1",
                ownerId = "team-a",
                gpuClass = "H100",
                errorType = "IDP_FORBIDDEN",
                errorMessage = "401",
                attempts = 4,
                firstSeenAt = Instant.parse("2026-05-15T08:00:00Z"),
                lastSeenAt = Instant.parse("2026-05-15T10:00:00Z"),
                payloadPreview = "{...}",
            ),
        )
    }

    @Test
    fun `replay writes DLQ_REPLAY audit with jobId + ownerId`() {
        val result = service.replay("m1", "k", actor = "alice", actorKey = "alice|ip")

        assertThat(result.outcome).isEqualTo(DlqMessageStore.ReplayOutcome.SUCCESS)
        val entry = audit.entries.single()
        assertThat(entry.action).isEqualTo(DlqAuditLog.Action.DLQ_REPLAY)
        assertThat(entry.actor).isEqualTo("alice")
        assertThat(entry.target).isEqualTo("m1")
        assertThat(entry.outcome).isEqualTo("SUCCESS")
        assertThat(entry.jobId).isEqualTo("j1")
        assertThat(entry.ownerId).isEqualTo("team-a")
    }

    @Test
    fun `discard requires non-blank reason`() {
        assertThatThrownBy { service.discard("m1", reason = "", actor = "a", actorKey = "k") }
            .isInstanceOf(DlqAdminBadRequestException::class.java)
    }

    @Test
    fun `replay raises NotFound for missing id`() {
        assertThatThrownBy { service.replay("missing", "k", actor = "a", actorKey = "k") }
            .isInstanceOf(DlqMessageNotFoundException::class.java)
    }

    @Test
    fun `rate limit DENY raises RateLimitedException with the deny scope`() {
        rateLimiter.deny(AdminRateLimiter.Scope.WRITE)
        assertThatThrownBy { service.replay("m1", "k", actor = "a", actorKey = "k") }
            .isInstanceOfSatisfying(DlqAdminRateLimitedException::class.java) {
                assertThat(it.message).contains("WRITE")
            }
    }

    @Test
    fun `list passes through to store using READ scope`() {
        rateLimiter.deny(AdminRateLimiter.Scope.READ)
        assertThatThrownBy {
            service.list(
                DlqEntryFilter(null, null, null, null, null, null, 50),
                actor = "a", actorKey = "k",
            )
        }.isInstanceOfSatisfying(DlqAdminRateLimitedException::class.java) {
            assertThat(it.message).contains("READ")
        }
    }

    @Test
    fun `discard writes DLQ_DISCARD audit with reason preserved`() {
        service.discard("m1", reason = "stale obsolete job", actor = "alice", actorKey = "k")
        val entry = audit.entries.single()
        assertThat(entry.action).isEqualTo(DlqAuditLog.Action.DLQ_DISCARD)
        assertThat(entry.reason).isEqualTo("stale obsolete job")
        assertThat(entry.outcome).isEqualTo("SUCCESS")
    }

    private class ToggleRateLimiter : AdminRateLimiter {
        private val deniedScope = AtomicReference<AdminRateLimiter.Scope?>(null)
        fun deny(scope: AdminRateLimiter.Scope) = deniedScope.set(scope)
        override fun allow(key: String, scope: AdminRateLimiter.Scope): Boolean =
            deniedScope.get() != scope
    }

    private class CapturingAuditLog : DlqAuditLog {
        val entries = mutableListOf<DlqAuditLog.Entry>()
        override fun log(entry: DlqAuditLog.Entry) {
            entries += entry
        }
    }
}
