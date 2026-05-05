package com.example.gwp.orchestrator.api.dto;

import com.example.gwp.orchestrator.domain.JobStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StatusCallbackRequest(
        @NotNull JobStatus status,
        @Size(max = 1024) String resultUri,
        @Size(max = 2048) String errorMessage
) {}
