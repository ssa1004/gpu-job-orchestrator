package com.example.gwp.orchestrator.api.dto;

import com.example.gwp.orchestrator.cost.JobCostRecord;
import com.example.gwp.orchestrator.domain.JobStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 한 잡의 cost 단건 응답.
 *
 * <p>{@code ratePerGpuHour} 는 *기록 시점* 단가 (변경 후에도 박제). {@code computedCost} 는 그 시점
 * 계산값. 클라이언트가 다시 계산할 필요 없음 — 바로 표시 가능.</p>
 */
public record JobCostResponse(
        UUID jobId,
        String owner,
        int gpuCount,
        long runtimeMillis,
        BigDecimal ratePerGpuHour,
        BigDecimal computedCost,
        String currency,
        JobStatus finalStatus,
        Instant jobStartedAt,
        Instant jobFinishedAt,
        Instant recordedAt
) {
    public static JobCostResponse from(JobCostRecord r) {
        return new JobCostResponse(
                r.getJobId(),
                r.getOwner(),
                r.getGpuCount(),
                r.getRuntimeMillis(),
                r.getRatePerGpuHour(),
                r.getComputedCost(),
                r.getCurrency(),
                r.getFinalStatus(),
                r.getJobStartedAt(),
                r.getJobFinishedAt(),
                r.getRecordedAt()
        );
    }
}
