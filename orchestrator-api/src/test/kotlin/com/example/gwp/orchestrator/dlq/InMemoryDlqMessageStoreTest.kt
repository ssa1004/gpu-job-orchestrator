package com.example.gwp.orchestrator.dlq

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryDlqMessageStoreTest {

    private lateinit var store: InMemoryDlqMessageStore

    @BeforeEach
    fun setUp() {
        store = InMemoryDlqMessageStore()
    }

    @Test
    fun `list filters by source and topic`() {
        store.seed(message("m1", DlqSource.CALLBACK, "internal/jobs/x/status", owner = "team-a"))
        store.seed(message("m2", DlqSource.OUTBOX, "gwp.job.completed", owner = "team-b"))
        store.seed(message("m3", DlqSource.CALLBACK, "internal/jobs/y/status", owner = "team-a"))

        val page = store.list(
            DlqEntryFilter(DlqSource.CALLBACK, null, null, null, null, null, 50),
        )

        assertThat(page.items).hasSize(2)
        assertThat(page.items.map { it.id }).containsExactlyInAnyOrder("m1", "m3")
    }

    @Test
    fun `list uses cursor pagination — same lastSeen tie-breaks by id`() {
        val ts = Instant.parse("2026-05-15T00:00:00Z")
        store.seed(message("a", DlqSource.CALLBACK, "t", lastSeen = ts))
        store.seed(message("b", DlqSource.CALLBACK, "t", lastSeen = ts))
        store.seed(message("c", DlqSource.CALLBACK, "t", lastSeen = ts))

        val first = store.list(
            DlqEntryFilter(DlqSource.CALLBACK, null, null, null, null, null, 2),
        )
        assertThat(first.items.map { it.id }).containsExactly("a", "b")
        assertThat(first.nextCursor).isNotNull()

        val second = store.list(
            DlqEntryFilter(DlqSource.CALLBACK, null, null, null, null, first.nextCursor, 2),
        )
        assertThat(second.items.map { it.id }).containsExactly("c")
        assertThat(second.nextCursor).isNull()
    }

    @Test
    fun `replay is idempotent under same idempotency key`() {
        store.seed(message("m1", DlqSource.JOB_DISPATCH, "k8s"))

        val first = store.replay("m1", "key-1", actor = "admin")
        val second = store.replay("m1", "key-1", actor = "admin")

        assertThat(first).isEqualTo(DlqMessageStore.ReplayOutcome.SUCCESS)
        assertThat(second).isEqualTo(DlqMessageStore.ReplayOutcome.IGNORED)
    }

    @Test
    fun `replay returns IGNORED for unknown id (멱등)`() {
        val outcome = store.replay("missing", "any", actor = "admin")
        assertThat(outcome).isEqualTo(DlqMessageStore.ReplayOutcome.IGNORED)
    }

    @Test
    fun `discard hides messages from subsequent list and detail`() {
        store.seed(message("m1", DlqSource.OUTBOX, "gwp.job"))

        val outcome = store.discard("m1", reason = "stale", actor = "admin")
        assertThat(outcome).isEqualTo(DlqMessageStore.DiscardOutcome.SUCCESS)

        assertThat(store.findById("m1")).isNull()
        assertThat(
            store.list(DlqEntryFilter(null, null, null, null, null, null, 50)).items,
        ).isEmpty()
    }

    @Test
    fun `stats aggregates by source byOwner byGpuClass buckets`() {
        val from = Instant.parse("2026-05-15T00:00:00Z")
        val to = Instant.parse("2026-05-15T03:00:00Z")
        store.seed(
            message(
                "m1", DlqSource.JOB_DISPATCH, "k8s",
                owner = "team-a", gpuClass = "H100",
                lastSeen = Instant.parse("2026-05-15T00:30:00Z"),
            ),
        )
        store.seed(
            message(
                "m2", DlqSource.JOB_DISPATCH, "k8s",
                owner = "team-a", gpuClass = "A100",
                lastSeen = Instant.parse("2026-05-15T01:30:00Z"),
            ),
        )
        store.seed(
            message(
                "m3", DlqSource.CALLBACK, "internal/jobs",
                owner = "team-b", gpuClass = "H100",
                lastSeen = Instant.parse("2026-05-15T02:15:00Z"),
            ),
        )

        val stats = store.stats(
            DlqEntryFilter(null, null, from, to, null, null, 0),
            bucket = "PT1H",
        )

        assertThat(stats.total).isEqualTo(3)
        assertThat(stats.bySource).containsEntry(DlqSource.JOB_DISPATCH, 2L)
        assertThat(stats.bySource).containsEntry(DlqSource.CALLBACK, 1L)
        assertThat(stats.byOwner).containsEntry("team-a", 2L).containsEntry("team-b", 1L)
        assertThat(stats.byGpuClass).containsEntry("H100", 2L).containsEntry("A100", 1L)
        // 3 bucket * 1h, each with 1 message
        assertThat(stats.buckets).hasSize(3)
        assertThat(stats.buckets.map { it.count }).allSatisfy { c -> assertThat(c).isEqualTo(1L) }
    }

    @Test
    fun `stats with invalid bucket raises BadRequest`() {
        assertThatThrownBy {
            store.stats(
                DlqEntryFilter(null, null, null, null, null, null, 0),
                bucket = "not-a-duration",
            )
        }.isInstanceOf(DlqAdminBadRequestException::class.java)
    }

    @Test
    fun `list cursor invalid raises BadRequest`() {
        store.seed(message("m1", DlqSource.OUTBOX, "t"))
        assertThatThrownBy {
            store.list(DlqEntryFilter(null, null, null, null, null, "###not-base64###", 50))
        }.isInstanceOf(DlqAdminBadRequestException::class.java)
    }

    private fun message(
        id: String,
        source: DlqSource,
        topic: String,
        owner: String? = null,
        gpuClass: String? = null,
        lastSeen: Instant = Instant.parse("2026-05-15T10:00:00Z"),
    ) = DlqMessage(
        id = id,
        source = source,
        topic = topic,
        jobId = null,
        ownerId = owner,
        gpuClass = gpuClass,
        errorType = "OTHER",
        errorMessage = "x",
        attempts = 3,
        firstSeenAt = lastSeen,
        lastSeenAt = lastSeen,
        payloadPreview = "{}",
    )
}
