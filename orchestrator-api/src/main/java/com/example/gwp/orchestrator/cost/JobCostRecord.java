package com.example.gwp.orchestrator.cost;

import com.example.gwp.orchestrator.domain.JobStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 한 Job 의 cost 기록 (append-only). Job 종착 시 {@code CostAttributionService} 가 정확히 1개 INSERT.
 *
 * <p><b>왜 별도 테이블 (vs Job 컬럼 추가)</b>:
 * <ul>
 *   <li>Job 은 *상태 변경* 의 aggregate. cost 는 *불변 사실*. 책임 분리.</li>
 *   <li>Cost 정정 (rate 변경 / 재계산) 시 *새 row* 로 표현 — append-only 가 자연스러움.</li>
 *   <li>Cost query 가 Job 의 다른 컬럼 join 안 해도 됨 (분석 / 빌링 export 가 효율).</li>
 * </ul>
 *
 * <p>같은 jobId 에 두 row 들어가면 안 됨 — UNIQUE constraint 가 막음. CostAttributionService 가
 * 한 번만 호출되도록 호출 측 (lifecycle hook) 책임.</p>
 *
 * <p>{@code finalStatus} 도 박제 — Job aggregate 의 status 가 나중에 다시 바뀌어도 cost 시점의
 * 기록은 그대로 (PREEMPTED 잡이 후속 ADR 에서 자동 requeue 되어 새 잡으로 다시 RUNNING 되는 등).</p>
 */
@Entity
@Table(name = "job_cost_records", uniqueConstraints = {
        @UniqueConstraint(name = "uq_job_cost_job_id", columnNames = "job_id")
}, indexes = {
        // owner 별 시간 구간 집계 — 가장 자주 쓰임
        @Index(name = "idx_job_cost_owner_time", columnList = "owner,recorded_at DESC"),
        // 시간 구간 전체 (월별 빌링 export)
        @Index(name = "idx_job_cost_time", columnList = "recorded_at DESC")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class JobCostRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "owner", nullable = false, length = 128)
    private String owner;

    @Column(name = "gpu_count", nullable = false)
    private int gpuCount;

    /** Job 의 startedAt ~ finishedAt 사이 millis. PREEMPTED / CANCELLED 도 그때까지 사용 분 청구. */
    @Column(name = "runtime_millis", nullable = false)
    private long runtimeMillis;

    /** 계산 시점 GPU-hour 단가 (KRW) — rate 가 나중에 바뀌어도 이 row 의 cost 는 불변. */
    @Column(name = "rate_per_gpu_hour", nullable = false, precision = 18, scale = 0)
    private BigDecimal ratePerGpuHour;

    @Column(name = "computed_cost", nullable = false, precision = 18, scale = 0)
    private BigDecimal computedCost;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_status", nullable = false, length = 32)
    private JobStatus finalStatus;

    /** Job 의 startedAt — 회계상 사용 시작 시각. null 이면 아예 RUNNING 안 한 잡 (dispatch 실패 등). */
    @Column(name = "job_started_at")
    private Instant jobStartedAt;

    /** Job 의 finishedAt. */
    @Column(name = "job_finished_at", nullable = false)
    private Instant jobFinishedAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    public Duration runtime() { return Duration.ofMillis(runtimeMillis); }

    public static JobCostRecord forJob(UUID jobId, String owner, int gpuCount,
                                       Instant jobStartedAt, Instant jobFinishedAt,
                                       JobStatus finalStatus, CostRate rate, Instant recordedAt) {
        long runtimeMs = jobStartedAt == null ? 0L : Duration.between(jobStartedAt, jobFinishedAt).toMillis();
        if (runtimeMs < 0) runtimeMs = 0L;   // clock skew 방어
        BigDecimal cost = rate.calculate(gpuCount, Duration.ofMillis(runtimeMs));
        return new JobCostRecord(
                UUID.randomUUID(),
                jobId,
                owner,
                gpuCount,
                runtimeMs,
                rate.costPerGpuHour(),
                cost,
                "KRW",
                finalStatus,
                jobStartedAt,
                jobFinishedAt,
                recordedAt
        );
    }
}
