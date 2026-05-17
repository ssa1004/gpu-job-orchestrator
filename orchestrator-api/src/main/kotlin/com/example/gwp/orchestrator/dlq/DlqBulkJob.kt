package com.example.gwp.orchestrator.dlq

import java.time.Instant

/**
 * bulk replay / discard 의 진행 상태. dry-run 결과도 같은 모양으로 반환되어 콘솔이
 * 한 화면에서 미리보기 → 실행 → 폴링을 같은 데이터 모델로 처리할 수 있다.
 *
 * - [jobId] — `GET /api/v1/admin/dlq/bulk-jobs/{jobId}` 폴링 키. dry-run 도 job 으로
 *   기록되어 audit log 에 남는다 (어떤 운영자가 어떤 필터로 dry-run 했는지).
 * - [action] — REPLAY 또는 DISCARD.
 * - [source] — bulk 의 source. market ADR-0028 패턴 — 한 번에 한 source 만.
 * - [filter] — 매칭에 사용된 필터 (운영자 화면에 다시 보여주기 위함).
 * - [dryRun] — true 면 매칭 결과만 [result] 에 채우고 실제 변경 X.
 * - [status] — PENDING / RUNNING / COMPLETED / FAILED.
 * - [result] — 처리 결과 (matched / succeeded / failed / skipped / 시작 / 종료 시각).
 */
data class DlqBulkJob(
    val jobId: String,
    val action: Action,
    val source: DlqSource,
    val filter: DlqEntryFilter,
    val dryRun: Boolean,
    val status: Status,
    val result: DlqBulkResult,
    val createdAt: Instant,
    val actor: String,
    val reason: String?,
) {
    enum class Action { REPLAY, DISCARD }
    enum class Status { PENDING, RUNNING, COMPLETED, FAILED }
}
