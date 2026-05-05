package com.example.gwp.orchestrator.api.dto;

import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobPriority;
import com.example.gwp.orchestrator.domain.JobStatus;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String owner,
        String image,
        int gpuCount,
        JobStatus status,
        JobPriority priority,
        String inputUri,
        String resultUri,
        String traceId,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getOwner(),
                job.getImage(),
                job.getGpuCount(),
                job.getStatus(),
                job.getPriority(),
                job.getInputUri(),
                job.getResultUri(),
                job.getTraceId(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt()
        );
    }
}
