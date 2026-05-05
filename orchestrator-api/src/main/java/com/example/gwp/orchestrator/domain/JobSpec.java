package com.example.gwp.orchestrator.domain;

public record JobSpec(
        String owner,
        String inputUri,
        String image,
        int gpuCount,
        JobPriority priority
) {
    public JobSpec {
        if (priority == null) priority = JobPriority.NORMAL;
    }

    /** 우선순위 미지정 시 NORMAL 로 위임. */
    public JobSpec(String owner, String inputUri, String image, int gpuCount) {
        this(owner, inputUri, image, gpuCount, JobPriority.NORMAL);
    }
}
