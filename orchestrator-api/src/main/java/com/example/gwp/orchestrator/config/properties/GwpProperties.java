package com.example.gwp.orchestrator.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 모든 도메인 / 인프라 설정을 한 record 트리에 모아 type-safe (잘못된 타입은 컴파일 시점
 * 에 에러) + bean validation (`@NotBlank`, `@Min` 등 어노테이션으로 자동 검증) 로
 * 관리한다. application.yml 의 {@code gwp.*} prefix 가 자동 매핑된다.
 *
 * <p>장점:</p>
 * <ul>
 *   <li>{@code @Value} 필드 주입의 final 불가 / 검증 부재 문제 해결</li>
 *   <li>설정 누락 시 startup 시점에 즉시 실패 (런타임 NPE — NullPointerException 방지)</li>
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
        @NotNull @Valid Quota quota,
        @NotNull @Valid Leader leader
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
                @NotBlank String topicPrefix,
                /**
                 * DLQ 격리 임계 attempt 수. 메시지 1건이 이 횟수만큼 실패하면 dead_lettered
                 * 로 격리되어 polling 큐에서 빠진다. poison pill (영구적으로 발행 실패하는
                 * 메시지) 이 뒤 메시지의 발행을 막는 head-of-line blocking 을 방지.
                 */
                @Min(1) int maxAttempts
        ) {}
    }

    public record Quota(
            @Min(1) int defaultMaxConcurrentJobs,
            @Min(1) int defaultMaxGpuCount,
            /**
             * Postgres advisory lock 으로 owner 단위 quota 검사를 직렬화할지 여부.
             * 운영 (prod) 에서는 true 로 동시 제출 over-commit race 차단.
             * H2 / 단위 테스트는 false — 인메모리 단일 인스턴스라 race 없음.
             * yml 에 누락 시 기본 false.
             */
            boolean advisoryLockEnabled
    ) {}

    /**
     * 다중 인스턴스 leader election 설정. {@code mode = lease} 일 때만 K8s Lease 가
     * 활성. {@code shedlock} (또는 누락) 이면 기존 ShedLock path 그대로.
     *
     * <p>kube-controller-manager 와 같은 표준 비율 (15s / 10s / 2s) 이 default.
     * lease-duration 은 renew-deadline 보다 충분히 길어야 한다 (network blip 흡수).</p>
     */
    public record Leader(
            @NotBlank String mode,
            @NotBlank String namespace,
            @NotBlank String leaseName,
            /**
             * Pod identity — 이 값이 lease 의 holderIdentity 에 박힌다. K8s Downward API
             * 로 {@code metadata.name} 을 환경변수로 받아 채우는 게 운영 컨벤션.
             * 누락 시 hostname.
             */
            String identity,
            @Min(1) long leaseDurationSeconds,
            @Min(1) long renewDeadlineSeconds,
            @Min(1) long retryPeriodSeconds
    ) {}
}
