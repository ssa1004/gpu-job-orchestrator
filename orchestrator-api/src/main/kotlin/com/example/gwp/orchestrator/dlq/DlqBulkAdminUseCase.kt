package com.example.gwp.orchestrator.dlq

/**
 * bulk replay / discard 의 application port. dry-run 강제 — `confirm=true` 가
 * 없으면 실제 변경 없이 매칭 결과만 반환 (market ADR-0028 패턴).
 *
 * source 는 필수 — 한 번에 한 saga 단계만 조작 (POST 컨트롤러 단에서 검증).
 */
interface DlqBulkAdminUseCase {

    fun bulkReplay(
        filter: DlqEntryFilter,
        confirm: Boolean,
        actor: String,
        actorKey: String,
        reason: String?,
    ): DlqBulkJob

    fun bulkDiscard(
        filter: DlqEntryFilter,
        confirm: Boolean,
        actor: String,
        actorKey: String,
        reason: String?,
    ): DlqBulkJob

    fun findJob(jobId: String): DlqBulkJob?
}
