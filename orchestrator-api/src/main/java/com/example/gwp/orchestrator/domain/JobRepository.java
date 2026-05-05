package com.example.gwp.orchestrator.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
}
