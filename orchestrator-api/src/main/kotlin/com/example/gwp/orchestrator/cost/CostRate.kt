package com.example.gwp.orchestrator.cost

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * GPU-hour 당 단가 (KRW) — 모든 cost 계산의 기준.
 *
 * **왜 GPU-hour 단위?**: 실제 클라우드 GPU 가격 책정 표준. K8s + GPU operator 가
 * 보고하는 자원 사용량도 GPU 시간 단위라 매핑 단순. `costPerGpuHour × gpuCount × runtimeHours`
 * 로 잡 1개의 비용 결정.
 *
 * **Snapshot**: 가격 정책이 바뀌어도 과거 잡의 cost 가 변하지 않도록, JobCostRecord
 * 가 계산 시점의 rate 를 row 에 그대로 보관한다 (FeeSnapshot / PricingSnapshot 패턴).
 *
 * 운영에서 instance type 별 (A100 / H100 / V100 등) 다른 rate 가 필요하면 후속 ADR 에서
 * GpuType enum + Map\<GpuType, CostRate\> 도입.
 *
 * Java 호출자 (`rate.costPerGpuHour()`) 무변경 — `@JvmRecord` 가 record 시그니처를 그대로 노출.
 */
@JvmRecord
data class CostRate(val costPerGpuHour: BigDecimal) {

    init {
        if (costPerGpuHour.signum() < 0) {
            throw IllegalArgumentException("costPerGpuHour must be non-negative: $costPerGpuHour")
        }
    }

    /**
     * `cost = costPerGpuHour × gpuCount × runtimeHours`.
     *
     * runtime 이 분 단위라도 시간 환산 (60.5분 → 1.008h). 반올림은 KRW 라 소수점 0 (HALF_UP).
     */
    fun calculate(gpuCount: Int, runtime: Duration): BigDecimal {
        require(gpuCount >= 0) { "gpuCount must be non-negative" }
        require(!runtime.isNegative) { "runtime must be non-negative" }
        val hours = BigDecimal.valueOf(runtime.toMillis())
            .divide(BigDecimal.valueOf(3_600_000L), 6, RoundingMode.HALF_UP)
        return costPerGpuHour
            .multiply(BigDecimal.valueOf(gpuCount.toLong()))
            .multiply(hours)
            .setScale(0, RoundingMode.HALF_UP)   // KRW
    }
}
