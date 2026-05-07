package com.example.gwp.orchestrator.api.dto;

import com.example.gwp.orchestrator.cost.JobCostRecordRepository.OwnerCostSummary;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 시간 구간 집계 응답. owner 가 null 이면 전체 합계, 아니면 owner 단독.
 *
 * <p>{@code totalGpuHours} 는 millis → 시간 변환된 *GPU-시간* 단위 — 청구서에 표시.</p>
 */
public record CostSummaryResponse(
        String owner,         // null = 전체
        Instant from,
        Instant to,
        long jobCount,
        long totalRuntimeMillis,
        long totalGpuMillis,
        BigDecimal totalGpuHours,
        BigDecimal totalCost,
        String currency
) {
    /** GPU-시간 변환 — 1 시간 = 3,600,000 ms. 소수점 2자리. */
    private static final BigDecimal MS_PER_HOUR = new BigDecimal("3600000");

    public static CostSummaryResponse from(String owner, Instant from, Instant to, OwnerCostSummary s) {
        BigDecimal hours = new BigDecimal(s.totalGpuMillis())
                .divide(MS_PER_HOUR, 2, java.math.RoundingMode.HALF_UP);
        return new CostSummaryResponse(
                owner, from, to,
                s.jobCount(),
                s.totalRuntimeMillis(),
                s.totalGpuMillis(),
                hours,
                s.totalCost(),
                "KRW"
        );
    }
}
