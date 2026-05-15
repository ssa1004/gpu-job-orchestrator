package com.example.gwp.orchestrator.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 테넌트(owner)별 동시 실행 제한. 제출 흐름에서 QuotaService 가 검사한다.
 *
 * 설계 노트: 단순 카운트 기반 제한. 토큰 버킷이나 슬라이딩 윈도우는
 * 짧은 burst 까지 잡고 싶을 때 도입. 현재는 GPU Job 이 분 단위로 길어
 * 동시 실행 카운트만으로 충분.
 *
 * Java 호출자 (QuotaService / QuotaServiceTest) 가 `UserQuota.builder().owner(...).build()`
 * 패턴을 사용해 — Lombok `@Builder` 호환 builder 를 companion object 에 직접 구현해 노출.
 *
 * `kotlin-jpa` 가 JPA 가 요구하는 no-arg 생성자를 자동 합성하고, `kotlin-spring` 이
 * final 클래스를 open 처리한다.
 */
@Entity
@Table(name = "user_quotas")
class UserQuota(
    @Id
    @Column(name = "owner", length = 128)
    var owner: String,

    @Column(name = "max_concurrent_jobs", nullable = false)
    var maxConcurrentJobs: Int,

    @Column(name = "max_gpu_count", nullable = false)
    var maxGpuCount: Int,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {

    fun accommodates(currentJobs: Int, currentGpus: Int, requestedGpus: Int): Boolean {
        if (currentJobs + 1 > maxConcurrentJobs) return false
        if (currentGpus + requestedGpus > maxGpuCount) return false
        return true
    }

    companion object {
        /** Lombok `@Builder` 호환 — Java 호출자 `UserQuota.builder().owner(...).build()`. */
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    /**
     * Java Lombok `@Builder` 호환 builder. 모든 필드를 chained setter 로 받고 build() 로
     * 인스턴스 생성. 누락 필드는 sensible default (Instant.EPOCH / 0) 또는 lateinit
     * 형태로 둔다 — 실제 호출자는 모든 필드를 채우므로 default 가 사용되지 않는다.
     */
    class Builder {
        private var owner: String? = null
        private var maxConcurrentJobs: Int = 0
        private var maxGpuCount: Int = 0
        private var updatedAt: Instant? = null

        fun owner(owner: String): Builder = apply { this.owner = owner }
        fun maxConcurrentJobs(maxConcurrentJobs: Int): Builder =
            apply { this.maxConcurrentJobs = maxConcurrentJobs }
        fun maxGpuCount(maxGpuCount: Int): Builder = apply { this.maxGpuCount = maxGpuCount }
        fun updatedAt(updatedAt: Instant): Builder = apply { this.updatedAt = updatedAt }

        fun build(): UserQuota = UserQuota(
            requireNotNull(owner) { "owner is required" },
            maxConcurrentJobs,
            maxGpuCount,
            requireNotNull(updatedAt) { "updatedAt is required" },
        )
    }
}
