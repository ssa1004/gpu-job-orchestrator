package com.example.gwp.orchestrator.cost

import com.example.gwp.orchestrator.domain.JobStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 한 Job 의 cost 기록 (append-only — 한 번 쓰면 수정 / 삭제 안 함). Job 종착 시
 * `CostAttributionService` 가 정확히 1개 INSERT.
 *
 * **왜 별도 테이블 (vs Job 컬럼 추가)**:
 * - Job 은 *상태 변경* 의 aggregate. cost 는 *불변 사실* (한 번 결정되면 안 바뀜)
 *   의 ledger (장부). 책임 분리.
 * - Cost 정정 (rate 변경 / 재계산) 시 *새 row* 로 표현 — append-only 가 자연스러움.
 * - Cost query 가 Job 의 다른 컬럼 join 안 해도 됨 (분석 / 빌링 export 가 효율).
 *
 * 같은 jobId 에 두 row 들어가면 안 됨 — DB 의 UNIQUE 제약이 막음. CostAttributionService
 * 가 한 번만 호출되도록 호출 측 (lifecycle hook) 책임.
 *
 * `finalStatus` 도 그대로 보관한다 (snapshot — 그 시점 값을 보존). Job aggregate
 * 의 status 가 나중에 다시 바뀌어도 cost 시점의 기록은 변하지 않는다 (PREEMPTED 잡이
 * 후속 ADR 에서 자동 requeue 되어 새 잡으로 다시 RUNNING 되는 시나리오 등).
 *
 * Java 호출자 (`r.getJobId()` 등 Lombok-스타일 getter) 그대로 동작 — Kotlin 컴파일러가
 * `val xxx` 에 `getXxx()` 를 합성. `kotlin-jpa` 가 JPA 가 요구하는 no-arg 생성자를 합성한다.
 */
@Entity
@Table(
    name = "job_cost_records",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_job_cost_job_id", columnNames = ["job_id"]),
    ],
    indexes = [
        // owner 별 시간 구간 집계 — 가장 자주 쓰임
        Index(name = "idx_job_cost_owner_time", columnList = "owner,recorded_at DESC"),
        // 시간 구간 전체 (월별 빌링 export)
        Index(name = "idx_job_cost_time", columnList = "recorded_at DESC"),
    ],
)
class JobCostRecord(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID,

    @Column(name = "job_id", nullable = false)
    val jobId: UUID,

    @Column(name = "owner", nullable = false, length = 128)
    val owner: String,

    @Column(name = "gpu_count", nullable = false)
    val gpuCount: Int,

    /** Job 의 startedAt ~ finishedAt 사이 millis. PREEMPTED / CANCELLED 도 그때까지 사용한
     *  분량을 청구 (GPU-시간 = 1 GPU 가 1 시간 동안 점유한 양). */
    @Column(name = "runtime_millis", nullable = false)
    val runtimeMillis: Long,

    /** 계산 시점 GPU-hour (1 GPU 가 1 시간 점유) 단가 (KRW). 단가가 나중에 바뀌어도 이
     *  row 의 cost 는 불변 (FeeSnapshot / PricingSnapshot 패턴 — 그 시점의 가격을 보관). */
    @Column(name = "rate_per_gpu_hour", nullable = false, precision = 18, scale = 0)
    val ratePerGpuHour: BigDecimal,

    @Column(name = "computed_cost", nullable = false, precision = 18, scale = 0)
    val computedCost: BigDecimal,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "final_status", nullable = false, length = 32)
    val finalStatus: JobStatus,

    /** Job 의 startedAt — 회계상 사용 시작 시각. null 이면 아예 RUNNING 안 한 잡 (dispatch 실패 등). */
    @Column(name = "job_started_at")
    val jobStartedAt: Instant?,

    /** Job 의 finishedAt. */
    @Column(name = "job_finished_at", nullable = false)
    val jobFinishedAt: Instant,

    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant,
) {

    fun runtime(): Duration = Duration.ofMillis(runtimeMillis)

    companion object {
        @JvmStatic
        fun forJob(
            jobId: UUID,
            owner: String,
            gpuCount: Int,
            jobStartedAt: Instant?,
            jobFinishedAt: Instant,
            finalStatus: JobStatus,
            rate: CostRate,
            recordedAt: Instant,
        ): JobCostRecord {
            var runtimeMs = if (jobStartedAt == null) 0L
            else Duration.between(jobStartedAt, jobFinishedAt).toMillis()
            // clock skew (서버 / 컨테이너 사이 시계 차이로 finishedAt < startedAt 이 되는 경우)
            // 방어 — 음수면 0 으로 보정
            if (runtimeMs < 0) runtimeMs = 0L
            val cost = rate.calculate(gpuCount, Duration.ofMillis(runtimeMs))
            return JobCostRecord(
                UUID.randomUUID(),
                jobId,
                owner,
                gpuCount,
                runtimeMs,
                rate.costPerGpuHour,
                cost,
                "KRW",
                finalStatus,
                jobStartedAt,
                jobFinishedAt,
                recordedAt,
            )
        }
    }
}
