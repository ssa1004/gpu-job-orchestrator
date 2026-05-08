package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.domain.*;

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
    @Mock DependencyResolutionService dependencyResolution;
    @Mock CostAttributionService costAttribution;

    JobMetrics metrics;
    JobLifecycleService service;

    @BeforeEach
    void setUp() {
        metrics = new JobMetrics(new SimpleMeterRegistry());
        service = new JobLifecycleService(jobRepository, jobDispatcher, metrics, outboxWriter,
                dependencyResolution, costAttribution,
                com.example.gwp.orchestrator.lifecycle.JobLifecycleStateMachineFactory.build(),
                CLOCK);
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
    void callback_CANCELLED_isRejected() {
        Job job = aJobIn(JobStatus.RUNNING);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.updateStatusFromCallback(job.getId(), JobStatus.CANCELLED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported callback status");
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        verify(jobRepository, never()).save(any());
        verify(outboxWriter, never()).write(any());
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

    /** 콜백 SUCCEEDED — cost record 1건 박제 (terminal hook). */
    @Test
    void callback_SUCCEEDED_recordsCost() {
        Job job = aJobIn(JobStatus.RUNNING);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.updateStatusFromCallback(job.getId(), JobStatus.SUCCEEDED, "s3://b/o", null);

        verify(costAttribution).recordCost(job);
    }

    /** 콜백 FAILED — cost record 박제 (사용자 재시도 시 청구 추적). */
    @Test
    void callback_FAILED_recordsCost() {
        Job job = aJobIn(JobStatus.RUNNING);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.updateStatusFromCallback(job.getId(), JobStatus.FAILED, null, "OOM");

        verify(costAttribution).recordCost(job);
    }

    /** 콜백 RUNNING (non-terminal) — cost record 호출 안 됨. */
    @Test
    void callback_RUNNING_doesNotRecordCost() {
        Job job = aJobIn(JobStatus.DISPATCHING);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.updateStatusFromCallback(job.getId(), JobStatus.RUNNING, null, null);

        verify(costAttribution, never()).recordCost(any());
    }

    /** 사용자 cancel — 그때까지 사용한 GPU-시간 청구. cost record 박제. */
    @Test
    void cancel_runningJob_recordsCost() {
        Job job = aJobIn(JobStatus.RUNNING);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.cancel(job.getId());

        verify(costAttribution).recordCost(job);
    }

    /** 이미 종착된 잡 cancel — 멱등 (재진입 시 cost record 도 다시 안 만듬). */
    @Test
    void cancel_terminalJob_doesNotRecordCostAgain() {
        Job job = aJobIn(JobStatus.RUNNING);
        job.markSucceeded("s3://b/o", CLOCK);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.cancel(job.getId());

        // 멱등 — 두 번 호출해도 추가 cost record 안 만듬
        verify(costAttribution, never()).recordCost(any());
    }

    /** Dependency cascade 도 같이 호출 — parent terminal 시 child 들에게 전파. */
    @Test
    void callback_terminalState_triggersDependencyResolution() {
        Job job = aJobIn(JobStatus.RUNNING);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.updateStatusFromCallback(job.getId(), JobStatus.SUCCEEDED, "s3://b/o", null);

        verify(dependencyResolution).onParentTerminal(job.getId());
    }

    /** 사용자 cancel 도 parent terminal — child cascade 트리거. */
    @Test
    void cancel_triggersDependencyResolution() {
        Job job = aJobIn(JobStatus.RUNNING);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.cancel(job.getId());

        verify(dependencyResolution).onParentTerminal(job.getId());
    }
}
