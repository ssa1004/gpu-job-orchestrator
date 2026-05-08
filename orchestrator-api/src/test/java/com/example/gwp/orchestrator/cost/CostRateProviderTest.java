package com.example.gwp.orchestrator.cost;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시작 시점에 단가 검증이 끝나야 — 잘못된 값 (음수 / 숫자 아님) 이면 컨테이너 시작 자체가 실패.
 * 첫 잡 종착할 때까지 미발견되는 시나리오를 회귀 방지.
 */
class CostRateProviderTest {

    @Test
    void postConstruct_validRate_cachesAndReturns() {
        CostRateProvider provider = providerWith("7500");
        provider.validateAndCache();

        CostRate rate = provider.current();

        assertThat(rate.costPerGpuHour()).isEqualByComparingTo("7500");
    }

    /** 같은 인스턴스를 두 번 호출해도 매번 새 BigDecimal 만들지 않고 동일 인스턴스 반환. */
    @Test
    void current_isCachedAfterStartup() {
        CostRateProvider provider = providerWith("5000");
        provider.validateAndCache();

        CostRate first = provider.current();
        CostRate second = provider.current();

        assertThat(first).isSameAs(second);
    }

    @Test
    void postConstruct_nonNumeric_failsFast() {
        CostRateProvider provider = providerWith("abc");

        assertThatThrownBy(provider::validateAndCache)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("abc")
                .hasMessageContaining("non-negative decimal");
    }

    @Test
    void postConstruct_negativeRate_failsFast() {
        CostRateProvider provider = providerWith("-1000");

        // CostRate 생성자에서 IllegalArgumentException 던짐 — Spring 이 @PostConstruct 실패로 startup abort.
        assertThatThrownBy(provider::validateAndCache)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    /** 0 은 허용 — internal/dev 환경에서 비용을 끄는 용도. */
    @Test
    void postConstruct_zeroRate_allowed() {
        CostRateProvider provider = providerWith("0");
        provider.validateAndCache();

        assertThat(provider.current().costPerGpuHour()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static CostRateProvider providerWith(String configured) {
        CostRateProvider provider = new CostRateProvider();
        ReflectionTestUtils.setField(provider, "configuredRate", configured);
        return provider;
    }
}
