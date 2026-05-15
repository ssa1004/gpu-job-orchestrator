package com.example.gwp.orchestrator.domain

/**
 * owner 의 현재 active job 카운트 + 점유 GPU 합계. 쿼터 검사용 read-only 값.
 *
 * JPQL constructor projection 의 limitation: SUM() 결과가 Long 또는 null 이라
 * COALESCE 으로 0 보장. JPQL 에서 primitive 못 받음.
 *
 * `@JvmRecord data class` — Java 호출자 (`usage.activeJobs()` / `usage.totalGpus()`
 * record-style accessor) 그대로 동작. JPQL `SELECT new OwnerActiveUsage(...)` 도
 * record 의 canonical 생성자를 그대로 호출.
 */
@JvmRecord
data class OwnerActiveUsage(val activeJobs: Long, val totalGpus: Long) {

    fun accommodates(requestedGpus: Int, maxJobs: Int, maxGpus: Int): Boolean {
        if (activeJobs + 1 > maxJobs) return false
        if (totalGpus + requestedGpus > maxGpus) return false
        return true
    }
}
