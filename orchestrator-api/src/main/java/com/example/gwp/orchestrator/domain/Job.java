package com.example.gwp.orchestrator.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Job 애그리거트 루트. 상태 천이는 반드시 {@code mark*} 메서드를 통해서만 수행한다 —
 * 직접 setter 노출은 의도적으로 막아 도메인 무결성을 보장한다.
 *
 * <p>시간 결정은 외부 {@link Clock} 으로 주입한다 (테스트 가능성 + UTC 일관성).</p>
 */
@Entity
@Table(name = "jobs", indexes = {
        @Index(name = "idx_jobs_status", columnList = "status"),
        @Index(name = "idx_jobs_owner_created", columnList = "owner,created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA 전용
@AllArgsConstructor(access = AccessLevel.PRIVATE)    // builder 전용
@Builder(access = AccessLevel.PACKAGE)               // 도메인 패키지 + 도메인 단위테스트만 직접 build
public class Job {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner", nullable = false, length = 128)
    private String owner;

    @Column(name = "input_uri", nullable = false, length = 1024)
    private String inputUri;

    @Column(name = "result_uri", length = 1024)
    private String resultUri;

    @Column(name = "image", nullable = false, length = 256)
    private String image;

    @Column(name = "gpu_count", nullable = false)
    private int gpuCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private JobPriority priority;

    /**
     * 더 높은 우선순위 잡에 GPU 를 양보할 의사. PREEMPTABLE (default) / NEVER.
     * 자세한 정의는 {@link PreemptionPolicy}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "preemption_policy", nullable = false, length = 16)
    private PreemptionPolicy preemptionPolicy;

    @Column(name = "k8s_job_name", length = 256)
    private String k8sJobName;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    /** preempt 발생 시각 — 분석 / 빌링 / 알림 용. */
    @Column(name = "preempted_at")
    private Instant preemptedAt;

    /** 누구 (어느 jobId) 에게 GPU 를 양보했는지 — 운영 화면 traceability. */
    @Column(name = "preempted_by_job_id")
    private UUID preemptedByJobId;

    /** preempt 사유 (자유 텍스트) — 보통 "preempted by higher priority job <id>". */
    @Column(name = "preempted_reason", length = 256)
    private String preemptedReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 신규 Job 생성 (QUEUED 상태로 — parent 없는 일반 잡). */
    public static Job submit(JobSpec spec, String traceId, Clock clock) {
        return submit(spec, traceId, false, clock);
    }

    /**
     * 신규 Job 생성. {@code waitingForParents} 가 true 면 {@link JobStatus#WAITING_DEPS}
     * 로 시작 — 모든 parent 가 SUCCEEDED 되면 {@link DependencyResolutionService} 가
     * QUEUED 로 promote.
     */
    public static Job submit(JobSpec spec, String traceId, boolean waitingForParents, Clock clock) {
        Instant now = clock.instant();
        return Job.builder()
                .id(UUID.randomUUID())
                .owner(spec.owner())
                .inputUri(spec.inputUri())
                .image(spec.image())
                .gpuCount(spec.gpuCount())
                .status(waitingForParents ? JobStatus.WAITING_DEPS : JobStatus.QUEUED)
                .priority(spec.priority())
                // spec 이 null 이면 도메인 default — 기존 호출자 (테스트) 와 backward compat.
                .preemptionPolicy(spec.preemptionPolicy() != null
                        ? spec.preemptionPolicy() : PreemptionPolicy.PREEMPTABLE)
                .traceId(traceId)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 모든 parent 가 SUCCEEDED 되면 호출 — WAITING_DEPS → QUEUED.
     * Dispatcher 가 그 다음 일반 dispatch path 로 픽업.
     */
    public void markReadyToQueue(Clock clock) {
        if (status != JobStatus.WAITING_DEPS) {
            throw new IllegalJobTransitionException(status, JobStatus.QUEUED);
        }
        this.status = JobStatus.QUEUED;
        this.updatedAt = clock.instant();
    }

    /** K8s 디스패치 성공 시. QUEUED → DISPATCHING. WAITING_DEPS 는 dispatch 안 됨. */
    public void markDispatched(String k8sJobName, Clock clock) {
        if (status != JobStatus.QUEUED) {
            throw new IllegalJobTransitionException(status, JobStatus.DISPATCHING);
        }
        this.k8sJobName = k8sJobName;
        this.status = JobStatus.DISPATCHING;
        this.updatedAt = clock.instant();
    }

    /** 워커가 실행 시작. DISPATCHING → RUNNING. */
    public void markRunning(Clock clock) {
        if (status != JobStatus.DISPATCHING && status != JobStatus.QUEUED) {
            throw new IllegalJobTransitionException(status, JobStatus.RUNNING);
        }
        this.status = JobStatus.RUNNING;
        this.startedAt = clock.instant();
        this.updatedAt = this.startedAt;
    }

    /** 정상 종료. */
    public void markSucceeded(String resultUri, Clock clock) {
        if (status.isTerminal()) {
            throw new IllegalJobTransitionException(status, JobStatus.SUCCEEDED);
        }
        this.status = JobStatus.SUCCEEDED;
        this.resultUri = resultUri;
        this.finishedAt = clock.instant();
        this.updatedAt = this.finishedAt;
    }

    /** 실패 종료. (제출 단계 dispatch 실패 포함) */
    public void markFailed(String reason, Clock clock) {
        if (status.isTerminal()) {
            throw new IllegalJobTransitionException(status, JobStatus.FAILED);
        }
        this.status = JobStatus.FAILED;
        this.errorMessage = reason;
        this.finishedAt = clock.instant();
        this.updatedAt = this.finishedAt;
    }

    /** 취소. */
    public void markCancelled(Clock clock) {
        if (status.isTerminal()) {
            throw new IllegalJobTransitionException(status, JobStatus.CANCELLED);
        }
        this.status = JobStatus.CANCELLED;
        this.finishedAt = clock.instant();
        this.updatedAt = this.finishedAt;
    }

    /**
     * 시스템이 더 높은 우선순위 잡에게 GPU 양보. ACTIVE 상태에서만 호출 가능.
     *
     * <p><b>사전 조건</b>: {@link #isPreemptable()} 가 true 여야 — 호출자 (PreemptionEvaluator)
     * 가 후보 선정 단계에서 보장. 도메인은 방어적으로 한 번 더 체크.</p>
     *
     * @param byJobId 이 잡의 GPU 를 차지하게 된 잡의 id (운영 화면 traceability)
     * @param reason  보통 "preempted by higher priority job <id> (priority=...)"
     */
    public void markPreempted(UUID byJobId, String reason, Clock clock) {
        if (status.isTerminal()) {
            throw new IllegalJobTransitionException(status, JobStatus.PREEMPTED);
        }
        if (preemptionPolicy == PreemptionPolicy.NEVER) {
            throw new IllegalStateException("job is NEVER-preemptable: id=" + id);
        }
        Instant now = clock.instant();
        this.status = JobStatus.PREEMPTED;
        this.preemptedAt = now;
        this.preemptedByJobId = byJobId;
        this.preemptedReason = reason;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    /** Preemption 후보 자격: ACTIVE + PREEMPTABLE. */
    public boolean isPreemptable() {
        return status.isActive() && preemptionPolicy == PreemptionPolicy.PREEMPTABLE;
    }
}
