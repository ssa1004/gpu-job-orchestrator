package com.example.gwp.orchestrator.domain;

import com.example.gwp.orchestrator.adapter.kubernetes.JobDispatcher;
import com.example.gwp.orchestrator.observability.JobMetrics;
import com.example.gwp.orchestrator.outbox.JobEvent;
import com.example.gwp.orchestrator.outbox.OutboxWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobSubmissionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock JobRepository jobRepository;
    @Mock JobDispatcher jobDispatcher;
    @Mock Tracer tracer;
    @Mock QuotaService quotaService;
    @Mock OutboxWriter outboxWriter;

    JobMetrics metrics;
    JobSubmissionService service;

    @BeforeEach
    void setUp() {
        metrics = new JobMetrics(new SimpleMeterRegistry());
        service = new JobSubmissionService(
                jobRepository, jobDispatcher, metrics, tracer, quotaService, outboxWriter, CLOCK);
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void submit_dispatchesAndPublishesEvent_whenK8sSucceeds() {
        when(jobDispatcher.dispatch(any())).thenReturn("k8s-job-1");

        Job result = service.submit(new JobSpec("alice", "s3://bucket/in.bin", "engine:1.0", 1));

        assertThat(result.getStatus()).isEqualTo(JobStatus.DISPATCHING);
        assertThat(result.getK8sJobName()).isEqualTo("k8s-job-1");
        verify(quotaService).enforceForSubmission("alice", 1);
        verify(outboxWriter).write(any(JobEvent.JobSubmitted.class));
        verify(jobRepository, times(2)).save(any(Job.class));   // save → dispatch → re-save
    }

    @Test
    void submit_marksFailedAndPublishesEvent_whenDispatchThrows() {
        when(jobDispatcher.dispatch(any())).thenThrow(new RuntimeException("k8s API down"));

        Job result = service.submit(new JobSpec("alice", "s3://bucket/in.bin", "engine:1.0", 1));

        assertThat(result.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("k8s API down");
        // Submitted 이벤트는 발행 (status FAILED 로) — 실패 가시성 확보
        verify(outboxWriter).write(any(JobEvent.JobSubmitted.class));
    }

    @Test
    void submit_throwsAndDoesNotPersist_whenQuotaExceeded() {
        doThrow(new QuotaExceededException("over"))
                .when(quotaService).enforceForSubmission(anyString(), anyInt());

        assertThatThrownBy(() ->
                service.submit(new JobSpec("alice", "s3://bucket/in.bin", "engine:1.0", 1)))
                .isInstanceOf(QuotaExceededException.class);

        verify(jobRepository, never()).save(any());
        verify(jobDispatcher, never()).dispatch(any());
        verify(outboxWriter, never()).write(any());
    }

    @Test
    void submit_recordsTraceIdFromCurrentSpan() {
        var spanContext = mock(io.micrometer.tracing.TraceContext.class);
        var span = mock(io.micrometer.tracing.Span.class);
        when(spanContext.traceId()).thenReturn("trace-xyz");
        when(span.context()).thenReturn(spanContext);
        when(tracer.currentSpan()).thenReturn(span);
        when(jobDispatcher.dispatch(any())).thenReturn("k8s-1");

        Job result = service.submit(new JobSpec("alice", "s3://bucket/in.bin", "engine:1.0", 1));

        assertThat(result.getTraceId()).isEqualTo("trace-xyz");
    }
}
