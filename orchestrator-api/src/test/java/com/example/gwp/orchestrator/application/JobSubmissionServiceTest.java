package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.domain.*;

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
    @Mock com.example.gwp.orchestrator.domain.JobDependencyRepository jobDependencyRepository;
    @Mock JobDispatcher jobDispatcher;
    @Mock Tracer tracer;
    @Mock QuotaService quotaService;
    @Mock OutboxWriter outboxWriter;
    @Mock CostAttributionService costAttribution;

    JobMetrics metrics;
    JobSubmissionService service;

    @BeforeEach
    void setUp() {
        metrics = new JobMetrics(new SimpleMeterRegistry());
        service = new JobSubmissionService(
                jobRepository, jobDependencyRepository, jobDispatcher, metrics, tracer,
                quotaService, outboxWriter, costAttribution, CLOCK);
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

    /**
     * dispatch 실패 시에도 cost record 를 기록한다. 운영에서 "어떤 잡이 dispatch 실패였는지"
     * 추적용. runtime 0 / cost 0 record.
     */
    @Test
    void submit_recordsCost_whenDispatchFails() {
        when(jobDispatcher.dispatch(any())).thenThrow(new RuntimeException("k8s API down"));

        Job result = service.submit(new JobSpec("alice", "s3://bucket/in.bin", "engine:1.0", 1));

        verify(costAttribution).recordCost(result);
    }

    /**
     * dispatch 성공 시에는 cost recording 호출 안 됨 — 잡이 아직 종착 상태 아님.
     */
    @Test
    void submit_doesNotRecordCost_whenDispatchSucceeds() {
        when(jobDispatcher.dispatch(any())).thenReturn("k8s-job-1");

        service.submit(new JobSpec("alice", "s3://bucket/in.bin", "engine:1.0", 1));

        verify(costAttribution, never()).recordCost(any());
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

    /**
     * cycle 검사가 BFS 한 레벨을 한 batch IN-쿼리로 끌어오는지 — 1000 노드 깊이 5 의
     * 그래프에서도 round-trip 이 깊이 만큼만 발생하는지 회귀 락다운.
     *
     * <p>예전 구현 (per-node {@code findByChildJobId}) 은 노드 수와 같은 round-trip 을
     * 발생시켜 깊은 chain 에서 latency 가 잡 수에 비례. {@code findByChildJobIdIn} 으로
     * 한 레벨씩 묶어 round-trip 을 깊이 단위로 압축 — 1000 노드 / 깊이 5 라면 5 회.</p>
     */
    @Test
    void submit_withDeepDependencyChain_usesBatchedLookup_notPerNode() {
        // 새 잡 → P0 → P1 → P2 의 4 단계 chain. 노드 4 개, 깊이 3 (root 의 부모부터 셈).
        java.util.UUID p0 = java.util.UUID.randomUUID();
        java.util.UUID p1 = java.util.UUID.randomUUID();
        java.util.UUID p2 = java.util.UUID.randomUUID();

        when(jobRepository.existsById(p0)).thenReturn(true);
        // findByChildJobIdIn 은 frontier 한 레벨 = 한 호출. {p0} → {p1} → {p2} → {} 4 콜.
        when(jobDependencyRepository.findByChildJobIdIn(java.util.Set.of(p0)))
                .thenReturn(java.util.List.of(JobDependency.edge(p0, p1, CLOCK.instant())));
        when(jobDependencyRepository.findByChildJobIdIn(java.util.Set.of(p1)))
                .thenReturn(java.util.List.of(JobDependency.edge(p1, p2, CLOCK.instant())));
        when(jobDependencyRepository.findByChildJobIdIn(java.util.Set.of(p2)))
                .thenReturn(java.util.List.of());
        when(jobRepository.findAllById(java.util.Set.of(p0)))
                .thenReturn(java.util.List.of());   // allParentsAlreadySucceeded → false

        service.submit(
                new JobSpec("alice", "s3://b/in", "engine:1", 1),
                java.util.Set.of(p0));

        // 핵심 회귀 락다운 — 레거시 단일-건 API 는 호출되면 안 됨.
        verify(jobDependencyRepository, never()).findByChildJobId(any());
        // 레벨 1 / 2 / 3 = 3 회. (마지막 {p2} 도 한 번 — 빈 결과 — 호출 후 frontier 비어 종료)
        verify(jobDependencyRepository, times(3)).findByChildJobIdIn(any());
    }

    /**
     * cycle 이 있는 그래프 — 새 잡 X 의 parent A 가 그래프 상에서 X 자신으로 거슬러
     * 올라가는 경우. detectCycle 이 발견해 거절.
     */
    @Test
    void submit_cycleInDependencyGraph_throws() {
        // 새 잡 (placeholder id 는 service 내부 생성) → A → B → A — A↔B cycle.
        java.util.UUID a = java.util.UUID.randomUUID();
        java.util.UUID b = java.util.UUID.randomUUID();

        when(jobRepository.existsById(a)).thenReturn(true);
        when(jobDependencyRepository.findByChildJobIdIn(java.util.Set.of(a)))
                .thenReturn(java.util.List.of(JobDependency.edge(a, b, CLOCK.instant())));
        when(jobDependencyRepository.findByChildJobIdIn(java.util.Set.of(b)))
                .thenReturn(java.util.List.of(JobDependency.edge(b, a, CLOCK.instant())));

        assertThatThrownBy(() -> service.submit(
                new JobSpec("alice", "s3://b/in", "engine:1", 1),
                java.util.Set.of(a)))
                .isInstanceOf(DependencyCycleException.class);

        verify(jobRepository, never()).save(any());
    }
}
