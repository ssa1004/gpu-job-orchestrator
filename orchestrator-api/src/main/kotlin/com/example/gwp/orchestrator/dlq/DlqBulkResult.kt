package com.example.gwp.orchestrator.dlq

import java.time.Instant

/**
 * bulk job 처리 결과 카운터 + 시간.
 *
 * - [matched] — 필터에 걸린 메시지 수.
 * - [succeeded] — replay / discard 가 성공한 수.
 * - [failed] — 실패한 수 (도메인 거절 / I/O 오류 등).
 * - [skipped] — already-terminal job 의 콜백 등 멱등 처리로 *동작 자체가 의미 없어*
 *   skip 한 수. 콘솔에 별로 표시 — 실패가 아니라 정상.
 * - [startedAt] / [finishedAt] — dry-run 도 동일 (시작 ~ 매칭 종료 시각).
 */
data class DlqBulkResult(
    val matched: Long,
    val succeeded: Long,
    val failed: Long,
    val skipped: Long,
    val startedAt: Instant,
    val finishedAt: Instant?,
)
