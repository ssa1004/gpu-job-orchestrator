package com.example.gwp.orchestrator.api.dto;

import com.example.gwp.orchestrator.application.CostQueryService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Top spender 응답 — owner 별 cost DESC.
 */
public record TopSpendersResponse(
        Instant from,
        Instant to,
        List<Entry> items,
        String currency
) {
    public record Entry(
            String owner,
            long jobCount,
            long totalRuntimeMillis,
            long totalGpuMillis,
            BigDecimal totalGpuHours,
            BigDecimal totalCost
    ) {}

    private static final BigDecimal MS_PER_HOUR = new BigDecimal("3600000");

    public static TopSpendersResponse from(Instant from, Instant to, List<CostQueryService.TopSpender> rows) {
        List<Entry> items = rows.stream().map(r -> {
            BigDecimal hours = new BigDecimal(r.totalGpuMillis())
                    .divide(MS_PER_HOUR, 2, java.math.RoundingMode.HALF_UP);
            return new Entry(
                    r.owner(),
                    r.jobCount(),
                    r.totalRuntimeMillis(),
                    r.totalGpuMillis(),
                    hours,
                    r.totalCost()
            );
        }).toList();
        return new TopSpendersResponse(from, to, items, "KRW");
    }
}
