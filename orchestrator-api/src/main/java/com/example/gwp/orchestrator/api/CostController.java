package com.example.gwp.orchestrator.api;

import com.example.gwp.orchestrator.api.dto.CostSummaryResponse;
import com.example.gwp.orchestrator.api.dto.JobCostResponse;
import com.example.gwp.orchestrator.api.dto.TopSpendersResponse;
import com.example.gwp.orchestrator.application.CostQueryService;
import com.example.gwp.orchestrator.application.JobAccessControl;
import com.example.gwp.orchestrator.domain.AccessDeniedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Cost / FinOps 조회 API.
 *
 * <ul>
 *   <li>{@code GET /api/v1/cost/jobs/{jobId}} — 한 잡의 cost 단건. owner 또는 admin.</li>
 *   <li>{@code GET /api/v1/cost/owners/{owner}?from=...&to=...} — owner 본인 또는 admin.</li>
 *   <li>{@code GET /api/v1/cost/summary?from=...&to=...} — admin 전용 (전체 회계 export).</li>
 *   <li>{@code GET /api/v1/cost/top-spenders?from=...&to=...&limit=N} — admin 전용
 *       (다른 owner 의 사용량 정보 노출).</li>
 * </ul>
 *
 * <p><b>인증 / 권한</b>: jobCost / ownerSummary 는 본인 데이터만, summary / topSpenders 는
 * admin 전용. 일반 사용자가 다른 owner 의 사용량 / 비용을 볼 수 없게 차단.</p>
 */
@RestController
@RequestMapping("/api/v1/cost")
@RequiredArgsConstructor
@Tag(name = "cost", description = "GPU 사용량 / 비용 (FinOps)")
class CostController {

    private final CostQueryService costQueryService;
    private final JobAccessControl jobAccessControl;

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "한 잡의 cost record (종착 후 1건)")
    ResponseEntity<JobCostResponse> jobCost(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId
    ) {
        var caller = Caller.from(jwt);
        // ownership 검증 — owner 가 아니면 AccessDeniedException, 잡 자체가 없으면 JobNotFound.
        jobAccessControl.getOwned(jobId, caller.owner(), caller.isAdmin());
        return costQueryService.findByJobId(jobId)
                .map(JobCostResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "cost record not found"));
    }

    @GetMapping("/owners/{owner}")
    @Operation(summary = "owner 의 시간 구간 cost 합계 (월별 청구서 기반) — owner 본인 또는 admin")
    ResponseEntity<CostSummaryResponse> ownerSummary(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String owner,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        var caller = Caller.from(jwt);
        // 비교 순서를 owner.equals(caller.owner()) 로 — caller.owner() 가 null 일 가능성은
        // Caller.from 에서 차단했지만, 방어적으로 path variable 쪽을 좌변에 두어 NPE 면역.
        if (!caller.isAdmin() && !owner.equals(caller.owner())) {
            throw new AccessDeniedException(null, caller.owner());
        }
        var summary = costQueryService.summaryForOwner(owner, from, to);
        return ResponseEntity.ok(CostSummaryResponse.from(owner, from, to, summary));
    }

    @GetMapping("/summary")
    @Operation(summary = "전체 시간 구간 cost 합계 (회계 / 빌링 export — admin 전용)")
    ResponseEntity<CostSummaryResponse> totalSummary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        var caller = Caller.from(jwt);
        if (!caller.isAdmin()) {
            throw new AccessDeniedException(null, caller.owner());
        }
        var summary = costQueryService.summaryAll(from, to);
        return ResponseEntity.ok(CostSummaryResponse.from(null, from, to, summary));
    }

    @GetMapping("/top-spenders")
    @Operation(summary = "Top spender ranking — 운영 dashboard (admin 전용)")
    ResponseEntity<TopSpendersResponse> topSpenders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "10") int limit
    ) {
        var caller = Caller.from(jwt);
        if (!caller.isAdmin()) {
            throw new AccessDeniedException(null, caller.owner());
        }
        var rows = costQueryService.topSpenders(from, to, limit);
        return ResponseEntity.ok(TopSpendersResponse.from(from, to, rows));
    }
}
