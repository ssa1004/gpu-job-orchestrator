package com.example.gwp.orchestrator.domain;

/**
 * owner 의 현재 active job 카운트 + 점유 GPU 합계. 쿼터 검사용 read-only 값.
 *
 * <p>JPQL constructor projection 의 limitation: SUM() 결과가 Long 또는 null 이라
 * COALESCE 으로 0 보장. JPQL 에서 primitive 못 받음.</p>
 */
public record OwnerActiveUsage(long activeJobs, long totalGpus) {

    public boolean accommodates(int requestedGpus, int maxJobs, int maxGpus) {
        if (activeJobs + 1 > maxJobs) return false;
        if (totalGpus + requestedGpus > maxGpus) return false;
        return true;
    }
}
