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
 * <p><b>동시성</b>: 같은 owner 가 동시에 두 잡을 제출하면 read-modify-write 사이에
 * 둘 다 통과해서 한도를 살짝 넘길 수 있는 over-commit race 가 있다. 이를 막기 위해
 * 호출자 트랜잭션 시작 직후 {@link QuotaLock#acquireForOwner(String)} 로 owner
 * 단위 잠금을 잡는다 — 같은 owner 의 다른 quota 검사는 트랜잭션 commit / rollback
 * 까지 대기. 다른 owner 의 제출은 영향 없음 (per-owner 단위 잠금).</p>
 *
 * <p>운영 (Postgres) 에서는 {@link PgAdvisoryQuotaLock} 가 {@code pg_advisory_xact_lock}
 * 으로 잠금을 구현. H2 / 단위 테스트에서는 {@link NoopQuotaLock} 로 즉시 통과
 * (단일 인스턴스 인메모리라 race 자체가 없음). 활성 / 비활성 스위치는
 * {@code gwp.quota.advisory-lock-enabled} 설정.</p>
 *
 * <p>왜 row lock ({@code SELECT ... FOR UPDATE}) 이 아닌 advisory lock?
 * UserQuota row 는 신규 owner 의 첫 제출 시점에 아직 없을 수 있어 잠글 대상이 없다.
 * Advisory lock 은 임의 64bit key 로 잠금을 잡을 수 있어 row 존재 여부와 무관하게 동작.</p>
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
    private final QuotaLock quotaLock;
    private final Clock clock;
    private final GwpProperties properties;

    /**
     * @apiNote read-only 가 아니라 일반 트랜잭션이다 — advisory lock 이 트랜잭션 commit
     *          시점까지 유지되도록 보장하기 위함. 호출자 ({@link JobSubmissionService})
     *          가 같은 트랜잭션에서 INSERT 하므로 read-only 로 둘 수 없다.
     */
    @Transactional
    public void enforceForSubmission(String owner, int requestedGpus) {
        // owner 단위 잠금 — 같은 owner 의 다른 트랜잭션은 commit / rollback 까지 대기.
        // PG: pg_advisory_xact_lock(hash(owner)). H2 / 단위 테스트: noop.
        quotaLock.acquireForOwner(owner);

        UserQuota quota = quotaRepository.findByOwner(owner)
                .orElseGet(() -> defaultQuota(owner));

        OwnerActiveUsage usage = jobRepository.sumActiveUsage(owner, JobStatus.activeStatuses());

        boolean ok = usage.accommodates(requestedGpus, quota.getMaxConcurrentJobs(), quota.getMaxGpuCount());
        if (!ok) {
            log.info("quota exceeded owner_hash={} active_jobs={} active_gpus={} request_gpus={} max_jobs={} max_gpus={}",
                    OwnerLogMask.mask(owner), usage.activeJobs(), usage.totalGpus(), requestedGpus,
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
