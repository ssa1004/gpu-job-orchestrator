package com.example.gwp.orchestrator.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 모든 도메인 / 인프라 설정을 한 record 트리에 모아 type-safe + bean validation 로 관리한다.
 * application.yml 의 {@code gwp.*} prefix 가 매핑된다.
 *
 * <p>장점:</p>
 * <ul>
 *   <li>{@code @Value} 필드 주입의 final 불가 / 검증 부재 문제 해결</li>
 *   <li>설정 누락 시 startup 시점에 즉시 실패 (런타임 NPE 방지)</li>
 *   <li>IDE 가 yml 자동완성</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "gwp")
@Validated
public record GwpProperties(
        @NotNull @Valid Kubernetes kubernetes,
        @NotNull @Valid Storage storage,
        @NotNull @Valid Callback callback,
        @NotNull @Valid Security security,
        @NotNull @Valid Outbox outbox,
        @NotNull @Valid Quota quota
) {

    public record Kubernetes(
            boolean enabled,
            @NotBlank String namespace,
            @Min(60) int jobTtlSeconds,
            @NotBlank String callbackUrl
    ) {}

    public record Storage(
            boolean enabled,
            @Min(60) int presignTtlSeconds
    ) {}

    public record Callback(
            @NotBlank String sharedSecret
    ) {}

    public record Security(@NotNull @Valid Jwt jwt) {
        public record Jwt(boolean enabled) {}
    }

    public record Outbox(@NotNull @Valid Relay relay) {
        public record Relay(
                boolean enabled,
                @Min(50) long pollIntervalMs,
                @Min(1) int batchSize,
                @Min(1000) long sendTimeoutMs,
                @NotBlank String topicPrefix
        ) {}
    }

    public record Quota(
            @Min(1) int defaultMaxConcurrentJobs,
            @Min(1) int defaultMaxGpuCount
    ) {}
}
