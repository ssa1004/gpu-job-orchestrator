package com.example.gwp.orchestrator.api.dto

import com.example.gwp.orchestrator.application.CostQueryService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Top spender 응답 — owner 별 cost DESC.
 */
@JvmRecord
data class TopSpendersResponse(
    val from: Instant,
    val to: Instant,
    val items: List<Entry>,
    val currency: String,
) {
    @JvmRecord
    data class Entry(
        val owner: String,
        val jobCount: Long,
        val totalRuntimeMillis: Long,
        val totalGpuMillis: Long,
        val totalGpuHours: BigDecimal,
        val totalCost: BigDecimal,
    )

    companion object {

        private val MS_PER_HOUR = BigDecimal("3600000")

        @JvmStatic
        fun from(from: Instant, to: Instant, rows: List<CostQueryService.TopSpender>): TopSpendersResponse {
            val items = rows.map { r ->
                val hours = BigDecimal(r.totalGpuMillis)
                    .divide(MS_PER_HOUR, 2, RoundingMode.HALF_UP)
                Entry(
                    r.owner,
                    r.jobCount,
                    r.totalRuntimeMillis,
                    r.totalGpuMillis,
                    hours,
                    r.totalCost,
                )
            }
            return TopSpendersResponse(from, to, items, "KRW")
        }
    }
}
