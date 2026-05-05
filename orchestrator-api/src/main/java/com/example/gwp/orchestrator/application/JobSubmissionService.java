package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.domain.*;

import com.example.gwp.orchestrator.adapter.kubernetes.JobDispatcher;
import com.example.gwp.orchestrator.observability.JobMetrics;
import com.example.gwp.orchestrator.outbox.JobEvent;
import com.example.gwp.orchestrator.outbox.OutboxWriter;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Job 제출 책임 — 쿼터 검사 → DB 저장 → K8s 디스패치 → Outbox 발행 → 메트릭 기록.
 *
 * <p>한 트랜잭션 안에서 DB INSERT 와 Outbox INSERT 가 같이 commit 되어 atomicity 보장.
 * Kafka publish 는 OutboxRelay 가 별도 트랜잭션으로 처리.</p>
 *
 * <p>dispatch 실패는 catch 하여 Job 을 FAILED 로 기록하고 client 에게는 정상 응답
 * (job ID 발급 + status FAILED) — 그래야 client 가 재시도 시 새 ID 가 아닌 같은 ID 로 추적 가능.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobSubmissionService {

    private final JobRepository jobRepository;
    private final JobDispatcher jobDispatcher;
    private final JobMetrics jobMetrics;
    private final Tracer tracer;
    private final QuotaService quotaService;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    @Transactional
    public Job submit(JobSpec spec) {
        quotaService.enforceForSubmission(spec.owner(), spec.gpuCount());

        String traceId = currentTraceId();
        Job job = Job.submit(spec, traceId, clock);
        jobRepository.save(job);
        jobMetrics.recordSubmitted();
        log.info("job submitted id={} owner={} image={} gpu={}",
                job.getId(), spec.owner(), spec.image(), spec.gpuCount());

        dispatchOrFail(job);
        Job persisted = jobRepository.save(job);
        publishSubmittedEvent(persisted, spec, traceId);
        return persisted;
    }

    private void dispatchOrFail(Job job) {
        try {
            String k8sJobName = jobDispatcher.dispatch(job);
            job.markDispatched(k8sJobName, clock);
        } catch (Exception e) {
            log.error("dispatch failed id={} reason={}", job.getId(), e.getMessage(), e);
            job.markFailed("dispatch failed: " + e.getMessage(), clock);
            jobMetrics.recordFailed();
        }
    }

    private void publishSubmittedEvent(Job job, JobSpec spec, String traceId) {
        outboxWriter.write(new JobEvent.JobSubmitted(
                job.getId().toString(),
                spec.owner(),
                spec.image(),
                spec.gpuCount(),
                spec.priority().name(),
                job.getStatus().name(),
                traceId != null ? traceId : ""
        ));
    }

    private String currentTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
