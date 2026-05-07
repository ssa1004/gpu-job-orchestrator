package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.cost.CostRate;
import com.example.gwp.orchestrator.cost.CostRateProvider;
import com.example.gwp.orchestrator.cost.JobCostRecord;
import com.example.gwp.orchestrator.cost.JobCostRecordRepository;
import com.example.gwp.orchestrator.domain.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Job 종착 시 cost 계산 후 영구 기록 — FinOps 기반.
 *
 * <p><b>호출 시점</b>: {@link JobLifecycleService} 가 Job 을 종착 상태 (SUCCEEDED / FAILED /
 * CANCELLED / PREEMPTED) 로 전이 직후. *같은 트랜잭션* — Job commit 과 cost record 가 원자적.
 * 한 잡이 두 번 종착되는 race 는 lifecycle 의 invariant 가 막고, 만약 race 가 뚫려도 UNIQUE 가
 * 두 번째 INSERT 거절.</p>
 *
 * <p><b>왜 같은 트랜잭션?</b> *cost 누락 사고* 방지. Job 은 SUCCEEDED 됐는데 cost 가 안 잡혀
 * 영영 빌링 안 된다면 회계 사고. 같은 트랜잭션이라 commit 같이 / rollback 같이.</p>
 *
 * <p><b>왜 startedAt null 도 허용?</b> dispatch 실패한 잡은 RUNNING 한 적 없어 runtime 0,
 * cost 0. 그래도 record 는 만들어야 *어떤 잡이 dispatch 실패 였는지* 운영에서 추적 가능.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CostAttributionService {

    private final JobCostRecordRepository costRecords;
    private final CostRateProvider rateProvider;
    private final Clock clock;

    @Transactional
    public void recordCost(Job job) {
        if (!job.getStatus().isTerminal()) {
            log.warn("recordCost called for non-terminal job id={} status={}",
                    job.getId(), job.getStatus());
            return;
        }
        if (job.getFinishedAt() == null) {
            log.warn("recordCost: job has no finishedAt id={}", job.getId());
            return;
        }
        CostRate rate = rateProvider.current();
        JobCostRecord record = JobCostRecord.forJob(
                job.getId(), job.getOwner(), job.getGpuCount(),
                job.getStartedAt(), job.getFinishedAt(),
                job.getStatus(), rate, clock.instant()
        );
        try {
            costRecords.save(record);
            log.info("cost recorded job={} owner={} runtime={}ms cost={}",
                    job.getId(), job.getOwner(), record.getRuntimeMillis(), record.getComputedCost());
        } catch (DataIntegrityViolationException dup) {
            // UNIQUE(job_id) — 이미 기록됨. 멱등 — 무해.
            log.debug("cost already recorded for job={}", job.getId());
        }
    }
}
