package com.example.gwp.orchestrator.application

import com.example.gwp.orchestrator.cost.CostRateProvider
import com.example.gwp.orchestrator.cost.JobCostRecord
import com.example.gwp.orchestrator.cost.JobCostRecordRepository
import com.example.gwp.orchestrator.domain.Job
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Job 종착 시 cost (사용 비용) 계산 후 영구 기록 — FinOps (인프라 비용을 운영 지표로
 * 관리하는 실무) 기반.
 *
 * 호출 시점: [JobLifecycleService] 가 Job 을 종착 상태 (SUCCEEDED / FAILED / CANCELLED
 * / PREEMPTED) 로 전이 직후. *같은 트랜잭션* — Job commit 과 cost record 가 원자적.
 * 한 잡이 두 번 종착되는 race (경쟁 상태) 는 lifecycle 의 불변 조건이 막고, 만약 race
 * 가 뚫려도 DB 의 UNIQUE 제약이 두 번째 INSERT 거절.
 *
 * 왜 같은 트랜잭션? *cost 누락 사고* 방지. Job 은 SUCCEEDED 됐는데 cost 가 안 잡혀 영영
 * 빌링 안 된다면 회계 사고. 같은 트랜잭션이라 commit 같이 / rollback 같이.
 *
 * 왜 startedAt null 도 허용? dispatch 실패한 잡은 RUNNING 한 적 없어 runtime 0, cost 0.
 * 그래도 record 는 만들어야 *어떤 잡이 dispatch 실패였는지* 운영에서 추적 가능.
 *
 * Java 호출자 (Test / 다른 service) 무변경 — Kotlin primary constructor 가 같은 positional
 * 시그니처. `plugin.spring` 이 `@Service` 자동 open.
 */
@Service
class CostAttributionService(
    private val costRecords: JobCostRecordRepository,
    private val rateProvider: CostRateProvider,
    private val clock: Clock,
) {

    @Transactional
    fun recordCost(job: Job) {
        if (!job.status.isTerminal()) {
            log.warn(
                "recordCost called for non-terminal job id={} status={}",
                job.id, job.status,
            )
            return
        }
        if (job.finishedAt == null) {
            log.warn("recordCost: job has no finishedAt id={}", job.id)
            return
        }
        val rate = rateProvider.current()
        val record = JobCostRecord.forJob(
            job.id, job.owner, job.gpuCount,
            job.startedAt, job.finishedAt!!,
            job.status, rate, clock.instant(),
        )
        try {
            costRecords.save(record)
            log.info(
                "cost recorded job={} owner_hash={} runtime={}ms cost={}",
                job.id, OwnerLogMask.mask(job.owner),
                record.runtimeMillis, record.computedCost,
            )
        } catch (dup: DataIntegrityViolationException) {
            // UNIQUE(job_id) 제약에 의해 이미 기록된 잡은 두 번째 INSERT 가 거절됨.
            // 두 번 호출돼도 결과가 같으면 되므로 (멱등) 무해.
            log.debug("cost already recorded for job={}", job.id)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CostAttributionService::class.java)
    }
}
