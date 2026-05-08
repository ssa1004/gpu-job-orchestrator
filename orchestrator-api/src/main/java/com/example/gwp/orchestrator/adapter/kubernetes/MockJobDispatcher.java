package com.example.gwp.orchestrator.adapter.kubernetes;

import com.example.gwp.orchestrator.application.ImageLogMask;
import com.example.gwp.orchestrator.domain.Job;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MockJobDispatcher implements JobDispatcher {

    @Override
    public String dispatch(Job job) {
        String name = "mock-job-" + job.getId();
        log.info("[mock] dispatch jobId={} image={} gpu={} → {}",
                job.getId(), ImageLogMask.mask(job.getImage()), job.getGpuCount(), name);
        return name;
    }

    @Override
    public void cancel(String k8sJobName) {
        log.info("[mock] cancel name={}", k8sJobName);
    }
}
