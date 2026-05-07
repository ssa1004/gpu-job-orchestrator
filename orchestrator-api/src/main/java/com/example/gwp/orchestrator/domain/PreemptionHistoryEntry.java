package com.example.gwp.orchestrator.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Preemption 이 한 번 발생한 사실의 영속 기록 (append-only).
 *
 * <p>{@code Job.preempted_at / preempted_by_job_id} 만으로는 "이 잡이 다른 잡을 *얼마나 자주*
 * preempt 했는지" 같은 분석이 어렵다. 영속 history 가 있으면 운영 화면 (timeline) / 빌링
 * (양보 횟수 보상) / 정책 튜닝 (어느 priority 가 너무 자주 죽이나) 모두 가능.</p>
 */
@Entity
@Table(name = "preemption_history", indexes = {
        @Index(name = "idx_preemption_history_victim_time",
                columnList = "victim_job_id, preempted_at DESC"),
        @Index(name = "idx_preemption_history_preemptor_time",
                columnList = "preemptor_job_id, preempted_at DESC"),
        @Index(name = "idx_preemption_history_time", columnList = "preempted_at DESC")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PreemptionHistoryEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "victim_job_id", nullable = false)
    private UUID victimJobId;

    @Column(name = "victim_owner", nullable = false, length = 128)
    private String victimOwner;

    @Enumerated(EnumType.STRING)
    @Column(name = "victim_priority", nullable = false, length = 16)
    private JobPriority victimPriority;

    @Column(name = "victim_gpu_count", nullable = false)
    private int victimGpuCount;

    @Column(name = "preemptor_job_id", nullable = false)
    private UUID preemptorJobId;

    @Column(name = "preemptor_owner", nullable = false, length = 128)
    private String preemptorOwner;

    @Enumerated(EnumType.STRING)
    @Column(name = "preemptor_priority", nullable = false, length = 16)
    private JobPriority preemptorPriority;

    @Column(name = "preempted_at", nullable = false)
    private Instant preemptedAt;

    @Column(name = "reason", nullable = false, length = 256)
    private String reason;

    public static PreemptionHistoryEntry record(Job victim, Job preemptor, String reason, Instant at) {
        return new PreemptionHistoryEntry(
                UUID.randomUUID(),
                victim.getId(),
                victim.getOwner(),
                victim.getPriority(),
                victim.getGpuCount(),
                preemptor.getId(),
                preemptor.getOwner(),
                preemptor.getPriority(),
                at,
                reason
        );
    }
}
