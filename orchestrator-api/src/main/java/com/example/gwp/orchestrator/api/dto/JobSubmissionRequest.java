package com.example.gwp.orchestrator.api.dto;

import com.example.gwp.orchestrator.domain.JobPriority;
import com.example.gwp.orchestrator.domain.PreemptionPolicy;
import jakarta.validation.constraints.*;

import java.util.Set;
import java.util.UUID;

/**
 * Job 제출 요청. owner 는 인증 정보(JWT subject)에서 자동 결정되므로 본문에 포함되지 않는다.
 *
 * <p>{@code priority} 미지정 → NORMAL.<br>
 * {@code preemptionPolicy} 미지정 → PREEMPTABLE — 보통 학습 / 배치 작업 (재시도 가능).
 * 진행 중에 죽이면 손실이 큰 작업 (DB migration / 실시간 inference SLA 보장 등) 만 NEVER 명시.</p>
 *
 * <p>{@code parentJobIds} — 의존하는 부모 잡들. 비어 있으면 즉시 dispatch path. 하나 이상이면
 * 모두 SUCCEEDED 될 때까지 WAITING_DEPS. 사이클 만들면 즉시 거절 (영속화 안 됨).</p>
 */
public record JobSubmissionRequest(

        @NotBlank
        @Pattern(regexp = "^s3://[a-z0-9.\\-]+/.+", message = "must be s3://bucket/key")
        String inputUri,

        /**
         * 컨테이너 이미지 reference. {@code [registry/]repo[:tag][@sha256:digest]} 형식만 허용.
         *
         * <p><b>왜 정규식</b>: 이미지 문자열에 공백 / 줄바꿈 / 자격증명 (user:pwd@host) 이
         * 끼면 K8s manifest 가 깨지거나 자격증명이 로그 / Job 메타데이터로 새어나갈 위험.
         * 자격증명은 {@code imagePullSecrets} 에 별도로 두는 것이 표준이라 image 문자열에
         * userinfo (user:pwd@) 가 들어올 일은 없다.</p>
         */
        @NotBlank
        @Size(max = 256)
        @Pattern(
                regexp = "^[a-zA-Z0-9._\\-/]+(?::[a-zA-Z0-9._\\-]+)?(?:@sha256:[a-f0-9]{64})?$",
                message = "image must match [registry/]repo[:tag][@sha256:digest], no whitespace or credentials")
        String image,

        @Min(1)
        @Max(8)
        int gpuCount,

        JobPriority priority,                  // null → NORMAL

        PreemptionPolicy preemptionPolicy,     // null → PREEMPTABLE

        Set<UUID> parentJobIds                 // null/empty → 의존 없음 (즉시 dispatch)
) {}
