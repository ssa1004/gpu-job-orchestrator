package com.example.gwp.orchestrator.dlq

import java.util.concurrent.ConcurrentHashMap

/**
 * 진행 중 / 완료된 bulk job 을 단순히 ConcurrentHashMap 에 보관한다. 같은 jobId 의
 * 재-save 는 마지막 write 가 이긴다 (RUNNING → COMPLETED 전이용).
 *
 * 운영에서는 Redis hash 로 교체 — 본 ADR '다시 검토할 시점' 참고 (pod 가 죽었을 때
 * 진행 중 job 의 가시성 유지).
 */
class InMemoryDlqBulkJobRepository : DlqBulkJobRepository {

    private val store = ConcurrentHashMap<String, DlqBulkJob>()

    override fun save(job: DlqBulkJob) {
        store[job.jobId] = job
    }

    override fun findById(jobId: String): DlqBulkJob? = store[jobId]
}
