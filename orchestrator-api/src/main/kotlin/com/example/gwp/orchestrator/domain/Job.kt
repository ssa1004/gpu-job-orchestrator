package com.example.gwp.orchestrator.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Job 애그리거트 루트 (도메인에서 함께 변경되는 객체 그룹의 진입점, DDD 용어).
 *
 * ### 상태 변경 규칙
 * 상태 천이는 반드시 `mark*` 메서드를 통해서만 수행한다. 직접 setter 노출은
 * 의도적으로 막아 (`var ... private set`) 도메인 무결성을 지킨다 — 예를 들어
 * SUCCEEDED 가 다시 RUNNING 으로 돌아가는 invalid 전이는 setter 가 있으면 막을 수 없다.
 *
 * ### 시간
 * 모든 timestamp 결정은 외부 [Clock] 으로 주입한다. 이유 두 가지:
 * - 테스트에서 시각을 고정할 수 있다 (`Clock.fixed(...)`).
 * - 모든 timestamp 가 UTC 로 통일된다 — 컨테이너 시계 / 타임존 차이로 인한 버그 차단
 *   (ADR-0007 참고).
 *
 * Java 호출자 (`job.getId()` / `job.getStatus()` 등 Lombok `@Getter` 스타일) 무변경 —
 * Kotlin 이 `var xxx` 에 `getXxx()` 합성. `private set` 으로 외부 setter 는 노출 안 함.
 *
 * `kotlin-jpa` 가 JPA 가 요구하는 no-arg (protected) 생성자를 자동 합성, `kotlin-spring`
 * 이 final 클래스를 open 처리한다. 모든 stateful property 는 본문에서 정의 + `private set`
 * 으로 외부 mutation 차단 — primary constructor 위치에서는 `private set` 를 못 붙인다.
 */
@Entity
@Table(
    name = "jobs",
    indexes = [
        Index(name = "idx_jobs_status", columnList = "status"),
        Index(name = "idx_jobs_owner_created", columnList = "owner,created_at"),
    ],
)
class Job private constructor(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID,
    owner: String,
    inputUri: String,
    image: String,
    gpuCount: Int,
    status: JobStatus,
    priority: JobPriority,
    preemptionPolicy: PreemptionPolicy,
    createdAt: Instant,
    updatedAt: Instant,
    traceId: String? = null,
    resultUri: String? = null,
    k8sJobName: String? = null,
    errorMessage: String? = null,
    preemptedAt: Instant? = null,
    preemptedByJobId: UUID? = null,
    preemptedReason: String? = null,
    startedAt: Instant? = null,
    finishedAt: Instant? = null,
) {

    @Column(name = "owner", nullable = false, length = 128)
    var owner: String = owner
        private set

    @Column(name = "input_uri", nullable = false, length = 1024)
    var inputUri: String = inputUri
        private set

    @Column(name = "image", nullable = false, length = 256)
    var image: String = image
        private set

    @Column(name = "gpu_count", nullable = false)
    var gpuCount: Int = gpuCount
        private set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: JobStatus = status
        private set

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    var priority: JobPriority = priority
        private set

    /**
     * 더 높은 우선순위 잡에 GPU 를 양보할 의사. PREEMPTABLE (default) / NEVER.
     * 자세한 정의는 [PreemptionPolicy].
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "preemption_policy", nullable = false, length = 16)
    var preemptionPolicy: PreemptionPolicy = preemptionPolicy
        private set

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = createdAt
        private set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = updatedAt
        private set

    @Column(name = "result_uri", length = 1024)
    var resultUri: String? = resultUri
        private set

    @Column(name = "k8s_job_name", length = 256)
    var k8sJobName: String? = k8sJobName
        private set

    @Column(name = "trace_id", length = 64)
    var traceId: String? = traceId
        private set

    @Column(name = "error_message", length = 2048)
    var errorMessage: String? = errorMessage
        private set

    /** preempt (높은 우선순위 잡에게 GPU 를 양보) 발생 시각 — 분석 / 빌링 / 알림 용. */
    @Column(name = "preempted_at")
    var preemptedAt: Instant? = preemptedAt
        private set

    /** 누구 (어느 jobId) 에게 GPU 를 양보했는지 — 운영 화면에서 추적할 수 있게 기록. */
    @Column(name = "preempted_by_job_id")
    var preemptedByJobId: UUID? = preemptedByJobId
        private set

    /** preempt 사유 (자유 텍스트) — 보통 "preempted by higher priority job <id>". */
    @Column(name = "preempted_reason", length = 256)
    var preemptedReason: String? = preemptedReason
        private set

    @Column(name = "started_at")
    var startedAt: Instant? = startedAt
        private set

    @Column(name = "finished_at")
    var finishedAt: Instant? = finishedAt
        private set

    /**
     * JPA optimistic lock 버전. 도메인 코드가 직접 건드리지 않아도 — save 시점에 JPA 가
     * 자동 증가시키고 WHERE version=? 로 검증한다. preemption tick (PreemptionService)
     * 처럼 같은 row 를 콜백 / 사용자 cancel 과 동시에 수정할 때, 먼저 commit 한 쪽만 살고
     * 나머지는 OptimisticLockException → 호출자가 재평가. lock 없이 race 를 안전하게 거른다.
     */
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        private set

    /**
     * 모든 parent 가 SUCCEEDED 되면 호출 — WAITING_DEPS → QUEUED.
     * Dispatcher 가 그 다음 일반 dispatch path 로 픽업.
     */
    fun markReadyToQueue(clock: Clock) {
        if (status != JobStatus.WAITING_DEPS) {
            throw IllegalJobTransitionException(status, JobStatus.QUEUED)
        }
        status = JobStatus.QUEUED
        updatedAt = clock.instant()
    }

    /** K8s 디스패치 성공 시. QUEUED → DISPATCHING. WAITING_DEPS 는 dispatch 안 됨. */
    fun markDispatched(k8sJobName: String, clock: Clock) {
        if (status != JobStatus.QUEUED) {
            throw IllegalJobTransitionException(status, JobStatus.DISPATCHING)
        }
        this.k8sJobName = k8sJobName
        status = JobStatus.DISPATCHING
        updatedAt = clock.instant()
    }

    /** 워커가 실행 시작. DISPATCHING → RUNNING. */
    fun markRunning(clock: Clock) {
        if (status != JobStatus.DISPATCHING && status != JobStatus.QUEUED) {
            throw IllegalJobTransitionException(status, JobStatus.RUNNING)
        }
        status = JobStatus.RUNNING
        val now = clock.instant()
        startedAt = now
        updatedAt = now
    }

    /** 정상 종료. */
    fun markSucceeded(resultUri: String, clock: Clock) {
        if (status.isTerminal()) {
            throw IllegalJobTransitionException(status, JobStatus.SUCCEEDED)
        }
        status = JobStatus.SUCCEEDED
        this.resultUri = resultUri
        val now = clock.instant()
        finishedAt = now
        updatedAt = now
    }

    /** 실패 종료. (제출 단계 dispatch 실패 포함) */
    fun markFailed(reason: String, clock: Clock) {
        if (status.isTerminal()) {
            throw IllegalJobTransitionException(status, JobStatus.FAILED)
        }
        status = JobStatus.FAILED
        errorMessage = reason
        val now = clock.instant()
        finishedAt = now
        updatedAt = now
    }

    /** 취소. */
    fun markCancelled(clock: Clock) {
        if (status.isTerminal()) {
            throw IllegalJobTransitionException(status, JobStatus.CANCELLED)
        }
        status = JobStatus.CANCELLED
        val now = clock.instant()
        finishedAt = now
        updatedAt = now
    }

    /**
     * 시스템이 더 높은 우선순위 잡에게 GPU 양보 (preemption). ACTIVE 상태에서만 호출 가능.
     *
     * **사전 조건**: [isPreemptable] 가 true 여야 — 호출자
     * (PreemptionEvaluator) 가 후보 선정 단계에서 보장. 도메인은 방어적으로 한 번 더 체크.
     *
     * @param byJobId 이 잡의 GPU 를 차지하게 된 잡의 id (운영 화면에서 추적 가능하도록 기록)
     * @param reason  보통 "preempted by higher priority job <id> (priority=...)"
     */
    fun markPreempted(byJobId: UUID, reason: String, clock: Clock) {
        if (status.isTerminal()) {
            throw IllegalJobTransitionException(status, JobStatus.PREEMPTED)
        }
        check(preemptionPolicy != PreemptionPolicy.NEVER) { "job is NEVER-preemptable: id=$id" }
        val now = clock.instant()
        status = JobStatus.PREEMPTED
        preemptedAt = now
        preemptedByJobId = byJobId
        preemptedReason = reason
        finishedAt = now
        updatedAt = now
    }

    /** Preemption 후보 자격: ACTIVE + PREEMPTABLE. */
    fun isPreemptable(): Boolean =
        status.isActive() && preemptionPolicy == PreemptionPolicy.PREEMPTABLE

    companion object {

        /** 신규 Job 생성 (QUEUED 상태로 — parent 없는 일반 잡). */
        @JvmStatic
        fun submit(spec: JobSpec, traceId: String?, clock: Clock): Job =
            submit(spec, traceId, false, clock)

        /**
         * 신규 Job 생성. `waitingForParents` 가 true 면 [JobStatus.WAITING_DEPS]
         * 로 시작 — 모든 parent 가 SUCCEEDED 되면 DependencyResolutionService 가
         * QUEUED 로 promote.
         */
        @JvmStatic
        fun submit(spec: JobSpec, traceId: String?, waitingForParents: Boolean, clock: Clock): Job {
            val now = clock.instant()
            return Job(
                id = UUID.randomUUID(),
                owner = spec.owner,
                inputUri = spec.inputUri,
                image = spec.image,
                gpuCount = spec.gpuCount,
                status = if (waitingForParents) JobStatus.WAITING_DEPS else JobStatus.QUEUED,
                priority = spec.priority,
                preemptionPolicy = spec.preemptionPolicy,
                createdAt = now,
                updatedAt = now,
                traceId = traceId,
            )
        }
    }
}
