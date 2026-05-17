package com.example.gwp.orchestrator.dlq

/**
 * DLQ admin 동작이 rate-limit 임계를 넘었을 때 발생. controller 단의
 * GlobalExceptionHandler 가 429 로 변환.
 */
class DlqAdminRateLimitedException(scope: AdminRateLimiter.Scope) :
    RuntimeException("rate limit exceeded for scope=$scope")

/**
 * 요청 자체가 잘못된 경우 (bulk 의 source 누락 등). 400 으로 변환.
 */
class DlqAdminBadRequestException(message: String) : RuntimeException(message)

/**
 * 메시지를 찾지 못한 경우. 404 로 변환.
 */
class DlqMessageNotFoundException(val messageId: String) :
    RuntimeException("DLQ message not found: $messageId")
