package com.example.gwp.orchestrator.adapter.kubernetes

import com.example.gwp.orchestrator.application.ImageLogMask
import com.example.gwp.orchestrator.domain.Job
import org.slf4j.LoggerFactory

class MockJobDispatcher : JobDispatcher {

    override fun dispatch(job: Job): String {
        val name = "mock-job-" + job.id
        log.info(
            "[mock] dispatch jobId={} image={} gpu={} → {}",
            job.id, ImageLogMask.mask(job.image), job.gpuCount, name,
        )
        return name
    }

    override fun cancel(k8sJobName: String) {
        log.info("[mock] cancel name={}", k8sJobName)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MockJobDispatcher::class.java)
    }
}
