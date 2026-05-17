package com.example.gwp.orchestrator.dlq

/**
 * bulk replay / discard 의 진행 상황 저장소 — 콘솔이 폴링 가능하도록.
 *
 * 구현은 InMemory (dev / 단위 테스트), Redis (운영). 한 인스턴스의 메모리에 두면
 * pod 가 죽었을 때 진행 중 job 의 상태를 잃지만, 대신 외부 의존을 한 겹 줄인다.
 * 운영에서는 RedisDlqBulkJobRepository 로 교체 — 본 ADR 의 다시 검토할 시점 참고.
 *
 * 같은 [DlqBulkJob.jobId] 에 두 번 save 가 호출되면 *덮어쓰기* — RUNNING → COMPLETED
 * 전이는 별 transition rule 없이 단순 last-write-wins.
 */
interface DlqBulkJobRepository {

    fun save(job: DlqBulkJob)

    fun findById(jobId: String): DlqBulkJob?
}
