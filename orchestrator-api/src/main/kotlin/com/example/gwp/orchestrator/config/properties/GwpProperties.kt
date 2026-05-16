package com.example.gwp.orchestrator.config.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 모든 도메인 / 인프라 설정을 한 record 트리에 모아 type-safe (잘못된 타입은 컴파일 시점
 * 에 에러) + bean validation (`@NotBlank`, `@Min` 등 어노테이션으로 자동 검증) 로
 * 관리한다. application.yml 의 `gwp.*` prefix 가 자동 매핑된다.
 *
 * 장점:
 * - `@Value` 필드 주입의 final 불가 / 검증 부재 문제 해결
 * - 설정 누락 시 startup 시점에 즉시 실패 (런타임 NPE — NullPointerException 방지)
 * - IDE 가 yml 자동완성
 *
 * Java 호출자 (`properties.kubernetes()` / `new GwpProperties.Kubernetes(...)`) 그대로
 * 동작 — `@JvmRecord data class` 가 record 시그니처를 보존. Spring Boot 3 의
 * configuration properties scanner 는 record 의 canonical constructor 를 인식.
 */
@ConfigurationProperties(prefix = "gwp")
@JvmRecord
data class GwpProperties(
    @field:NotNull @field:Valid val kubernetes: Kubernetes,
    @field:NotNull @field:Valid val storage: Storage,
    @field:NotNull @field:Valid val callback: Callback,
    @field:NotNull @field:Valid val security: Security,
    @field:NotNull @field:Valid val outbox: Outbox,
    @field:NotNull @field:Valid val quota: Quota,
    @field:NotNull @field:Valid val leader: Leader,
) {

    @JvmRecord
    data class Kubernetes(
        val enabled: Boolean,
        @field:NotBlank val namespace: String,
        @field:Min(60) val jobTtlSeconds: Int,
        @field:NotBlank val callbackUrl: String,
    )

    @JvmRecord
    data class Storage(
        val enabled: Boolean,
        @field:Min(60) val presignTtlSeconds: Int,
    )

    @JvmRecord
    data class Callback(
        @field:NotBlank val sharedSecret: String,
    )

    @JvmRecord
    data class Security(@field:NotNull @field:Valid val jwt: Jwt) {

        @JvmRecord
        data class Jwt(val enabled: Boolean)
    }

    @JvmRecord
    data class Outbox(@field:NotNull @field:Valid val relay: Relay) {

        @JvmRecord
        data class Relay(
            val enabled: Boolean,
            @field:Min(50) val pollIntervalMs: Long,
            @field:Min(1) val batchSize: Int,
            @field:Min(1000) val sendTimeoutMs: Long,
            @field:NotBlank val topicPrefix: String,
            /**
             * DLQ 격리 임계 attempt 수. 메시지 1건이 이 횟수만큼 실패하면 dead_lettered
             * 로 격리되어 polling 큐에서 빠진다. poison pill (영구적으로 발행 실패하는
             * 메시지) 이 뒤 메시지의 발행을 막는 head-of-line blocking 을 방지.
             */
            @field:Min(1) val maxAttempts: Int,
        )
    }

    @JvmRecord
    data class Quota(
        @field:Min(1) val defaultMaxConcurrentJobs: Int,
        @field:Min(1) val defaultMaxGpuCount: Int,
        /**
         * Postgres advisory lock 으로 owner 단위 quota 검사를 직렬화할지 여부.
         * 운영 (prod) 에서는 true 로 동시 제출 over-commit race 차단.
         * H2 / 단위 테스트는 false — 인메모리 단일 인스턴스라 race 없음.
         * yml 에 누락 시 기본 false.
         */
        val advisoryLockEnabled: Boolean,
    )

    /**
     * 다중 인스턴스 leader election 설정. `mode = lease` 일 때만 K8s Lease 가
     * 활성. `shedlock` (또는 누락) 이면 기존 ShedLock path 그대로.
     *
     * client-go 권장 표준 비율 (15s / 10s / 2s) 이 default.
     * lease-duration 은 renew-deadline 보다 충분히 길어야 한다 (network blip 흡수).
     */
    @JvmRecord
    data class Leader(
        @field:NotBlank val mode: String,
        @field:NotBlank val namespace: String,
        @field:NotBlank val leaseName: String,
        /**
         * Pod identity — 이 값이 lease 의 holderIdentity 에 박힌다. K8s Downward API
         * 로 `metadata.name` 을 환경변수로 받아 채우는 게 운영 컨벤션.
         * 누락 시 hostname.
         */
        val identity: String?,
        @field:Min(1) val leaseDurationSeconds: Long,
        @field:Min(1) val renewDeadlineSeconds: Long,
        @field:Min(1) val retryPeriodSeconds: Long,
    )
}
