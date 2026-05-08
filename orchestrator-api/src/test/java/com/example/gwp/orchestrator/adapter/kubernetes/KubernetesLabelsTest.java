package com.example.gwp.orchestrator.adapter.kubernetes;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K8s label value 의 형식 (RFC 1123 변형) 을 강제로 만족하도록 정규화하는지 검증.
 * 정규식: ^([A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?)?$ , 길이 ≤ 63.
 */
class KubernetesLabelsTest {

    private static final Pattern K8S_LABEL = Pattern.compile(
            "^([A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?)?$");

    private static void assertValidLabel(String value) {
        assertThat(value).hasSizeLessThanOrEqualTo(63);
        assertThat(K8S_LABEL.matcher(value).matches())
                .as("value=%s must match K8s label regex", value)
                .isTrue();
    }

    @Test
    void sanitize_email_replacesAtSymbol() {
        String result = KubernetesLabels.sanitizeLabelValue("alice@example.org");
        assertValidLabel(result);
        assertThat(result).isEqualTo("alice_example.org");
    }

    @Test
    void sanitize_uuid_passesThroughUnchanged() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        String result = KubernetesLabels.sanitizeLabelValue(uuid);
        assertValidLabel(result);
        assertThat(result).isEqualTo(uuid);
    }

    @Test
    void sanitize_nullOrEmpty_returnsUnknown() {
        assertThat(KubernetesLabels.sanitizeLabelValue(null)).isEqualTo("unknown");
        assertThat(KubernetesLabels.sanitizeLabelValue("")).isEqualTo("unknown");
    }

    @Test
    void sanitize_specialCharsAtEdges_strippedToValid() {
        // 시작/끝 invalid 문자 — alphanumeric 으로 트리밍
        String result = KubernetesLabels.sanitizeLabelValue("@@user.name@@");
        assertValidLabel(result);
        assertThat(result).startsWith("user");
    }

    @Test
    void sanitize_tooLong_truncatedWithHashSuffix() {
        // 100자 — 잘리고 끝에 해시가 붙어 충돌 위험을 낮춘다
        String longInput = "a".repeat(100);
        String result = KubernetesLabels.sanitizeLabelValue(longInput);
        assertValidLabel(result);
        assertThat(result).hasSize(63);
        // 같은 입력이면 같은 해시
        assertThat(KubernetesLabels.sanitizeLabelValue(longInput)).isEqualTo(result);
        // 다른 입력이면 다른 해시 (sanitize 후 prefix 가 같더라도)
        String other = "a".repeat(99) + "b";
        assertThat(KubernetesLabels.sanitizeLabelValue(other)).isNotEqualTo(result);
    }

    @Test
    void sanitize_allInvalid_fallsBackToUnknown() {
        // 모든 문자 invalid → trimToValid 가 빈 문자열 → "unknown" fallback
        String result = KubernetesLabels.sanitizeLabelValue("@@@@");
        assertThat(result).isEqualTo("unknown");
    }

    @Test
    void sanitize_injectionAttempt_neutralizesSeparators() {
        // 라벨 인젝션 시도 — `,` `;` 등은 모두 _ 로 치환되어 별도 라벨로 해석될 여지 없음
        String result = KubernetesLabels.sanitizeLabelValue("alice,role=admin;ns=kube-system");
        assertValidLabel(result);
        assertThat(result).doesNotContain(",", ";", "=");
    }
}
