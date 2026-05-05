package com.example.gwp.orchestrator.domain;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 쿼터 검사 + 기본값 부여.
 *
 * <p>동시성: read-modify-write 가 동시 실행되면 over-commit 가능성. 단순화를 위해 단일
 * 트랜잭션 안에서 읽기만 하고 (lock 없음), Job 생성 자체는 호출자({@link JobService})가 같은
 * 트랜잭션에서 INSERT. 엄격한 보장은 PG advisory lock (예: {@code pg_advisory_xact_lock}) 또는
 * SERIALIZABLE 격리로 강화. 현재 GPU Job 의 burst rate 가 낮아 eventual consistency 로 충분.</p>
 *
 * <p>active job 카운트는 단일 aggregate 쿼리({@link JobRepository#sumActiveUsage}) 로 조회 →
 * owner 의 모든 Job 을 메모리에 로드하지 않는다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaService {

    private final UserQuotaRepository quotaRepository;
    private final JobRepository jobRepository;
    private final Clock clock;
    private final GwpProperties properties;

    @Transactional(readOnly = true)
    public void enforceForSubmission(String owner, int requestedGpus) {
        UserQuota quota = quotaRepository.findByOwner(owner)
                .orElseGet(() -> defaultQuota(owner));

        OwnerActiveUsage usage = jobRepository.sumActiveUsage(owner, JobStatus.activeStatuses());

        boolean ok = usage.accommodates(requestedGpus, quota.getMaxConcurrentJobs(), quota.getMaxGpuCount());
        if (!ok) {
            log.info("quota exceeded owner={} active_jobs={} active_gpus={} request_gpus={} max_jobs={} max_gpus={}",
                    owner, usage.activeJobs(), usage.totalGpus(), requestedGpus,
                    quota.getMaxConcurrentJobs(), quota.getMaxGpuCount());
            throw new QuotaExceededException(String.format(
                    "quota exceeded for owner=%s: active_jobs=%d/%d, active_gpus=%d/%d, requested=%d",
                    owner, usage.activeJobs(), quota.getMaxConcurrentJobs(),
                    usage.totalGpus(), quota.getMaxGpuCount(), requestedGpus));
        }
    }

    private UserQuota defaultQuota(String owner) {
        var quota = properties.quota();
        return UserQuota.builder()
                .owner(owner)
                .maxConcurrentJobs(quota.defaultMaxConcurrentJobs())
                .maxGpuCount(quota.defaultMaxGpuCount())
                .updatedAt(clock.instant())
                .build();
    }
}
