package com.example.gwp.orchestrator.api

import com.example.gwp.orchestrator.api.dto.PreemptionHistoryResponse
import com.example.gwp.orchestrator.application.JobAccessControl
import com.example.gwp.orchestrator.domain.AccessDeniedException
import com.example.gwp.orchestrator.domain.PreemptionHistoryRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Preemption 이력 조회 — 잡 단위는 owner 또는 admin, 전체 timeline 은 admin 전용.
 *
 * - `GET /api/v1/jobs/{id}/preemption-history` — 한 잡이 죽었던 이력 (보통 1건).
 *   호출자가 잡의 owner 또는 admin 일 때만 허용 (다른 사용자의 preempt 이력은
 *   빌링 / 운영 정보를 노출).
 * - `GET /api/v1/preemption-history?limit=N` — 모든 사용자의 최근 preemption
 *   timeline. admin role 만 허용.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "preemption", description = "GPU 우선순위 기반 양보 (preemption) 이력")
internal class PreemptionController(
    private val history: PreemptionHistoryRepository,
    private val jobAccessControl: JobAccessControl,
) {

    @GetMapping("/jobs/{jobId}/preemption-history")
    @Operation(summary = "한 잡이 다른 잡에게 GPU 를 양보한 이력 (victim 시점)")
    open fun jobHistory(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable jobId: UUID,
    ): ResponseEntity<PreemptionHistoryResponse> {
        val caller = Caller.from(jwt)
        // ownership 검증 — 다른 사용자의 잡 preempt 이력은 빌링 / 운영 정보 노출이라 차단.
        // getOwned 는 잡이 없으면 JobNotFoundException, owner 가 아니면 AccessDeniedException.
        jobAccessControl.getOwned(jobId, caller.owner, caller.isAdmin)
        val entries = history.findByVictimJobIdOrderByPreemptedAtDesc(jobId)
        return ResponseEntity.ok(PreemptionHistoryResponse.from(entries))
    }

    @GetMapping("/preemption-history")
    @Operation(summary = "최근 preemption 이벤트 timeline (운영자 전용)")
    open fun recent(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): ResponseEntity<PreemptionHistoryResponse> {
        val caller = Caller.from(jwt)
        if (!caller.isAdmin) {
            throw AccessDeniedException(null, caller.owner)
        }
        require(limit > 0) { "limit must be positive" }
        val bounded = minOf(limit, MAX_RECENT_LIMIT)
        val entries = history.findAllByOrderByPreemptedAtDesc(PageRequest.of(0, bounded))
        return ResponseEntity.ok(PreemptionHistoryResponse.from(entries))
    }

    companion object {
        /** `/preemption-history` 의 page 크기 안전 상한 — admin 이라도 무한 limit 으로
         *  서버 메모리 / 응답 크기를 폭증시키지 않게. 이 이상 보고 싶으면 후속 페이지 / cursor
         *  pagination (다음 페이지 토큰을 응답에 실어 주는 방식) 을 도입. */
        private const val MAX_RECENT_LIMIT = 200
    }
}
