package com.example.gwp.orchestrator.application

import com.example.gwp.orchestrator.domain.Job
import com.example.gwp.orchestrator.domain.JobDependencyRepository
import com.example.gwp.orchestrator.domain.JobNotFoundException
import com.example.gwp.orchestrator.domain.JobRepository
import com.example.gwp.orchestrator.domain.JobStatus
import com.example.gwp.orchestrator.outbox.JobEvent
import com.example.gwp.orchestrator.outbox.OutboxWriter
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Dependency (잡 사이의 선후 관계) 해소 — parent 상태 변경 또는 주기적 검사로 child
 * 들의 시작 가능 여부 판단. DAG (Directed Acyclic Graph — 방향성 있고 순환 없는 그래프)
 * 워크플로우.
 *
 * **두 가지 trigger**:
 * 1. [onParentTerminal] — parent 가 SUCCEEDED / FAILED / CANCELLED 로 전이된 직후 호출
 *    (lifecycle service 가 같은 트랜잭션에서 직접 호출). 즉시 cascade (parent 결과를
 *    child 에게 자동 적용).
 * 2. [scanWaitingJobs] — scheduler 가 매 분 호출. lifecycle 이벤트가 유실되거나 job 이
 *    늦게 등록된 경우 보강 (idempotent — 이미 처리된 child 는 no-op, 즉 아무 것도 안 함).
 *
 * **Cascade 정책**:
 * - parent SUCCEEDED + child 의 *모든* parent SUCCEEDED → child WAITING_DEPS → QUEUED
 * - parent FAILED / CANCELLED → child *자동 CANCELLED* (cascade-cancel)
 * - parent PREEMPTED → child 는 그대로 WAITING — preempt (양보 당함) 된 잡은 사용자
 *   또는 스케줄러가 재투입할 수 있어 child 도 그 결과를 기다림. (반대 정책도 가능 —
 *   후속 ADR 참고)
 *
 * **왜 이벤트 listener 가 아닌 명시 호출**: lifecycle service 의 트랜잭션 안에서 같이
 * commit 되어야 함. Spring ApplicationEvent + `@TransactionalEventListener` 는 트랜잭션
 * commit 이후 다른 트랜잭션으로 처리 → cascade 가 별도 트랜잭션이 되어 race window
 * (race 발생 가능 시간 창) 생김. 명시 호출이 트랜잭션 경계를 분명히 함.
 *
 * Java 호출자 (JobLifecycleService / DependencyScanScheduler / Test) 무변경 — Kotlin
 * primary constructor 가 같은 positional 시그니처. `plugin.spring` 이 `@Service` 자동 open.
 */
@Service
class DependencyResolutionService(
    private val jobs: JobRepository,
    private val dependencies: JobDependencyRepository,
    private val outboxWriter: OutboxWriter,
    private val costAttribution: CostAttributionService,
    private val clock: Clock,
) {

    /**
     * Parent 가 종착 상태에 도달했을 때 호출. 영향받는 child 들의 promote / cascade-cancel 처리.
     */
    @Transactional
    fun onParentTerminal(parentJobId: UUID) {
        val parent = jobs.findById(parentJobId).orElseThrow { JobNotFoundException(parentJobId) }
        if (!parent.status.isTerminal()) {
            log.warn(
                "onParentTerminal called for non-terminal parent={} status={}",
                parentJobId, parent.status,
            )
            return
        }
        val edges = dependencies.findByParentJobId(parentJobId)
        for (edge in edges) {
            tryResolveChild(edge.childJobId, parent.status)
        }
    }

    /**
     * 주기 스캔 — WAITING_DEPS 인 child 들을 한 페이지씩 검사.
     * 이벤트 유실 / 잡이 parent 보다 늦게 등록된 경우 등 corner case 보강.
     *
     * WAITING_DEPS 인덱스로 직접 쿼리 → 잡 수가 많아도 active 한 잡만 로드. 한 tick 에
     * 한 페이지만 처리하고, 더 많으면 다음 tick 에서 이어서 (idempotent).
     */
    @Transactional
    fun scanWaitingJobs(): Int {
        val waiting = jobs.findWaitingForDependencies(PageRequest.of(0, SCAN_PAGE_SIZE))
        var promoted = 0
        var cancelled = 0
        for (child in waiting) {
            when (tryResolveChild(child.id, null)) {
                ResolutionOutcome.PROMOTED -> promoted++
                ResolutionOutcome.CASCADED_CANCEL -> cancelled++
                ResolutionOutcome.NOOP -> Unit
            }
        }
        if (promoted > 0 || cancelled > 0) {
            log.info(
                "dependency scan finished waiting={} promoted={} cascaded={}",
                waiting.size, promoted, cancelled,
            )
        }
        return promoted + cancelled
    }

    @Suppress("UNUSED_PARAMETER")   // `knownParentStatus` 는 향후 정책 분기 (예: PREEMPTED 한정 처리) 예약 슬롯.
    private fun tryResolveChild(childJobId: UUID, knownParentStatus: JobStatus?): ResolutionOutcome {
        val child = jobs.findById(childJobId).orElse(null)
        if (child == null) {
            log.warn("child job vanished id={}", childJobId)
            return ResolutionOutcome.NOOP
        }
        if (child.status != JobStatus.WAITING_DEPS) {
            // 이미 promote / cancel 됨 — idempotent
            return ResolutionOutcome.NOOP
        }

        val edges = dependencies.findByChildJobId(childJobId)
        if (edges.isEmpty()) {
            // 의존 없는데 WAITING_DEPS 라는 건 비정상 상태 — 그냥 promote
            child.markReadyToQueue(clock)
            jobs.save(child)
            log.warn("child={} had no parents but was WAITING — promoted", childJobId)
            return ResolutionOutcome.PROMOTED
        }

        // parent 들을 한 번의 IN-쿼리로 batch 로딩 (예전엔 edge 마다 findById → N+1).
        // parent 수가 많아도 SELECT 1 회로 끝나 promotion latency 가 parent 수와 무관해진다.
        val parentIds: Set<UUID> = edges.map { it.parentJobId }.toSet()
        val parentsById: Map<UUID, Job> = jobs.findAllById(parentIds).associateBy { it.id }

        var anyParentBlocked = false
        var anyParentFailed = false
        for (edge in edges) {
            val parent = parentsById[edge.parentJobId]
            if (parent == null) {
                // parent 가 사라졌으면 — 영영 만족 못함. cascade cancel.
                anyParentFailed = true
                break
            }
            val s = parent.status
            if (s == JobStatus.FAILED || s == JobStatus.CANCELLED) {
                anyParentFailed = true
                break
            }
            if (s != JobStatus.SUCCEEDED) {
                // RUNNING / DISPATCHING / QUEUED / WAITING_DEPS / PREEMPTED — 아직 대기
                anyParentBlocked = true
            }
        }

        if (anyParentFailed) {
            child.markCancelled(clock)
            val persisted = jobs.save(child)
            outboxWriter.write(
                JobEvent.JobCompleted(
                    child.id.toString(),
                    JobStatus.CANCELLED.name,
                    null,
                    "cascade-cancel from failed/cancelled parent",
                    clock.instant().toString(),
                ),
            )
            // Cascade-cancel 도 종착 — runtime 0 / cost 0 이지만 record 는 만든다.
            // 운영 dashboard 에서 "이 잡은 왜 안 돌고 cancelled 됐나" 추적 가능.
            costAttribution.recordCost(persisted)
            log.info("dep cascade-cancel child={} due to parent failure", childJobId)
            return ResolutionOutcome.CASCADED_CANCEL
        }
        if (anyParentBlocked) {
            return ResolutionOutcome.NOOP
        }
        // 모두 SUCCEEDED → promote
        child.markReadyToQueue(clock)
        jobs.save(child)
        log.info("dep promoted child={} (all parents succeeded)", childJobId)
        return ResolutionOutcome.PROMOTED
    }

    private enum class ResolutionOutcome { PROMOTED, CASCADED_CANCEL, NOOP }

    companion object {
        /**
         * 한 tick 에 검사할 WAITING_DEPS 잡 수의 상한 — 트랜잭션이 너무 길어지지 않게.
         * 잡이 더 많으면 다음 tick 에서 이어서 처리 (idempotent).
         */
        private const val SCAN_PAGE_SIZE = 500

        private val log = LoggerFactory.getLogger(DependencyResolutionService::class.java)
    }
}
