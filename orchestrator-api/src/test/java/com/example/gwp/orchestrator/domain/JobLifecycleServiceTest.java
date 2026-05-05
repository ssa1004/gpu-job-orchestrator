package com.example.gwp.orchestrator.domain;

import com.example.gwp.orchestrator.adapter.kubernetes.JobDispatcher;
import com.example.gwp.orchestrator.observability.JobMetrics;
import com.example.gwp.orchestrator.outbox.JobEvent;
import com.example.gwp.orchestrator.outbox.OutboxWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobLifecycleServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock JobRepository jobRepository;
    @Mock JobDispatcher jobDispatcher;
    @Mock OutboxWriter outboxWriter;

    JobMetrics metrics;
    JobLifecycleService service;

    @BeforeEach
    void setUp() {
        metrics = new JobMetrics(new SimpleMeterRegistry());
        service = new JobLifecycleService(jobRepository, jobDispatcher, metrics, outboxWriter, CLOCK);
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Job aJobIn(JobStatus status) {
        Job job = Job.submit(new JobSpec("alice", "s3://b/i", "eng:1", 1), null, CLOCK);
        if (status == JobStatus.DISPATCHING) job.markDispatched("k8s-1", CLOCK);
        if (status == JobStatus.RUNNING) {
            job.markDispatched("k8s-1", CLOCK);
            job.markRunning(CLOCK);
        }
        return job;
    }

    @Test
    void callback_RUNNING_marksRunningAndDoesNotPublish() {
        Job job = aJobIn(JobStatus.DISPATCHING);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.updateStatusFromCallback(job.getId(), JobStatus.RUNNING, null, null);

        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        verify(outboxWriter, never()).write(any());   // RUNNING 은 outbox 미발행 (이력만)
    }

    @Test
    void callback_SUCCEEDED_publishesCompletedEvent() {
        Job job = aJobIn(JobStatus.RUNNING);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.updateStatusFromCallback(job.getId(), JobStatus.SUCCEEDED, "s3://b/o", null);

        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        verify(outboxWriter).write(any(JobEvent.JobCompleted.class));
    }

    @Test
    void callback_terminalJob_isIgnored() {
        Job job = aJobIn(JobStatus.RUNNING);
        job.markSucceeded("s3://b/o", CLOCK);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        Job result = service.updateStatusFromCallback(job.getId(), JobStatus.FAILED, null, "boom");

        assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCEEDED);   // 변경 없음
        verify(outboxWriter, never()).write(any());
        verify(jobRepository, never()).save(any());
    }

    @Test
    void cancel_runningJob_callsK8sDeleteAndPublishesEvent() {
        Job job = aJobIn(JobStatus.RUNNING);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        Job result = service.cancel(job.getId());

        assertThat(result.getStatus()).isEqualTo(JobStatus.CANCELLED);
        verify(jobDispatcher).cancel("k8s-1");
        verify(outboxWriter).write(any(JobEvent.JobCompleted.class));
    }

    @Test
    void cancel_terminalJob_isIdempotent() {
        Job job = aJobIn(JobStatus.RUNNING);
        job.markSucceeded("s3://b/o", CLOCK);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        Job result = service.cancel(job.getId());

        assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        verify(jobDispatcher, never()).cancel(any());
    }

    @Test
    void callback_unknownJob_throws() {
        UUID id = UUID.randomUUID();
        when(jobRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatusFromCallback(id, JobStatus.RUNNING, null, null))
                .isInstanceOf(JobNotFoundException.class);
    }
}
