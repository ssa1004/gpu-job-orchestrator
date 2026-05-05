package com.example.gwp.orchestrator.api.dto;

import com.example.gwp.orchestrator.domain.JobPriority;
import jakarta.validation.constraints.*;

/**
 * Job 제출 요청. owner 는 인증 정보(JWT subject)에서 자동 결정되므로 본문에 포함되지 않는다.
 */
public record JobSubmissionRequest(

        @NotBlank
        @Pattern(regexp = "^s3://[a-z0-9.\\-]+/.+", message = "must be s3://bucket/key")
        String inputUri,

        @NotBlank
        @Size(max = 256)
        String image,

        @Min(1)
        @Max(8)
        int gpuCount,

        JobPriority priority   // null → NORMAL (JobSpec 에서 default)
) {}
