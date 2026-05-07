package com.example.gwp.orchestrator.domain;

/**
 * 사용자가 Job 제출 시 명시하는 명세.
 *
 * <p>priority / preemptionPolicy 는 nullable — 미지정 시 도메인 기본값 (NORMAL / PREEMPTABLE).
 * 기존 호출자 (테스트 / 옛 controller) backward compat 를 위해 편의 생성자 유지.</p>
 */
public record JobSpec(
        String owner,
        String inputUri,
        String image,
        int gpuCount,
        JobPriority priority,
        PreemptionPolicy preemptionPolicy
) {
    public JobSpec {
        if (priority == null) priority = JobPriority.NORMAL;
        if (preemptionPolicy == null) preemptionPolicy = PreemptionPolicy.PREEMPTABLE;
    }

    /** 우선순위 / preemption policy 미지정 시 default. */
    public JobSpec(String owner, String inputUri, String image, int gpuCount) {
        this(owner, inputUri, image, gpuCount, JobPriority.NORMAL, PreemptionPolicy.PREEMPTABLE);
    }

    public JobSpec(String owner, String inputUri, String image, int gpuCount, JobPriority priority) {
        this(owner, inputUri, image, gpuCount, priority, PreemptionPolicy.PREEMPTABLE);
    }
}
