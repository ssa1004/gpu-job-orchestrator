package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.domain.*;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 쿼터 (사용자별 동시 실행 작업 / GPU 한도) 검사 + 기본값 부여.
 *
 * <p>동시성: 같은 사용자가 동시에 두 개를 제출하면 read-modify-write 사이에 둘 다 통과해서
 * 한도를 살짝 넘길 가능성 (over-commit). 단순화를 위해 단일 트랜잭션 안에서 읽기만 하고
 * (lock 없음), Job 생성 자체는 호출자 ({@link JobSubmissionService}) 가 같은 트랜잭션에서
 * INSERT. 엄격한 보장은 PG advisory lock (애플리케이션이 직접 잠금 키를 정해 거는 PG 전용
 * 락, 예: {@code pg_advisory_xact_lock}) 또는 SERIALIZABLE (가장 엄격한 트랜잭션 격리)
 * 로 강화. 현재 GPU Job 의 burst rate (요청이 한꺼번에 몰리는 정도) 가 낮아 시간이 지나면
 * 양쪽 데이터가 같아짐 (eventual consistency) 으로 충분.</p>
 *
 * <p>active job 카운트는 단일 aggregate 쿼리 ({@link JobRepository#sumActiveUsage} —
 * SUM/COUNT 한 번) 로 조회 → owner 의 모든 Job 을 메모리에 로드하지 않는다.</p>
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
