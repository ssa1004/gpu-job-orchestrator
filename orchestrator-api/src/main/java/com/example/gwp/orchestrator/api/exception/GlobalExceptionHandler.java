package com.example.gwp.orchestrator.api.exception;

import com.example.gwp.orchestrator.api.dto.ErrorResponse;
import com.example.gwp.orchestrator.domain.AccessDeniedException;
import com.example.gwp.orchestrator.domain.IllegalJobTransitionException;
import com.example.gwp.orchestrator.domain.JobNotFoundException;
import com.example.gwp.orchestrator.domain.QuotaExceededException;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final Tracer tracer;

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(JobNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", e.getMessage(), List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        // 정보 노출 방지: 메시지 그대로 노출하지 않고 일반화
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "access denied", List.of());
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleQuota(QuotaExceededException e) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED", e.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request validation failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedBody(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request body is malformed", List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_PARAMETER", "request parameter is invalid", List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), List.of());
    }

    @ExceptionHandler(IllegalJobTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalJobTransition(IllegalJobTransitionException e) {
        return build(HttpStatus.CONFLICT, "ILLEGAL_JOB_TRANSITION", e.getMessage(), List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException e) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "job was updated by another request", List.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        return build(HttpStatus.CONFLICT, "ILLEGAL_STATE", e.getMessage(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAll(Exception e) {
        log.error("unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal error", List.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, List<String> details) {
        var span = tracer.currentSpan();
        String traceId = span != null ? span.context().traceId() : null;
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, details, traceId, Instant.now()));
    }
}
