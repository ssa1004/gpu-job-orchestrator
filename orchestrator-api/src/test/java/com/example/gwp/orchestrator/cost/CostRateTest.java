package com.example.gwp.orchestrator.cost;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostRateTest {

    /** 가장 흔한 단가 — 5,000원 / GPU-hour, 1 GPU, 1시간 → 5,000원. */
    @Test
    void calculate_oneGpuOneHour_yieldsRate() {
        CostRate rate = new CostRate(new BigDecimal("5000"));

        BigDecimal cost = rate.calculate(1, Duration.ofHours(1));

        assertThat(cost).isEqualByComparingTo("5000");
    }

    /** 4 GPU × 30분 = 2 GPU-시간 = 10,000원. */
    @Test
    void calculate_fourGpuHalfHour_yieldsTwoGpuHours() {
        CostRate rate = new CostRate(new BigDecimal("5000"));

        BigDecimal cost = rate.calculate(4, Duration.ofMinutes(30));

        assertThat(cost).isEqualByComparingTo("10000");
    }

    /** 0 GPU 또는 0 runtime → 0원 (dispatch 실패 / 즉시 cancel 등 corner). */
    @Test
    void calculate_zeroRuntime_yieldsZero() {
        CostRate rate = new CostRate(new BigDecimal("5000"));

        assertThat(rate.calculate(8, Duration.ZERO)).isEqualByComparingTo("0");
        assertThat(rate.calculate(0, Duration.ofHours(1))).isEqualByComparingTo("0");
    }

    /** KRW 라 소수점 0 자리 — HALF_UP 반올림. */
    @Test
    void calculate_oddDuration_roundsHalfUp() {
        // 5000 × 1 × (1500ms / 3600000ms) = 5000 × 0.000417 = 2.0833... → 2 (HALF_UP)
        CostRate rate = new CostRate(new BigDecimal("5000"));

        BigDecimal cost = rate.calculate(1, Duration.ofMillis(1500));

        assertThat(cost.scale()).isZero();
        assertThat(cost).isEqualByComparingTo("2");
    }

    @Test
    void constructor_negativeRate_throws() {
        assertThatThrownBy(() -> new CostRate(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void calculate_negativeRuntime_throws() {
        CostRate rate = new CostRate(new BigDecimal("5000"));
        assertThatThrownBy(() -> rate.calculate(1, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
