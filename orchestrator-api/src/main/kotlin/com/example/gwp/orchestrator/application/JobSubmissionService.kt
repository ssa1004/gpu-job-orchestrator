package com.example.gwp.orchestrator.application

import com.example.gwp.orchestrator.adapter.kubernetes.JobDispatcher
import com.example.gwp.orchestrator.domain.DependencyGraph
import com.example.gwp.orchestrator.domain.Job
import com.example.gwp.orchestrator.domain.JobDependency
import com.example.gwp.orchestrator.domain.JobDependencyRepository
import com.example.gwp.orchestrator.domain.JobNotFoundException
import com.example.gwp.orchestrator.domain.JobRepository
import com.example.gwp.orchestrator.domain.JobSpec
import com.example.gwp.orchestrator.domain.JobStatus
import com.example.gwp.orchestrator.observability.JobMetrics
import com.example.gwp.orchestrator.outbox.JobEvent
import com.example.gwp.orchestrator.outbox.OutboxWriter
import io.micrometer.tracing.Tracer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID
import java.util.function.Supplier

/**
 * Job 제출 책임 — 쿼터 검사 → DB 저장 → K8s 디스패치 (Kubernetes Job 생성 요청) →
 * Outbox 발행 → 메트릭 기록.
 *
 * 한 트랜잭션 안에서 DB INSERT 와 Outbox INSERT (DB 안의 발신함 테이블에 이벤트 row
 * 추가) 가 같이 commit 되어 원자성 보장 — DB 변경과 이벤트 발행 의도가 분리되지 않게.
 * Kafka publish 는 OutboxRelay 가 별도 트랜잭션으로 처리한다.
 *
 * dispatch 실패는 catch 하여 Job 을 FAILED 로 기록하고 client 에게는 정상 응답 (job ID
 * 발급 + status FAILED) — 그래야 client 가 재시도 시 새 ID 가 아닌 같은 ID 로 추적 가능.
 *
 * Java 호출자 (Controller / Test) 무변경 — Kotlin primary constructor 가 같은 positional
 * 시그니처. `plugin.spring` 이 `@Service` 자동 open.
 */
@Service
class JobSubmissionService(
    private val jobRepository: JobRepository,
    private val jobDependencyRepository: JobDependencyRepository,
    private val jobDispatcher: JobDispatcher,
    private val jobMetrics: JobMetrics,
    private val tracer: Tracer,
    private val quotaService: QuotaService,
    private val outboxWriter: OutboxWriter,
    private val costAttribution: CostAttributionService,
    private val clock: Clock,
) {

    /** Parent 없는 일반 잡 — 즉시 dispatch path. */
    @Transactional
    fun submit(spec: JobSpec): Job = submit(spec, emptySet())

    /**
     * Parent 의존성을 갖는 잡 제출. parent 가 비어 있으면 일반 잡과 동일.
     *
     * **흐름**:
     * 1. 쿼터 검사 (parent 와 무관 — 잡 자체의 자원 한도)
     * 2. parent 들이 모두 *존재* 하는지 + cycle (잡 A→B→C→A 처럼 끝없이 도는 의존
     *    관계) 검사 — 영속화 전에 거절
     * 3. parent 가 비어 있으면 일반 dispatch / 있으면 WAITING_DEPS 로 저장 + 의존
     *    관계 row (edge) 영속
     * 4. 이미 모든 parent 가 SUCCEEDED 면 즉시 promote (race 시 scheduler 가 보강)
     *
     * [JobMetrics.submitTimer] 로 wall-clock 측정 — histogram bucket 마다 exemplar
     * (이 호출의 traceId) 가 attached 되어, p95 spike 발생 시 Grafana 에서 해당 bucket
     * 클릭 → 그 spike 를 일으킨 *실제 trace* 로 바로 jump 가능 (ADR-0019).
     */
    @Transactional
    fun submit(spec: JobSpec, parentJobIds: Set<UUID>?): Job =
        jobMetrics.submitTimer().record(Supplier { doSubmit(spec, parentJobIds) })!!

    private fun doSubmit(spec: JobSpec, parentJobIds: Set<UUID>?): Job {
        quotaService.enforceForSubmission(spec.owner, spec.gpuCount)
        val traceId = currentTraceId()

        if (parentJobIds.isNullOrEmpty()) {
            // 일반 잡 — 기존 path 그대로
            val job = Job.submit(spec, traceId, clock)
            jobRepository.save(job)
            jobMetrics.recordSubmitted()
            log.info(
                "job submitted id={} owner_hash={} image={} gpu={}",
                job.id, OwnerLogMask.mask(spec.owner),
                ImageLogMask.mask(spec.image), spec.gpuCount,
            )
            dispatchOrFail(job)
            val persisted = jobRepository.save(job)
            publishSubmittedEvent(persisted, spec, traceId)
            return persisted
        }

        // 1. parent 들 모두 존재 검증
        for (parentId in parentJobIds) {
            if (!jobRepository.existsById(parentId)) {
                throw JobNotFoundException(parentId)
            }
        }

        // 2. cycle 검사 — 새 잡이 추가되면서 사이클 만들지 않는지 (기존 그래프 + 새 edges).
        //    아직 영속화 전이라 placeholder UUID 로 검사 — INSERT 는 cycle-free 확인 후 진행.
        val placeholderId = UUID.randomUUID()
        validateNoCycle(placeholderId, parentJobIds)

        // 3. 잡 + edges 저장 (WAITING_DEPS) — 도메인 factory 사용 (id 는 도메인이 발급)
        val job = Job.submit(spec, traceId, true, clock)
        val newJobId = job.id
        jobRepository.save(job)
        val now = clock.instant()
        for (parentId in parentJobIds) {
            jobDependencyRepository.save(JobDependency.edge(newJobId, parentId, now))
        }
        jobMetrics.recordSubmitted()
        log.info(
            "job submitted with deps id={} owner_hash={} parents={}",
            newJobId, OwnerLogMask.mask(spec.owner), parentJobIds,
        )

        // 4. parent 가 *이미 모두 SUCCEEDED* 인 corner case — 즉시 promote.
        //    아니면 그냥 WAITING_DEPS 로 두고 scheduler / parent terminal hook 이 처리.
        if (allParentsAlreadySucceeded(parentJobIds)) {
            job.markReadyToQueue(clock)
            jobRepository.save(job)
            log.info("job id={} parents already done — promoted immediately", newJobId)
            // 일반 dispatch path 로 진행
            dispatchOrFail(job)
            val persisted = jobRepository.save(job)
            publishSubmittedEvent(persisted, spec, traceId)
            return persisted
        }
        publishSubmittedEvent(job, spec, traceId)
        return job
    }

    /**
     * 새 잡 `newJobId` → `newParents` edge 를 추가했을 때 cycle 이 생기는지 검사.
     *
     * **핵심 아이디어**: cycle 검사를 *전체* 의존성 그래프가 아니라 새 잡과 관련된
     * 부분 그래프 (subgraph) 만으로 줄인다. 새 잡은 leaf (아직 어떤 parent 에도 등록 안
     * 됨) 라 cycle 이 생기는 시나리오는 단 하나 — 새 잡의 parent 중 하나의 조상이 새
     * 잡 자신인 경우다. 따라서 새 잡의 parents 부터 거슬러 올라가며 닿는 노드만 검사하면
     * 충분.
     *
     * 예: 기존 그래프 `A → B`, `C → D` 에서 새 잡 X 가 parent {A} 와 함께 들어오면 — A
     * 의 조상만 따라가면 됨 (`{A} → {}`). C/D 그래프는 X 와 무관해 검사 대상 X.
     *
     * **레벨 단위 batch 로딩**: 한 레벨의 모든 노드를 한 IN-쿼리로 묶어 부모 edge 를
     * 가져온다. 깊이 D / 평균 fan-in F 의 그래프에서 round-trip 이 노드 수 (D × F) 가
     * 아닌 깊이 (D) 만큼만 든다. 1000 개 노드 / 깊이 5 라면 5 SELECT 로 끝남 — 예전
     * per-node 쿼리 대비 round-trip 200배 절감.
     */
    private fun validateNoCycle(newJobId: UUID, newParents: Set<UUID>) {
        // child → parents 인접 리스트. 새 잡이 root, 그 위로 거슬러 올라가며 채운다.
        val graph: MutableMap<UUID, Set<UUID>> = HashMap()
        graph[newJobId] = LinkedHashSet(newParents)
        val seen: MutableSet<UUID> = HashSet()
        seen.add(newJobId)

        // BFS 한 레벨 = 한 IN-쿼리. frontier 는 *아직 부모를 안 가져온* 노드 집합.
        var frontier: Set<UUID> = LinkedHashSet(newParents)
        while (frontier.isNotEmpty()) {
            // 한 레벨 batch — IN 쿼리 1회로 모든 부모 edge 로드.
            val edges = jobDependencyRepository.findByChildJobIdIn(frontier)
            // child 별로 부모를 묶는다 — graph 인접 리스트에 그대로 들어간다.
            val parentsByChild: MutableMap<UUID, MutableSet<UUID>> = HashMap()
            for (edge in edges) {
                parentsByChild.getOrPut(edge.childJobId) { HashSet() }.add(edge.parentJobId)
            }
            seen.addAll(frontier)
            val nextFrontier: MutableSet<UUID> = LinkedHashSet()
            for (child in frontier) {
                val parents = parentsByChild[child]
                if (parents == null || parents.isEmpty()) continue
                graph[child] = parents
                for (p in parents) {
                    if (seen.add(p)) {   // 처음 보는 부모만 다음 레벨로
                        nextFrontier.add(p)
                    }
                }
            }
            frontier = nextFrontier
        }
        DependencyGraph.detectCycle(graph)
    }

    private fun allParentsAlreadySucceeded(parentJobIds: Set<UUID>): Boolean {
        val parents = jobRepository.findAllById(parentJobIds)
        if (parents.size != parentJobIds.size) return false   // 누락 = 즉시 promote 안 함
        return parents.all { it.status == JobStatus.SUCCEEDED }
    }

    private fun dispatchOrFail(job: Job) {
        try {
            val k8sJobName = jobDispatcher.dispatch(job)
            job.markDispatched(k8sJobName, clock)
        } catch (e: Exception) {
            log.error("dispatch failed id={} reason={}", job.id, e.message, e)
            job.markFailed("dispatch failed: " + e.message, clock)
            jobMetrics.recordFailed()
            // dispatch 실패 = RUNNING 한 적 없음 → runtime 0, cost 0.
            // 그래도 record 는 만든다 — *어떤 잡이 dispatch 실패했는지* 운영에서 추적 가능.
            costAttribution.recordCost(job)
        }
    }

    private fun publishSubmittedEvent(job: Job, spec: JobSpec, traceId: String?) {
        outboxWriter.write(
            JobEvent.JobSubmitted(
                job.id.toString(),
                spec.owner,
                spec.image,
                spec.gpuCount,
                spec.priority.name,
                job.status.name,
                traceId ?: "",
            ),
        )
    }

    private fun currentTraceId(): String? {
        val span = tracer.currentSpan()
        return span?.context()?.traceId()
    }

    companion object {
        private val log = LoggerFactory.getLogger(JobSubmissionService::class.java)
    }
}
