package com.example.gwp.orchestrator.api;

import com.example.gwp.orchestrator.api.dto.CostSummaryResponse;
import com.example.gwp.orchestrator.api.dto.JobCostResponse;
import com.example.gwp.orchestrator.api.dto.TopSpendersResponse;
import com.example.gwp.orchestrator.application.CostQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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
 *   <li>{@code GET /api/v1/cost/jobs/{jobId}} — 한 잡의 cost 단건</li>
 *   <li>{@code GET /api/v1/cost/owners/{owner}?from=...&to=...} — owner 의 시간 구간 합계</li>
 *   <li>{@code GET /api/v1/cost/summary?from=...&to=...} — 전체 합계</li>
 *   <li>{@code GET /api/v1/cost/top-spenders?from=...&to=...&limit=N} — 운영 dashboard</li>
 * </ul>
 *
 * <p><b>인증 / 권한</b>: 본 ADR 의 경계 — 일반적으로 owner 본인 + 운영자 ROLE 만 허용해야 함.
 * Spring Security 의 {@code @PreAuthorize} 는 보안 ADR (별도) 에서 다룸.</p>
 */
@RestController
@RequestMapping("/api/v1/cost")
@RequiredArgsConstructor
@Tag(name = "cost", description = "GPU 사용량 / 비용 (FinOps)")
class CostController {

    private final CostQueryService costQueryService;

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "한 잡의 cost record (종착 후 1건)")
    ResponseEntity<JobCostResponse> jobCost(@PathVariable UUID jobId) {
        return costQueryService.findByJobId(jobId)
                .map(JobCostResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "cost record not found"));
    }

    @GetMapping("/owners/{owner}")
    @Operation(summary = "owner 의 시간 구간 cost 합계 (월별 청구서 기반)")
    ResponseEntity<CostSummaryResponse> ownerSummary(
            @PathVariable String owner,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        var summary = costQueryService.summaryForOwner(owner, from, to);
        return ResponseEntity.ok(CostSummaryResponse.from(owner, from, to, summary));
    }

    @GetMapping("/summary")
    @Operation(summary = "전체 시간 구간 cost 합계 (회계 / 빌링 export)")
    ResponseEntity<CostSummaryResponse> totalSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        var summary = costQueryService.summaryAll(from, to);
        return ResponseEntity.ok(CostSummaryResponse.from(null, from, to, summary));
    }

    @GetMapping("/top-spenders")
    @Operation(summary = "Top spender ranking — 운영 dashboard")
    ResponseEntity<TopSpendersResponse> topSpenders(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "10") int limit
    ) {
        var rows = costQueryService.topSpenders(from, to, limit);
        return ResponseEntity.ok(TopSpendersResponse.from(from, to, rows));
    }
}
