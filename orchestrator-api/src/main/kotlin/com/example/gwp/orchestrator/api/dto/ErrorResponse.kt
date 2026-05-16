package com.example.gwp.orchestrator.api.dto

import java.time.Instant

@JvmRecord
data class ErrorResponse(
    val code: String,
    val message: String,
    val details: List<String>,
    val traceId: String?,
    val timestamp: Instant?,
)
