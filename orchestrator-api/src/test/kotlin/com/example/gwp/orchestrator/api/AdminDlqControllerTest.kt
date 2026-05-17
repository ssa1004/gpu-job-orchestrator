package com.example.gwp.orchestrator.api

import com.example.gwp.orchestrator.api.exception.GlobalExceptionHandler
import com.example.gwp.orchestrator.config.PermissiveSecurityConfig
import com.example.gwp.orchestrator.dlq.DlqAdminUseCase
import com.example.gwp.orchestrator.dlq.DlqBulkAdminUseCase
import com.example.gwp.orchestrator.dlq.DlqBulkJob
import com.example.gwp.orchestrator.dlq.DlqBulkResult
import com.example.gwp.orchestrator.dlq.DlqEntryFilter
import com.example.gwp.orchestrator.dlq.DlqListPage
import com.example.gwp.orchestrator.dlq.DlqMessage
import com.example.gwp.orchestrator.dlq.DlqMessageStore
import com.example.gwp.orchestrator.dlq.DlqSource
import com.example.gwp.orchestrator.dlq.DlqStats
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.tracing.Tracer
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant

@WebMvcTest(AdminDlqController::class)
@Import(GlobalExceptionHandler::class, PermissiveSecurityConfig::class)
class AdminDlqControllerTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper

    @MockBean private lateinit var dlqAdmin: DlqAdminUseCase
    @MockBean private lateinit var dlqBulkAdmin: DlqBulkAdminUseCase
    @MockBean private lateinit var tracer: Tracer
    @MockBean private lateinit var clock: Clock

    @Test
    @WithMockUser(username = "alice", roles = ["admin"])
    fun `GET list returns items + nextCursor for admin`() {
        `when`(dlqAdmin.list(anyFilter(), anyString(), anyString())).thenReturn(
            DlqListPage(
                items = listOf(sampleMessage("m1")),
                nextCursor = "cursor-2",
            ),
        )

        mvc.perform(get("/api/v1/admin/dlq?size=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").value("m1"))
            .andExpect(jsonPath("$.nextCursor").value("cursor-2"))
    }

    @Test
    fun `GET list returns 403 for non-admin (Permissive mode)`() {
        mvc.perform(get("/api/v1/admin/dlq"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
    }

    @Test
    @WithMockUser(username = "alice", roles = ["admin"])
    fun `POST replay forwards Idempotency-Key header to service`() {
        `when`(dlqAdmin.replay(anyString(), anyString(), anyString(), anyString())).thenReturn(
            DlqAdminUseCase.ReplayResult(DlqMessageStore.ReplayOutcome.SUCCESS, "m1"),
        )

        mvc.perform(
            post("/api/v1/admin/dlq/m1/replay")
                .header("Idempotency-Key", "my-key"),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.outcome").value("SUCCESS"))

        verify(dlqAdmin).replay(eqString("m1"), eqString("my-key"), anyString(), anyString())
    }

    @Test
    @WithMockUser(username = "alice", roles = ["admin"])
    fun `POST replay returns 200 on IGNORED (멱등)`() {
        `when`(dlqAdmin.replay(anyString(), anyString(), anyString(), anyString())).thenReturn(
            DlqAdminUseCase.ReplayResult(DlqMessageStore.ReplayOutcome.IGNORED, "m1"),
        )
        mvc.perform(post("/api/v1/admin/dlq/m1/replay"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("IGNORED"))
    }

    @Test
    @WithMockUser(username = "alice", roles = ["admin"])
    fun `POST discard requires reason`() {
        mvc.perform(
            post("/api/v1/admin/dlq/m1/discard")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":""}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("DLQ_BAD_REQUEST"))

        verify(dlqAdmin, never())
            .discard(anyString(), anyString(), anyString(), anyString())
    }

    @Test
    @WithMockUser(username = "alice", roles = ["admin"])
    fun `POST bulk-replay without confirm runs dry-run forwarding to service`() {
        val captor = ArgumentCaptor.forClass(java.lang.Boolean::class.java)
        `when`(
            dlqBulkAdmin.bulkReplay(
                anyFilter(),
                captor.capture() as Boolean,
                anyString(),
                anyString(),
                anyVal("preview"),
            ),
        ).thenReturn(sampleBulkJob(dryRun = true))

        mvc.perform(
            post("/api/v1/admin/dlq/bulk-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "source" to "JOB_DISPATCH",
                            "reason" to "preview",
                        ),
                    ),
                ),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.dryRun").value(true))

        // dry-run 기본값 — confirm=false
        org.assertj.core.api.Assertions.assertThat(captor.value).isEqualTo(false)
    }

    @Test
    @WithMockUser(username = "alice", roles = ["admin"])
    fun `POST bulk-discard requires source enum`() {
        mvc.perform(
            post("/api/v1/admin/dlq/bulk-discard")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"obsolete"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("DLQ_BAD_REQUEST"))
    }

    @Test
    @WithMockUser(username = "alice", roles = ["admin"])
    fun `GET stats returns bySource bucket grouping`() {
        `when`(dlqAdmin.stats(anyFilter(), eqString("PT1H"), anyString(), anyString())).thenReturn(
            DlqStats(
                from = Instant.parse("2026-05-15T00:00:00Z"),
                to = Instant.parse("2026-05-15T03:00:00Z"),
                total = 3,
                bySource = mapOf(DlqSource.JOB_DISPATCH to 2L, DlqSource.CALLBACK to 1L),
                byTopic = emptyMap(),
                byErrorType = emptyMap(),
                byOwner = mapOf("team-a" to 2L),
                byGpuClass = mapOf("H100" to 2L, "A100" to 1L),
                buckets = emptyList(),
            ),
        )

        mvc.perform(get("/api/v1/admin/dlq/stats?bucket=PT1H"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.bySource.JOB_DISPATCH").value(2))
            .andExpect(jsonPath("$.byOwner['team-a']").value(2))
            .andExpect(jsonPath("$.byGpuClass.H100").value(2))
    }

    @Test
    @WithMockUser(username = "alice", roles = ["admin"])
    fun `GET bulk-jobs returns 404 when unknown`() {
        `when`(dlqBulkAdmin.findJob("nope")).thenReturn(null)

        mvc.perform(get("/api/v1/admin/dlq/bulk-jobs/nope"))
            .andExpect(status().isNotFound)
    }

    /**
     * Mockito 의 ArgumentMatchers.any() 는 null 을 반환 → Kotlin non-null 파라미터에
     * 전달되면 NPE. 같은 matcher 를 등록하면서 호출자 측에는 적당한 dummy 값을 돌려주는
     * helper. 같은 패턴이 다른 테스트에서 자주 쓰이지만 단위 테스트라 inline.
     */
    private inline fun <reified T : Any> anyVal(default: T): T {
        ArgumentMatchers.any<T>()
        return default
    }

    private fun anyFilter() = anyVal(DlqEntryFilter(null, null, null, null, null, null, 0))

    /** Kotlin non-null String 파라미터에 안전한 eq() — Mockito.eq 가 null 반환하는 문제 우회. */
    private fun eqString(value: String): String {
        ArgumentMatchers.eq(value)
        return value
    }
    private fun anyBulkRequest() = anyVal(
        AdminDlqController.BulkRequest(DlqSource.JOB_DISPATCH, null, null, null, null, null),
    )

    private fun sampleMessage(id: String) = DlqMessage(
        id = id,
        source = DlqSource.CALLBACK,
        topic = "internal/jobs/x/status",
        jobId = "j1",
        ownerId = "team-a",
        gpuClass = "H100",
        errorType = "OTHER",
        errorMessage = "boom",
        attempts = 4,
        firstSeenAt = Instant.parse("2026-05-15T08:00:00Z"),
        lastSeenAt = Instant.parse("2026-05-15T10:00:00Z"),
        payloadPreview = "{}",
    )

    private fun sampleBulkJob(dryRun: Boolean) = DlqBulkJob(
        jobId = "bulk-1",
        action = DlqBulkJob.Action.REPLAY,
        source = DlqSource.JOB_DISPATCH,
        filter = DlqEntryFilter(DlqSource.JOB_DISPATCH, null, null, null, null, null, 100),
        dryRun = dryRun,
        status = DlqBulkJob.Status.COMPLETED,
        result = DlqBulkResult(
            matched = 3, succeeded = 0, failed = 0, skipped = 0,
            startedAt = Instant.parse("2026-05-15T10:00:00Z"),
            finishedAt = Instant.parse("2026-05-15T10:00:00Z"),
        ),
        createdAt = Instant.parse("2026-05-15T10:00:00Z"),
        actor = "alice",
        reason = "preview",
    )
}
