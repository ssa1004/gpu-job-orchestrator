package com.example.gwp.orchestrator.api.dto;

import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobPriority;
import com.example.gwp.orchestrator.domain.JobStatus;
import com.example.gwp.orchestrator.domain.PreemptionPolicy;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String owner,
        String image,
        int gpuCount,
        JobStatus status,
        JobPriority priority,
        PreemptionPolicy preemptionPolicy,
        String inputUri,
        String resultUri,
        String traceId,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        // PREEMPTED 인 잡에서만 채워짐 — 운영 화면 / 사용자 알림 본문에 사용
        Instant preemptedAt,
        UUID preemptedByJobId,
        String preemptedReason
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getOwner(),
                job.getImage(),
                job.getGpuCount(),
                job.getStatus(),
                job.getPriority(),
                job.getPreemptionPolicy(),
                job.getInputUri(),
                job.getResultUri(),
                job.getTraceId(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getPreemptedAt(),
                job.getPreemptedByJobId(),
                job.getPreemptedReason()
        );
    }
}
