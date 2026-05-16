package com.example.gwp.orchestrator.api.dto

import com.example.gwp.orchestrator.cost.JobCostRecord
import com.example.gwp.orchestrator.domain.JobStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * 한 잡의 cost 단건 응답.
 *
 * `ratePerGpuHour` 는 기록 시점 단가 (이후 단가가 변경돼도 그대로 보관).
 * `computedCost` 는 그 시점 계산값. 클라이언트가 다시 계산할 필요 없이 바로 표시
 * 가능하다.
 */
@JvmRecord
data class JobCostResponse(
    val jobId: UUID,
    val owner: String,
    val gpuCount: Int,
    val runtimeMillis: Long,
    val ratePerGpuHour: BigDecimal,
    val computedCost: BigDecimal,
    val currency: String,
    val finalStatus: JobStatus,
    val jobStartedAt: Instant?,
    val jobFinishedAt: Instant,
    val recordedAt: Instant,
) {
    companion object {
        @JvmStatic
        fun from(r: JobCostRecord): JobCostResponse = JobCostResponse(
            r.jobId,
            r.owner,
            r.gpuCount,
            r.runtimeMillis,
            r.ratePerGpuHour,
            r.computedCost,
            r.currency,
            r.finalStatus,
            r.jobStartedAt,
            r.jobFinishedAt,
            r.recordedAt,
        )
    }
}
