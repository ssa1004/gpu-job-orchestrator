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

    @Column(name = "k8s_job_name", length = 256)
    private String k8sJobName;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

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

    /** 신규 Job 생성 (QUEUED 상태로). */
    public static Job submit(JobSpec spec, String traceId, Clock clock) {
        Instant now = clock.instant();
        return Job.builder()
                .id(UUID.randomUUID())
                .owner(spec.owner())
                .inputUri(spec.inputUri())
                .image(spec.image())
                .gpuCount(spec.gpuCount())
                .status(JobStatus.QUEUED)
                .priority(spec.priority())
                .traceId(traceId)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** K8s 디스패치 성공 시. QUEUED → DISPATCHING. */
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
}
