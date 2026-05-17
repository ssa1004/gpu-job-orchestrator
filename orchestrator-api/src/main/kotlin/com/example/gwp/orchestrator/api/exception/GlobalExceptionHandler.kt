package com.example.gwp.orchestrator.api.exception

import com.example.gwp.orchestrator.api.dto.ErrorResponse
import com.example.gwp.orchestrator.dlq.DlqAdminBadRequestException
import com.example.gwp.orchestrator.dlq.DlqAdminRateLimitedException
import com.example.gwp.orchestrator.dlq.DlqMessageNotFoundException
import com.example.gwp.orchestrator.domain.AccessDeniedException
import com.example.gwp.orchestrator.domain.DependencyCycleException
import com.example.gwp.orchestrator.domain.IllegalJobTransitionException
import com.example.gwp.orchestrator.domain.JobNotFoundException
import com.example.gwp.orchestrator.domain.QuotaExceededException
import io.micrometer.tracing.Tracer
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.Clock

@RestControllerAdvice
class GlobalExceptionHandler(
    private val tracer: Tracer,
    private val clock: Clock,
) {

    @ExceptionHandler(JobNotFoundException::class)
    open fun handleNotFound(e: JobNotFoundException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", e.message ?: "", emptyList())

    @ExceptionHandler(AccessDeniedException::class)
    open fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ErrorResponse> {
        // 정보 노출 방지: 메시지 그대로 노출하지 않고 일반화
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "access denied", emptyList())
    }

    @ExceptionHandler(QuotaExceededException::class)
    open fun handleQuota(e: QuotaExceededException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED", e.message ?: "", emptyList())

    @ExceptionHandler(DependencyCycleException::class)
    open fun handleDependencyCycle(e: DependencyCycleException): ResponseEntity<ErrorResponse> {
        // 사이클이 있는 의존 관계를 만들려는 요청 — 영영 만족 못 할 그래프라 거절.
        // path 의 UUID 들은 클라이언트가 어느 잡 사이에 cycle 이 있는지 디버그할 때 필요.
        val details = e.cyclePath.map { it.toString() }
        return build(HttpStatus.CONFLICT, "DEPENDENCY_CYCLE", "dependency cycle detected", details)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    open fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = e.bindingResult.fieldErrors.map { fe -> "${fe.field}: ${fe.defaultMessage}" }
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request validation failed", details)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    open fun handleMalformedBody(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request body is malformed", emptyList())

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    open fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_PARAMETER", "request parameter is invalid", emptyList())

    @ExceptionHandler(IllegalArgumentException::class)
    open fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.message ?: "", emptyList())

    @ExceptionHandler(IllegalJobTransitionException::class)
    open fun handleIllegalJobTransition(e: IllegalJobTransitionException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.CONFLICT, "ILLEGAL_JOB_TRANSITION", e.message ?: "", emptyList())

    @ExceptionHandler(OptimisticLockingFailureException::class)
    open fun handleOptimisticLock(e: OptimisticLockingFailureException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "job was updated by another request", emptyList())

    @ExceptionHandler(IllegalStateException::class)
    open fun handleIllegalState(e: IllegalStateException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.CONFLICT, "ILLEGAL_STATE", e.message ?: "", emptyList())

    // ─── DLQ admin (ADR-0026) ─────────────────────────────────────────────────────

    @ExceptionHandler(DlqMessageNotFoundException::class)
    open fun handleDlqNotFound(e: DlqMessageNotFoundException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.NOT_FOUND, "DLQ_NOT_FOUND", "DLQ message not found", emptyList())

    @ExceptionHandler(DlqAdminBadRequestException::class)
    open fun handleDlqBadRequest(e: DlqAdminBadRequestException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "DLQ_BAD_REQUEST", e.message ?: "", emptyList())

    @ExceptionHandler(DlqAdminRateLimitedException::class)
    open fun handleDlqRateLimit(e: DlqAdminRateLimitedException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.TOO_MANY_REQUESTS, "DLQ_RATE_LIMITED", e.message ?: "rate limited", emptyList())

    @ExceptionHandler(Exception::class)
    open fun handleAll(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("unhandled exception", e)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal error", emptyList())
    }

    private fun build(
        status: HttpStatus,
        code: String,
        message: String,
        details: List<String>,
    ): ResponseEntity<ErrorResponse> {
        val span = tracer.currentSpan()
        val traceId = span?.context()?.traceId()
        // ADR-0007: 시각은 주입된 Clock 으로 — 테스트 가능성 + UTC 통일
        return ResponseEntity.status(status)
            .body(ErrorResponse(code, message, details, traceId, clock.instant()))
    }

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
