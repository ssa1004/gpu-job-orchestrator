package com.example.gwp.orchestrator.domain

/**
 * 허용되지 않는 Job 상태 천이를 시도했을 때 throw 된다.
 * 예: 이미 SUCCEEDED 인 Job 을 CANCELLED 로 바꾸려는 경우.
 *
 * HTTP 409 (CONFLICT) 로 매핑됨 — [com.example.gwp.orchestrator.api.exception.GlobalExceptionHandler].
 */
class IllegalJobTransitionException(from: JobStatus, to: JobStatus) :
    RuntimeException("illegal job transition: $from → $to")
