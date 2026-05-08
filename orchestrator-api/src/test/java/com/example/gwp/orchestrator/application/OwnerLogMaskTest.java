package com.example.gwp.orchestrator.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerLogMaskTest {

    @Test
    void mask_email_keepsDomain_hashesLocalPart() {
        String masked = OwnerLogMask.mask("alice@example.com");
        assertThat(masked).endsWith("@example.com");
        assertThat(masked).doesNotContain("alice");
        // 12자 hex prefix + @example.com
        assertThat(masked).matches("[0-9a-f]{12}@example\\.com");
    }

    @Test
    void mask_uuidStyleSubject_returnsHashOnly() {
        String masked = OwnerLogMask.mask("uuid-style-12345");
        assertThat(masked).matches("[0-9a-f]{12}");
    }

    @Test
    void mask_isDeterministic_sameInputSameOutput() {
        assertThat(OwnerLogMask.mask("alice@example.com"))
                .isEqualTo(OwnerLogMask.mask("alice@example.com"));
    }

    @Test
    void mask_distinctOwners_distinctMasks() {
        assertThat(OwnerLogMask.mask("alice@example.com"))
                .isNotEqualTo(OwnerLogMask.mask("bob@example.com"));
    }

    @Test
    void mask_nullOrBlank_returnsAnonymous() {
        assertThat(OwnerLogMask.mask(null)).isEqualTo("anonymous");
        assertThat(OwnerLogMask.mask("")).isEqualTo("anonymous");
        assertThat(OwnerLogMask.mask("   ")).isEqualTo("anonymous");
    }

    @Test
    void mask_emailWithEmptyLocalPart_treatsAsPlain() {
        // "@example.com" — at == 0, edge case.
        String masked = OwnerLogMask.mask("@example.com");
        // local part 가 비어 있으므로 plain hash 로 fallback (full string).
        assertThat(masked).matches("[0-9a-f]{12}");
    }
}
