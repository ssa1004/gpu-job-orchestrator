package com.example.gwp.orchestrator.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class JobMetrics {

    private final Counter submitted;
    private final Counter succeeded;
    private final Counter failed;
    private final Counter cancelled;

    public JobMetrics(MeterRegistry registry) {
        this.submitted = Counter.builder("gwp_orchestrator_jobs_submitted_total")
                .description("Total jobs submitted").register(registry);
        this.succeeded = Counter.builder("gwp_orchestrator_jobs_completed_total")
                .description("Total jobs completed").tag("status", "succeeded").register(registry);
        this.failed = Counter.builder("gwp_orchestrator_jobs_completed_total")
                .description("Total jobs completed").tag("status", "failed").register(registry);
        this.cancelled = Counter.builder("gwp_orchestrator_jobs_completed_total")
                .description("Total jobs completed").tag("status", "cancelled").register(registry);
    }

    public void recordSubmitted() { submitted.increment(); }
    public void recordSucceeded() { succeeded.increment(); }
    public void recordFailed() { failed.increment(); }
    public void recordCancelled() { cancelled.increment(); }
}
