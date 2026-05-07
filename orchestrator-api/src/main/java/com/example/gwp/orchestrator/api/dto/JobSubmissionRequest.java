package com.example.gwp.orchestrator.api.dto;

import com.example.gwp.orchestrator.domain.JobPriority;
import com.example.gwp.orchestrator.domain.PreemptionPolicy;
import jakarta.validation.constraints.*;

/**
 * Job 제출 요청. owner 는 인증 정보(JWT subject)에서 자동 결정되므로 본문에 포함되지 않는다.
 *
 * <p>{@code priority} 미지정 → NORMAL.<br>
 * {@code preemptionPolicy} 미지정 → PREEMPTABLE — 보통 학습 / 배치 작업 (재시도 가능).
 * 진행 중에 죽이면 손실이 큰 작업 (DB migration / 실시간 inference SLA 보장 등) 만 NEVER 명시.</p>
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

        JobPriority priority,           // null → NORMAL (JobSpec 에서 default)

        PreemptionPolicy preemptionPolicy   // null → PREEMPTABLE
) {}
