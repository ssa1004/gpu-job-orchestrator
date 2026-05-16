package com.example.gwp.orchestrator.adapter.kubernetes

/**
 * K8s 디스패치 / 취소 실패 시 throw. [com.example.gwp.orchestrator.api.exception.GlobalExceptionHandler]
 * 가 502 BAD_GATEWAY 로 매핑.
 */
class JobDispatchException : RuntimeException {
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(message: String) : super(message)
}
