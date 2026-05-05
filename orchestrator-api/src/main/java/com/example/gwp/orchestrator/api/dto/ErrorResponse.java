package com.example.gwp.orchestrator.api.dto;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        String code,
        String message,
        List<String> details,
        String traceId,
        Instant timestamp
) {}
