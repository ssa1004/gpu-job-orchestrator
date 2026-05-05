package com.example.gwp.orchestrator.adapter.kubernetes;

/**
 * K8s 디스패치 / 취소 실패 시 throw. {@link com.example.gwp.orchestrator.api.exception.GlobalExceptionHandler}
 * 가 502 BAD_GATEWAY 로 매핑.
 */
public class JobDispatchException extends RuntimeException {
    public JobDispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
