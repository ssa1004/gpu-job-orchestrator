package com.example.gwp.orchestrator.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageLogMaskTest {

    @Test
    void mask_normalImage_passthrough() {
        assertThat(ImageLogMask.mask("ghcr.io/org/repo:1.0"))
                .isEqualTo("ghcr.io/org/repo:1.0");
    }

    @Test
    void mask_imageWithSha256Digest_preserved() {
        String input = "ghcr.io/org/repo@sha256:" + "0".repeat(64);
        assertThat(ImageLogMask.mask(input)).isEqualTo(input);
    }

    @Test
    void mask_imageWithCredentialsInUrl_redacted() {
        String input = "https://user:secret-token@registry.example.com/org/repo:1.0";
        String masked = ImageLogMask.mask(input);
        assertThat(masked).doesNotContain("secret-token");
        assertThat(masked).doesNotContain("user:");
        assertThat(masked).contains("***@registry.example.com");
    }

    @Test
    void mask_imageWithCredentialsNoScheme_redacted() {
        // 스킴 없이 user:token@host 형태 — 일부 docker login 흐름에서 발생 가능.
        String input = "user:token@registry.example.com/org/repo:1.0";
        String masked = ImageLogMask.mask(input);
        assertThat(masked).doesNotContain("token");
        assertThat(masked).contains("***@registry.example.com");
    }

    @Test
    void mask_nullOrEmpty_returnsPlaceholder() {
        assertThat(ImageLogMask.mask(null)).isEqualTo("<null>");
        assertThat(ImageLogMask.mask("")).isEqualTo("<empty>");
    }

    @Test
    void mask_overlyLongImage_truncated() {
        String longImage = "a".repeat(300);
        String masked = ImageLogMask.mask(longImage);
        assertThat(masked.length()).isLessThanOrEqualTo(300);
        assertThat(masked).endsWith("...");
    }
}
