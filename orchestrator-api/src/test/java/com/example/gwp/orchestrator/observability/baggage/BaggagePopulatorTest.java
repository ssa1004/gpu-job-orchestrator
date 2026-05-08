package com.example.gwp.orchestrator.observability.baggage;

import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.BaggageManager;
import io.micrometer.tracing.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BaggagePopulator 의 핵심 보장:
 *
 * <ul>
 *   <li>화이트리스트 외 키는 활성화되지 않는다 (sensitive payload 차단).</li>
 *   <li>활성화된 만큼 close 가 *모두* 호출된다 (다음 요청에 leak 방지).</li>
 *   <li>MDC 가 동시에 set / unset 된다 (로그 라인 라벨 자동 색인).</li>
 * </ul>
 */
class BaggagePopulatorTest {

    private final FakeBaggageManager manager = new FakeBaggageManager();
    private final BaggagePopulator populator = new BaggagePopulator(manager);

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void activate_skipsKeysOutsideWhitelist() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(JobBaggage.OWNER, "alice");
        values.put("authorization", "bearer-xxx");  // sensitive — drop
        values.put("api_key", "sk_live_xxx");        // sensitive — drop

        try (var scope = populator.activate(values)) {
            assertThat(manager.activeKeys()).containsOnly(JobBaggage.OWNER);
            assertThat(MDC.get(JobBaggage.OWNER)).isEqualTo("alice");
            assertThat(MDC.get("authorization")).isNull();
            assertThat(MDC.get("api_key")).isNull();
        }
    }

    @Test
    void activate_setsAndClearsMdcAroundScope() {
        try (var scope = populator.activate(Map.of(
                JobBaggage.OWNER, "bob",
                JobBaggage.COST_CENTER, "platform-team"))) {
            assertThat(MDC.get(JobBaggage.OWNER)).isEqualTo("bob");
            assertThat(MDC.get(JobBaggage.COST_CENTER)).isEqualTo("platform-team");
        }
        // close 후 MDC 가 비워졌는지 — leak 방지의 핵심.
        assertThat(MDC.get(JobBaggage.OWNER)).isNull();
        assertThat(MDC.get(JobBaggage.COST_CENTER)).isNull();
    }

    @Test
    void close_invokesEveryOpenedScopeOnce() {
        var scope = populator.activate(Map.of(
                JobBaggage.OWNER, "carol",
                JobBaggage.PRIORITY, "HIGH"));
        scope.close();

        assertThat(manager.closedCount()).isEqualTo(2);
    }

    /** Test fake — close 카운트와 활성 키 노출. micrometer-tracing 의 Mock 보다 단순. */
    static class FakeBaggageManager implements BaggageManager {
        private final Map<String, String> active = new HashMap<>();
        private final AtomicInteger closed = new AtomicInteger();

        Iterable<String> activeKeys() { return active.keySet(); }
        int closedCount() { return closed.get(); }

        @Override public Map<String, String> getAllBaggage() { return new HashMap<>(active); }
        @Override public Map<String, String> getAllBaggage(TraceContext c) { return getAllBaggage(); }
        @Override public Baggage getBaggage(String name) { return null; }
        @Override public Baggage getBaggage(TraceContext c, String name) { return null; }
        @Override public Baggage createBaggage(String name) { return Baggage.NOOP; }
        @Override public Baggage createBaggage(String name, String value) {
            active.put(name, value); return Baggage.NOOP;
        }
        @Override public BaggageInScope createBaggageInScope(String name, String value) {
            active.put(name, value);
            return new BaggageInScope() {
                @Override public String name() { return name; }
                @Override public String get() { return value; }
                @Override public String get(TraceContext c) { return value; }
                @Override public void close() { active.remove(name); closed.incrementAndGet(); }
            };
        }
        @Override public BaggageInScope createBaggageInScope(TraceContext c, String name, String value) {
            return createBaggageInScope(name, value);
        }
    }
}
