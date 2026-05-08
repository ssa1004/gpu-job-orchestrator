package com.example.gwp.orchestrator.observability.baggage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화이트리스트 / 길이 cap / null 거절 — sensitive payload 가 baggage 로 우발 전파되는
 * 사고를 막는 첫 번째 방어선.
 */
class JobBaggageTest {

    @Test
    void allowed_acceptsWhitelistedKeys() {
        assertThat(JobBaggage.isAllowed(JobBaggage.OWNER, "alice")).isTrue();
        assertThat(JobBaggage.isAllowed(JobBaggage.COST_CENTER, "research-vision")).isTrue();
        assertThat(JobBaggage.isAllowed(JobBaggage.PRIORITY, "HIGH")).isTrue();
    }

    @Test
    void allowed_rejectsUnknownKey() {
        // sensitive 후보 — token / auth / secret / pii — 모두 반드시 reject.
        assertThat(JobBaggage.isAllowed("authorization", "bearer-xxx")).isFalse();
        assertThat(JobBaggage.isAllowed("session-token", "abc")).isFalse();
        assertThat(JobBaggage.isAllowed("ssn", "123-45-6789")).isFalse();
        assertThat(JobBaggage.isAllowed("api_key", "sk_live_xxx")).isFalse();
    }

    @Test
    void allowed_rejectsNullsAndBlanks() {
        assertThat(JobBaggage.isAllowed(null, "v")).isFalse();
        assertThat(JobBaggage.isAllowed(JobBaggage.OWNER, null)).isFalse();
        assertThat(JobBaggage.isAllowed(JobBaggage.OWNER, "")).isFalse();
        assertThat(JobBaggage.isAllowed(JobBaggage.OWNER, "   ")).isFalse();
    }

    @Test
    void allowed_rejectsOversizedValue() {
        String oversized = "x".repeat(JobBaggage.MAX_VALUE_LENGTH + 1);
        assertThat(JobBaggage.isAllowed(JobBaggage.OWNER, oversized)).isFalse();
    }

    @Test
    void allowed_acceptsAtBoundary() {
        String onLimit = "x".repeat(JobBaggage.MAX_VALUE_LENGTH);
        assertThat(JobBaggage.isAllowed(JobBaggage.OWNER, onLimit)).isTrue();
    }
}
