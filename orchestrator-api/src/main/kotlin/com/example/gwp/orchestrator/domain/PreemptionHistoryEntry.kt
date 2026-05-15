package com.example.gwp.orchestrator.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Preemption 이 한 번 발생한 사실의 영속 기록 (append-only).
 *
 * `Job.preempted_at / preempted_by_job_id` 만으로는 "이 잡이 다른 잡을 *얼마나 자주*
 * preempt 했는지" 같은 분석이 어렵다. 영속 history 가 있으면 운영 화면 (timeline) / 빌링
 * (양보 횟수 보상) / 정책 튜닝 (어느 priority 가 너무 자주 죽이나) 모두 가능.
 *
 * 한 번 만들어지면 변경되지 않는 append-only 모델 — 모든 필드 `val`.
 * Java 호출자 (`h.getId()` / `h.getVictimJobId()` 등 Lombok-스타일 getter) 그대로 동작 —
 * Kotlin 컴파일러가 `val xxx` 에 `getXxx()` 를 합성한다.
 *
 * `kotlin-jpa` 플러그인이 JPA 가 요구하는 no-arg 생성자를 자동 합성하고, `kotlin-spring`
 * 이 final 클래스를 open 처리한다.
 */
@Entity
@Table(
    name = "preemption_history",
    indexes = [
        Index(
            name = "idx_preemption_history_victim_time",
            columnList = "victim_job_id, preempted_at DESC",
        ),
        Index(
            name = "idx_preemption_history_preemptor_time",
            columnList = "preemptor_job_id, preempted_at DESC",
        ),
        Index(name = "idx_preemption_history_time", columnList = "preempted_at DESC"),
    ],
)
class PreemptionHistoryEntry(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID,

    @Column(name = "victim_job_id", nullable = false)
    val victimJobId: UUID,

    @Column(name = "victim_owner", nullable = false, length = 128)
    val victimOwner: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "victim_priority", nullable = false, length = 16)
    val victimPriority: JobPriority,

    @Column(name = "victim_gpu_count", nullable = false)
    val victimGpuCount: Int,

    @Column(name = "preemptor_job_id", nullable = false)
    val preemptorJobId: UUID,

    @Column(name = "preemptor_owner", nullable = false, length = 128)
    val preemptorOwner: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "preemptor_priority", nullable = false, length = 16)
    val preemptorPriority: JobPriority,

    @Column(name = "preempted_at", nullable = false)
    val preemptedAt: Instant,

    @Column(name = "reason", nullable = false, length = 256)
    val reason: String,
) {

    companion object {
        @JvmStatic
        fun record(victim: Job, preemptor: Job, reason: String, at: Instant): PreemptionHistoryEntry =
            PreemptionHistoryEntry(
                UUID.randomUUID(),
                victim.id,
                victim.owner,
                victim.priority,
                victim.gpuCount,
                preemptor.id,
                preemptor.owner,
                preemptor.priority,
                at,
                reason,
            )
    }
}
