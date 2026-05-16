package com.example.gwp.orchestrator.api

import com.example.gwp.orchestrator.api.dto.CostSummaryResponse
import com.example.gwp.orchestrator.api.dto.JobCostResponse
import com.example.gwp.orchestrator.api.dto.TopSpendersResponse
import com.example.gwp.orchestrator.application.CostQueryService
import com.example.gwp.orchestrator.application.JobAccessControl
import com.example.gwp.orchestrator.domain.AccessDeniedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Cost / FinOps 조회 API.
 *
 * - `GET /api/v1/cost/jobs/{jobId}` — 한 잡의 cost 단건. owner 또는 admin.
 * - `GET /api/v1/cost/owners/{owner}?from=...&to=...` — owner 본인 또는 admin.
 * - `GET /api/v1/cost/summary?from=...&to=...` — admin 전용 (전체 회계 export).
 * - `GET /api/v1/cost/top-spenders?from=...&to=...&limit=N` — admin 전용
 *   (다른 owner 의 사용량 정보 노출).
 *
 * **인증 / 권한**: jobCost / ownerSummary 는 본인 데이터만, summary / topSpenders 는
 * admin 전용. 일반 사용자가 다른 owner 의 사용량 / 비용을 볼 수 없게 차단.
 */
@RestController
@RequestMapping("/api/v1/cost")
@Tag(name = "cost", description = "GPU 사용량 / 비용 (FinOps)")
internal class CostController(
    private val costQueryService: CostQueryService,
    private val jobAccessControl: JobAccessControl,
) {

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "한 잡의 cost record (종착 후 1건)")
    open fun jobCost(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable jobId: UUID,
    ): ResponseEntity<JobCostResponse> {
        val caller = Caller.from(jwt)
        // ownership 검증 — owner 가 아니면 AccessDeniedException, 잡 자체가 없으면 JobNotFound.
        jobAccessControl.getOwned(jobId, caller.owner, caller.isAdmin)
        return costQueryService.findByJobId(jobId)
            .map { JobCostResponse.from(it) }
            .map { ResponseEntity.ok(it) }
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "cost record not found") }
    }

    @GetMapping("/owners/{owner}")
    @Operation(summary = "owner 의 시간 구간 cost 합계 (월별 청구서 기반) — owner 본인 또는 admin")
    open fun ownerSummary(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable owner: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant,
    ): ResponseEntity<CostSummaryResponse> {
        val caller = Caller.from(jwt)
        // 비교 순서를 owner.equals(caller.owner) 로 — caller.owner 가 null 일 가능성은
        // Caller.from 에서 차단했지만, 방어적으로 path variable 쪽을 좌변에 두어 NPE 면역.
        if (!caller.isAdmin && owner != caller.owner) {
            throw AccessDeniedException(null, caller.owner)
        }
        val summary = costQueryService.summaryForOwner(owner, from, to)
        return ResponseEntity.ok(CostSummaryResponse.from(owner, from, to, summary))
    }

    @GetMapping("/summary")
    @Operation(summary = "전체 시간 구간 cost 합계 (회계 / 빌링 export — admin 전용)")
    open fun totalSummary(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant,
    ): ResponseEntity<CostSummaryResponse> {
        val caller = Caller.from(jwt)
        if (!caller.isAdmin) {
            throw AccessDeniedException(null, caller.owner)
        }
        val summary = costQueryService.summaryAll(from, to)
        return ResponseEntity.ok(CostSummaryResponse.from(null, from, to, summary))
    }

    @GetMapping("/top-spenders")
    @Operation(summary = "Top spender ranking — 운영 dashboard (admin 전용)")
    open fun topSpenders(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant,
        @RequestParam(defaultValue = "10") limit: Int,
    ): ResponseEntity<TopSpendersResponse> {
        val caller = Caller.from(jwt)
        if (!caller.isAdmin) {
            throw AccessDeniedException(null, caller.owner)
        }
        val rows = costQueryService.topSpenders(from, to, limit)
        return ResponseEntity.ok(TopSpendersResponse.from(from, to, rows))
    }
}
