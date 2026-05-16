package com.example.gwp.orchestrator.api.dto

import com.example.gwp.orchestrator.cost.JobCostRecordRepository.OwnerCostSummary
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * 시간 구간 집계 응답. owner 가 null 이면 전체 합계, 아니면 owner 단독.
 *
 * `totalGpuHours` 는 millis → 시간 변환된 *GPU-시간* 단위 — 청구서에 표시.
 */
@JvmRecord
data class CostSummaryResponse(
    val owner: String?,         // null = 전체
    val from: Instant,
    val to: Instant,
    val jobCount: Long,
    val totalRuntimeMillis: Long,
    val totalGpuMillis: Long,
    val totalGpuHours: BigDecimal,
    val totalCost: BigDecimal,
    val currency: String,
) {
    companion object {

        /** GPU-시간 변환 — 1 시간 = 3,600,000 ms. 소수점 2자리. */
        private val MS_PER_HOUR = BigDecimal("3600000")

        @JvmStatic
        fun from(owner: String?, from: Instant, to: Instant, s: OwnerCostSummary): CostSummaryResponse {
            val hours = BigDecimal(s.totalGpuMillis)
                .divide(MS_PER_HOUR, 2, RoundingMode.HALF_UP)
            return CostSummaryResponse(
                owner, from, to,
                s.jobCount,
                s.totalRuntimeMillis,
                s.totalGpuMillis,
                hours,
                s.totalCost,
                "KRW",
            )
        }
    }
}
