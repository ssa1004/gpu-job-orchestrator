package com.example.gwp.orchestrator.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;

/**
 * GPU-hour 당 단가 (KRW) — 모든 cost 계산의 기준.
 *
 * <p><b>왜 GPU-hour 단위?</b>: 실제 클라우드 GPU 가격 책정 표준. K8s + GPU operator 가
 * 보고하는 자원 사용량도 GPU 시간 단위라 매핑 단순. {@code costPerGpuHour × gpuCount × runtimeHours}
 * 로 잡 1개의 비용 결정.</p>
 *
 * <p><b>Snapshot</b>: 가격 정책이 바뀌어도 과거 잡의 cost 가 변하지 않도록, JobCostRecord
 * 가 계산 시점의 rate 를 row 에 그대로 보관한다 (FeeSnapshot / PricingSnapshot 패턴).</p>
 *
 * <p>운영에서 instance type 별 (A100 / H100 / V100 등) 다른 rate 가 필요하면 후속 ADR 에서
 * GpuType enum + Map&lt;GpuType, CostRate&gt; 도입.</p>
 */
public record CostRate(BigDecimal costPerGpuHour) {

    public CostRate {
        Objects.requireNonNull(costPerGpuHour);
        if (costPerGpuHour.signum() < 0) {
            throw new IllegalArgumentException("costPerGpuHour must be non-negative: " + costPerGpuHour);
        }
    }

    /**
     * {@code cost = costPerGpuHour × gpuCount × runtimeHours}.
     *
     * <p>runtime 이 분 단위라도 시간 환산 (60.5분 → 1.008h). 반올림은 KRW 라 소수점 0 (HALF_UP).</p>
     */
    public BigDecimal calculate(int gpuCount, Duration runtime) {
        if (gpuCount < 0) throw new IllegalArgumentException("gpuCount must be non-negative");
        if (runtime.isNegative()) throw new IllegalArgumentException("runtime must be non-negative");
        BigDecimal hours = BigDecimal.valueOf(runtime.toMillis())
                .divide(BigDecimal.valueOf(3_600_000L), 6, RoundingMode.HALF_UP);
        return costPerGpuHour
                .multiply(BigDecimal.valueOf(gpuCount))
                .multiply(hours)
                .setScale(0, RoundingMode.HALF_UP);   // KRW
    }
}
