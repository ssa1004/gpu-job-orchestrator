package com.example.gwp.orchestrator.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    Page<Job> findByOwner(String owner, Pageable pageable);

    Page<Job> findByOwnerAndStatus(String owner, JobStatus status, Pageable pageable);

    /**
     * 쿼터 검사용 — owner 의 active(QUEUED/DISPATCHING/RUNNING) Job 수와 GPU 합계를
     * 단일 쿼리로 반환. 모든 Job 을 메모리에 로드하지 않는다.
     */
    @Query("""
            SELECT new com.example.gwp.orchestrator.domain.OwnerActiveUsage(
                COUNT(j),
                COALESCE(SUM(j.gpuCount), 0)
            )
            FROM Job j
            WHERE j.owner = :owner AND j.status IN :statuses
            """)
    OwnerActiveUsage sumActiveUsage(@Param("owner") String owner,
                                    @Param("statuses") Collection<JobStatus> statuses);

    /**
     * Preempt 평가에 필요한 후보 — RUNNING / DISPATCHING 인 PREEMPTABLE 잡들.
     *
     * <p>실제 victim markPreempted 시점에 OptimisticLock 으로 race 보호
     * (다른 트랜잭션이 같은 row 를 동시 수정 시 OptimisticLockException → 호출자가 evaluate 다시).</p>
     */
    @Query("""
            SELECT j FROM Job j
             WHERE j.status IN :activeStatuses
               AND j.preemptionPolicy = com.example.gwp.orchestrator.domain.PreemptionPolicy.PREEMPTABLE
            """)
    java.util.List<Job> findActivePreemptables(@Param("activeStatuses") Collection<JobStatus> activeStatuses);

    /** Scheduler 가 매분 호출 — QUEUED 중 priority 높은 + 오래 기다린 순. */
    @Query("""
            SELECT j FROM Job j
             WHERE j.status = com.example.gwp.orchestrator.domain.JobStatus.QUEUED
             ORDER BY j.priority DESC, j.createdAt ASC
            """)
    java.util.List<Job> findQueuedForScheduling(Pageable pageable);

    /**
     * Dependency scanner 용 — 한 페이지 분량의 WAITING_DEPS 잡만 로드.
     *
     * <p>예전 구현은 {@code findAll().stream().filter(...)} 였는데 잡 수가 많아지면
     * 모든 잡을 메모리로 끌어올려 OOM 위험. status 인덱스를 타도록 직접 쿼리 + 페이지 단위.</p>
     */
    @Query("""
            SELECT j FROM Job j
             WHERE j.status = com.example.gwp.orchestrator.domain.JobStatus.WAITING_DEPS
             ORDER BY j.createdAt ASC
            """)
    java.util.List<Job> findWaitingForDependencies(Pageable pageable);

    /**
     * 한 잡의 *현재* status 만 가져오는 가벼운 projection — entity 가 아닌 scalar 라
     * 1차 캐시에 잡혀 있던 옛 entity 와 무관하게 매번 DB 를 본다. preemption tick 같이
     * 'snapshot 후 시간 지났는데 victim 이 그 사이 종착했는지' 같은 race window 를 짧게
     * 닫는 용도. 잡이 사라진 corner 면 empty.
     */
    @Query("SELECT j.status FROM Job j WHERE j.id = :id")
    Optional<JobStatus> findCurrentStatusById(@Param("id") UUID id);
}
