package com.example.gwp.orchestrator.cost;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JobCostRecordRepository extends JpaRepository<JobCostRecord, UUID> {

    /** 한 잡의 cost record (보통 1건). UNIQUE 제약 덕에 최대 1건. */
    List<JobCostRecord> findByJobId(UUID jobId);

    /** 특정 owner 의 시간 구간 cost 합계 — owner 별 청구서. */
    @Query("""
            SELECT new com.example.gwp.orchestrator.cost.JobCostRecordRepository$OwnerCostSummary(
                COUNT(c),
                COALESCE(SUM(c.runtimeMillis), 0),
                COALESCE(SUM(c.gpuCount * c.runtimeMillis), 0),
                COALESCE(SUM(c.computedCost), 0)
            )
              FROM JobCostRecord c
             WHERE c.owner = :owner
               AND c.recordedAt >= :from
               AND c.recordedAt <  :to
            """)
    OwnerCostSummary aggregateByOwner(@Param("owner") String owner,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to);

    /** 시간 구간의 전체 cost (모든 owner 합산). */
    @Query("""
            SELECT new com.example.gwp.orchestrator.cost.JobCostRecordRepository$OwnerCostSummary(
                COUNT(c),
                COALESCE(SUM(c.runtimeMillis), 0),
                COALESCE(SUM(c.gpuCount * c.runtimeMillis), 0),
                COALESCE(SUM(c.computedCost), 0)
            )
              FROM JobCostRecord c
             WHERE c.recordedAt >= :from
               AND c.recordedAt <  :to
            """)
    OwnerCostSummary aggregateAll(@Param("from") Instant from,
                                  @Param("to") Instant to);

    /**
     * Owner 별 group 집계 — top spender 운영 dashboard.
     * 결과 row: [owner, jobCount, totalRuntimeMillis, totalGpuMillis, totalCost]
     */
    @Query("""
            SELECT c.owner,
                   COUNT(c),
                   COALESCE(SUM(c.runtimeMillis), 0),
                   COALESCE(SUM(c.gpuCount * c.runtimeMillis), 0),
                   COALESCE(SUM(c.computedCost), 0)
              FROM JobCostRecord c
             WHERE c.recordedAt >= :from
               AND c.recordedAt <  :to
             GROUP BY c.owner
             ORDER BY COALESCE(SUM(c.computedCost), 0) DESC
            """)
    List<Object[]> aggregateByOwnerGroup(@Param("from") Instant from,
                                         @Param("to") Instant to,
                                         Pageable pageable);

    /**
     * 결과 record DTO — JPQL constructor expression 으로 매핑.
     * jobCount=0 이면 다른 필드는 0.
     */
    record OwnerCostSummary(
            long jobCount,
            long totalRuntimeMillis,
            long totalGpuMillis,        // gpuCount × runtime — "GPU-시간" 의 millis 단위
            BigDecimal totalCost
    ) {}
}
