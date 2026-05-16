package com.example.gwp.orchestrator.application

import com.example.gwp.orchestrator.adapter.kubernetes.JobDispatcher
import com.example.gwp.orchestrator.domain.Job
import com.example.gwp.orchestrator.domain.JobNotFoundException
import com.example.gwp.orchestrator.domain.JobRepository
import com.example.gwp.orchestrator.domain.JobStatus
import com.example.gwp.orchestrator.lifecycle.JobLifecycleEvent
import com.example.gwp.orchestrator.lifecycle.JobLifecycleStateMachine
import com.example.gwp.orchestrator.observability.JobMetrics
import com.example.gwp.orchestrator.outbox.JobEvent
import com.example.gwp.orchestrator.outbox.OutboxWriter
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Job 라이프사이클 변경 책임 — 워커 콜백 + 사용자 취소.
 *
 * `@CacheEvict` 가 정상 동작하려면 외부 컴포넌트에서 호출되어야 함. Spring AOP 프록시는
 * 빈 사이의 메서드 호출만 가로채기 때문에, 같은 클래스 안에서 직접 부르면 캐시 무효화가
 * 일어나지 않는다 (self-invocation 문제).
 *
 * 같은 트랜잭션 안에서 Outbox (DB 안의 발신함 테이블) 도 발행해서 DB UPDATE 와 이벤트
 * INSERT 가 함께 commit / 함께 rollback 되도록 보장.
 *
 * Java 호출자 (InternalCallbackController / JobAccessControl / Test) 무변경 —
 * Kotlin primary constructor 가 같은 positional 시그니처. `plugin.spring` 이 `@Service`
 * 자동 open, `@CacheEvict` / `@Transactional` 적용을 위해 메서드도 자동 open.
 *
 * @param lifecycleStateMachine 도메인 메서드 호출 *전에* workflow 어휘 측 transition 이
 *   정의되어 있는지 검증하는 사이드카. 도메인 객체를 변경하지 않는 read-only 검증 + audit
 *   용. 정의되지 않은 transition 이면
 *   [com.example.gwp.orchestrator.lifecycle.IllegalJobLifecycleTransitionException]
 *   으로 거절 → callback handler 가 받아 4xx 로 반환. ADR-0022 참고.
 */
@Service
class JobLifecycleService(
    private val jobRepository: JobRepository,
    private val jobDispatcher: JobDispatcher,
    private val jobMetrics: JobMetrics,
    private val outboxWriter: OutboxWriter,
    private val dependencyResolution: DependencyResolutionService,
    private val costAttribution: CostAttributionService,
    private val lifecycleStateMachine: JobLifecycleStateMachine,
    private val clock: Clock,
) {

    @CacheEvict(cacheNames = ["jobs"], key = "#id")
    @Transactional
    fun updateStatusFromCallback(id: UUID, newStatus: JobStatus, resultUri: String?, errorMessage: String?): Job {
        val job = jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }

        if (job.status.isTerminal()) {
            log.warn(
                "ignored callback for terminal job id={} from={} to={}",
                id, job.status, newStatus,
            )
            return job
        }

        // 도메인 메서드 호출 *전* 에 transition table (lifecycleStateMachine) 측에서도
        // 같은 전이를 허용하는지 검증. 두 방어선이 동시에 fail 하면 도메인 / workflow 가 즉시
        // 멈춤 — 새 상태로 넘어가는 사고 면적 0.
        when (newStatus) {
            JobStatus.RUNNING -> {
                lifecycleStateMachine.fire(job.status, JobLifecycleEvent.RUN, job)
                job.markRunning(clock)
            }
            JobStatus.SUCCEEDED -> {
                lifecycleStateMachine.fire(job.status, JobLifecycleEvent.SUCCEED, job)
                job.markSucceeded(resultUri!!, clock)
                jobMetrics.recordSucceeded()
            }
            JobStatus.FAILED -> {
                lifecycleStateMachine.fire(job.status, JobLifecycleEvent.FAIL, job)
                job.markFailed(errorMessage!!, clock)
                jobMetrics.recordFailed()
            }
            else -> throw IllegalArgumentException("unsupported callback status: $newStatus")
        }

        val persisted = jobRepository.save(job)
        if (newStatus.isTerminal()) {
            outboxWriter.write(
                JobEvent.JobCompleted(
                    persisted.id.toString(),
                    persisted.status.name,
                    persisted.resultUri ?: "",
                    persisted.errorMessage ?: "",
                    persisted.finishedAt?.toString() ?: "",
                ),
            )
            // Dependency cascade — 이 잡을 parent 로 갖는 child 들을 자동으로 promote
            // (이 잡이 SUCCEEDED 면 child 를 QUEUED 로) 또는 cancel (이 잡이 FAILED /
            // CANCELLED 면 child 도 CANCELLED). 같은 트랜잭션 안이라 child 처리가 parent
            // commit 과 같이 원자적으로 적용됨.
            dependencyResolution.onParentTerminal(persisted.id)
            // Cost 기록 — 끝난 시점의 단가 / GPU-시간 을 그대로 보관 (FeeSnapshot 패턴).
            // 같은 트랜잭션이라 cost 누락이 발생할 수 없다. 잡 SUCCEEDED commit 이 됐다면
            // cost record 도 무조건 같이 commit 된다.
            costAttribution.recordCost(persisted)
        }
        return persisted
    }

    @CacheEvict(cacheNames = ["jobs"], key = "#id")
    @Transactional
    fun cancel(id: UUID): Job {
        val job = jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }

        if (job.status.isTerminal()) {
            return job   // 멱등 (idempotent — 두 번 호출해도 결과 같음). 이미 종료된 Job 은 그대로 반환
        }
        val k8sName = job.k8sJobName
        if (k8sName != null) {
            jobDispatcher.cancel(k8sName)
        }
        lifecycleStateMachine.fire(job.status, JobLifecycleEvent.CANCEL, job)
        job.markCancelled(clock)
        jobMetrics.recordCancelled()

        val persisted = jobRepository.save(job)
        outboxWriter.write(
            JobEvent.JobCompleted(
                persisted.id.toString(),
                persisted.status.name,
                "",
                "",
                persisted.finishedAt?.toString() ?: "",
            ),
        )
        // 사용자 cancel 도 parent terminal — 이 잡을 parent 로 둔 child 들도 함께 자동
        // 취소 (cascade-cancel) 시킴. 부모가 죽었으니 자식의 input 이 사라진 상태.
        dependencyResolution.onParentTerminal(persisted.id)
        // Cancel 도 *그때까지 사용한 GPU-시간* (1 GPU 가 1 시간 동안 점유한 양) 은
        // 청구 — 사용자 잘못이라 회계상 정당.
        costAttribution.recordCost(persisted)
        return persisted
    }

    companion object {
        private val log = LoggerFactory.getLogger(JobLifecycleService::class.java)
    }
}
