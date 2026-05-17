package com.example.gwp.orchestrator.dlq

/**
 * cursor-paginated 목록 응답.
 *
 * [nextCursor] 가 null 이면 마지막 페이지. 클라이언트는 이 값을 그대로 다음 요청의
 * `cursor` 로 넘기면 된다 — opaque 토큰 (구조를 외부에 노출하지 않음) 이라 서버 구현이
 * 바뀌어도 호환.
 */
data class DlqListPage(
    val items: List<DlqMessage>,
    val nextCursor: String?,
)
