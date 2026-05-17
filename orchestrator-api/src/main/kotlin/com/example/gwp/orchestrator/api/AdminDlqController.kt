package com.example.gwp.orchestrator.api

import com.example.gwp.orchestrator.dlq.DlqAdminBadRequestException
import com.example.gwp.orchestrator.dlq.DlqAdminUseCase
import com.example.gwp.orchestrator.dlq.DlqBulkAdminUseCase
import com.example.gwp.orchestrator.dlq.DlqBulkJob
import com.example.gwp.orchestrator.dlq.DlqEntryFilter
import com.example.gwp.orchestrator.dlq.DlqListPage
import com.example.gwp.orchestrator.dlq.DlqMessage
import com.example.gwp.orchestrator.dlq.DlqMessageNotFoundException
import com.example.gwp.orchestrator.dlq.DlqMessageStore
import com.example.gwp.orchestrator.dlq.DlqSource
import com.example.gwp.orchestrator.dlq.DlqStats
import com.example.gwp.orchestrator.domain.AccessDeniedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * DLQ 콘솔 백엔드 — admin 전용. notification ADR-0015 / billing ADR-0033 / market
 * ADR-0028 의 패턴 확산. 본 ADR-0026 참고.
 *
 * 8 endpoints:
 *  - list / detail
 *  - replay (idempotency) / discard (audit + reason)
 *  - bulk-replay / bulk-discard (dry-run 강제 — `confirm=true` 없으면 매칭만)
 *  - bulk-jobs/{jobId} 폴링
 *  - stats
 *
 * **권한**: SecurityConfig (JWT 모드) 에서 `@PreAuthorize` 가 `ROLE_admin` 강제.
 * Permissive 모드에서는 SecurityContext 가 anonymous 라 PreAuthorize 가 비활성, 대신
 * 컨트롤러 본문이 [Caller.isAdmin] 검사로 보강 — 두 방어선.
 *
 * **rate limit**: scope 별 (READ / WRITE / BULK) — service 단의 RateLimiter port 가
 * 통과시키지 않으면 429.
 */
@RestController
@RequestMapping("/api/v1/admin/dlq")
@Tag(name = "admin-dlq", description = "DLQ 관리 콘솔 (admin 전용)")
internal class AdminDlqController(
    private val dlqAdmin: DlqAdminUseCase,
    private val dlqBulkAdmin: DlqBulkAdminUseCase,
) {

    @GetMapping
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "DLQ 메시지 목록 (cursor pagination, admin 전용)")
    open fun list(
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
        @RequestParam(required = false) source: DlqSource?,
        @RequestParam(required = false) topic: String?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(required = false) errorType: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<DlqListPage> {
        val caller = requireAdmin(jwt)
        val filter = DlqEntryFilter(source, topic, from, to, errorType, cursor, size.coerceIn(1, MAX_SIZE))
        return ResponseEntity.ok(dlqAdmin.list(filter, caller.owner, actorKey(caller, request)))
    }

    @GetMapping("/{messageId}")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "DLQ 메시지 단건 (전체 payload, admin 전용)")
    open fun detail(
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
        @PathVariable messageId: String,
    ): ResponseEntity<DlqMessage> {
        val caller = requireAdmin(jwt)
        val message = dlqAdmin.detail(messageId, caller.owner, actorKey(caller, request))
            ?: throw DlqMessageNotFoundException(messageId)
        return ResponseEntity.ok(message)
    }

    @PostMapping("/{messageId}/replay")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "단건 replay (idempotency-key 헤더 필수)")
    open fun replay(
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
        @PathVariable messageId: String,
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<DlqAdminUseCase.ReplayResult> {
        val caller = requireAdmin(jwt)
        val key = idempotencyKey?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val result = dlqAdmin.replay(messageId, key, caller.owner, actorKey(caller, request))
        val status = when (result.outcome) {
            DlqMessageStore.ReplayOutcome.SUCCESS -> HttpStatus.ACCEPTED
            DlqMessageStore.ReplayOutcome.IGNORED -> HttpStatus.OK
            DlqMessageStore.ReplayOutcome.FAILED -> HttpStatus.UNPROCESSABLE_ENTITY
        }
        return ResponseEntity.status(status).body(result)
    }

    @PostMapping("/{messageId}/discard")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "단건 discard (사유 필수, audit 기록)")
    open fun discard(
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
        @PathVariable messageId: String,
        @RequestBody body: DiscardRequest,
    ): ResponseEntity<DlqAdminUseCase.DiscardResult> {
        val caller = requireAdmin(jwt)
        if (body.reason.isBlank()) {
            throw DlqAdminBadRequestException("reason must not be blank")
        }
        val result = dlqAdmin.discard(messageId, body.reason, caller.owner, actorKey(caller, request))
        return ResponseEntity.ok(result)
    }

    @PostMapping("/bulk-replay")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "bulk replay (dry-run 강제 — confirm=true 없으면 매칭만)")
    open fun bulkReplay(
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
        @RequestParam(defaultValue = "false") confirm: Boolean,
        @RequestBody body: BulkRequest,
    ): ResponseEntity<DlqBulkJob> {
        val caller = requireAdmin(jwt)
        val filter = body.toFilter()
        val job = dlqBulkAdmin.bulkReplay(filter, confirm, caller.owner, actorKey(caller, request), body.reason)
        return ResponseEntity.accepted().body(job)
    }

    @PostMapping("/bulk-discard")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "bulk discard (dry-run 강제 — confirm=true 없으면 매칭만)")
    open fun bulkDiscard(
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
        @RequestParam(defaultValue = "false") confirm: Boolean,
        @RequestBody body: BulkRequest,
    ): ResponseEntity<DlqBulkJob> {
        val caller = requireAdmin(jwt)
        if (body.reason.isNullOrBlank() && confirm) {
            throw DlqAdminBadRequestException("reason is required for confirmed bulk discard")
        }
        val filter = body.toFilter()
        val job = dlqBulkAdmin.bulkDiscard(filter, confirm, caller.owner, actorKey(caller, request), body.reason)
        return ResponseEntity.accepted().body(job)
    }

    @GetMapping("/bulk-jobs/{jobId}")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "bulk job 진행 상태 폴링")
    open fun bulkJob(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable jobId: String,
    ): ResponseEntity<DlqBulkJob> {
        requireAdmin(jwt)
        val job = dlqBulkAdmin.findJob(jobId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(job)
    }

    @GetMapping("/stats")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "DLQ 통계 (source / topic / owner / gpuClass / errorType / 시간 bucket)")
    open fun stats(
        @AuthenticationPrincipal jwt: Jwt?,
        request: HttpServletRequest,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(required = false) source: DlqSource?,
        @RequestParam(required = false) topic: String?,
        @RequestParam(required = false) errorType: String?,
        @RequestParam(defaultValue = "PT1H") bucket: String,
    ): ResponseEntity<DlqStats> {
        val caller = requireAdmin(jwt)
        val filter = DlqEntryFilter(source, topic, from, to, errorType, null, 0)
        return ResponseEntity.ok(dlqAdmin.stats(filter, bucket, caller.owner, actorKey(caller, request)))
    }

    /**
     * JWT 모드의 PreAuthorize 와 *별도로* permissive 모드에서도 admin 권한을 검증.
     * 두 모드 모두에서 동일하게 admin 만 통과.
     */
    private fun requireAdmin(jwt: Jwt?): Caller {
        val caller = Caller.from(jwt)
        if (!caller.isAdmin) {
            throw AccessDeniedException(null, caller.owner)
        }
        return caller
    }

    /** rate-limit 키 — admin sub + IP 조합. 같은 admin 이 여러 IP 에서 동시 운영해도 별 카운터. */
    private fun actorKey(caller: Caller, request: HttpServletRequest): String {
        val ip = request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
            ?: "unknown"
        return "${caller.owner}|$ip"
    }

    data class DiscardRequest(val reason: String)

    /**
     * bulk 요청 body. source 필수 (한 번에 한 saga 단계 — market ADR-0028 패턴).
     */
    data class BulkRequest(
        val source: DlqSource?,
        val topic: String?,
        val from: Instant?,
        val to: Instant?,
        val errorType: String?,
        val reason: String?,
    ) {
        fun toFilter(): DlqEntryFilter {
            if (source == null) {
                throw DlqAdminBadRequestException("source is required for bulk operations")
            }
            return DlqEntryFilter(
                source = source,
                topic = topic,
                from = from,
                to = to,
                errorType = errorType,
                cursor = null,
                size = MAX_SIZE,
            )
        }
    }

    companion object {
        private const val MAX_SIZE = 100
    }
}
