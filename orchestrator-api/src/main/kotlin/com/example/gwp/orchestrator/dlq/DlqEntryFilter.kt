package com.example.gwp.orchestrator.dlq

import java.time.Instant

/**
 * `GET /api/v1/admin/dlq` 의 query string 을 한 객체로 묶은 필터.
 *
 * cursor pagination — `cursor` 가 null 이면 첫 페이지, 응답의 `nextCursor` 를 다음
 * 요청에 그대로 넣어 follow-up. offset/limit 가 아닌 이유는 같은 페이지를 두 번 받지
 * 않게 하기 위함 (DLQ 는 polling 사이 시점에 신규 message 가 들어오면 offset 이 밀린다).
 *
 * `size` 는 application.yml 의 `spring.data.web.pageable.max-page-size` (100) 를
 * 넘지 않도록 controller / service 단에서 clamp.
 */
data class DlqEntryFilter(
    val source: DlqSource?,
    val topic: String?,
    val from: Instant?,
    val to: Instant?,
    val errorType: String?,
    val cursor: String?,
    val size: Int,
)
