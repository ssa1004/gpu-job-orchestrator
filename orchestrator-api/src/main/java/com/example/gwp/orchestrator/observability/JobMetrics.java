package com.example.gwp.orchestrator.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Job 라이프사이클 단계별 카운터 + 레이턴시 timer.
 *
 * <h3>왜 timer 들이 histogram 인가 (ADR-0019)</h3>
 *
 * <p>Counter 만으로는 *얼마나 자주 일어났나* 를 알 수 있지만 *얼마나 오래 걸렸나* 의
 * 분포 (p50 / p95 / p99) 를 모른다. Timer 가 histogram 으로 export 되면:</p>
 *
 * <ul>
 *   <li>Grafana 에서 latency p95 패널을 보고 spike 식별</li>
 *   <li>spike 의 *bucket* 에 Prometheus Exemplar 가 trace_id 를 attach 해 한 번 클릭에 trace
 *       (Tempo / Jaeger) 로 jump — 어떤 잡 / 어떤 단계가 느렸는지 정확한 trace 확인 가능</li>
 *   <li>SLO ({@code 95% < 2s}) 측정에 직접 활용 — error budget 계산 자동</li>
 * </ul>
 *
 * <p>histogram 의 cardinality 비용은 *bucket 수* 로 한정 (per-tag combination × bucket
 * 수). exemplar 자체는 per-bucket 1개라 cardinality 폭증 안 일으킴 — Prometheus Native
 * Histogram 으로 갈 때까지의 표준 trade-off.</p>
 *
 * <h3>주요 timer</h3>
 * <ul>
 *   <li>{@code gwp_orchestrator_job_submit_seconds} — JobSubmissionService.submit 의
 *       총 wall-clock 시간 (쿼터 검사 + DB INSERT + K8s dispatch + Outbox INSERT).
 *       사용자 체감 응답 시간.</li>
 *   <li>{@code gwp_orchestrator_dispatch_latency_seconds} — submit 시각 ~ 실제 dispatch
 *       시각의 갭 (큐 대기 / scheduler tick 지연 포함).</li>
 * </ul>
 *
 * <p>histogram 활성화는 application.yml 의
 * {@code management.metrics.distribution.percentiles-histogram} 으로. 이 코드는
 * 활성화 결과로 자동 형성된 bucket 에 exemplar 가 붙는 형태.</p>
 */
@Component
public class JobMetrics {

    private final Counter submitted;
    private final Counter succeeded;
    private final Counter failed;
    private final Counter cancelled;

    /** submit() 호출의 총 wall-clock 시간 (사용자 체감 응답 시간). */
    private final Timer submitTimer;

    /** submit 부터 실제 dispatch 까지의 갭. spike 가 큐 대기인지 K8s 응답 느림인지 분리 가능. */
    private final Timer dispatchLatency;

    public JobMetrics(MeterRegistry registry) {
        this.submitted = Counter.builder("gwp_orchestrator_jobs_submitted_total")
                .description("Total jobs submitted").register(registry);
        this.succeeded = Counter.builder("gwp_orchestrator_jobs_completed_total")
                .description("Total jobs completed").tag("status", "succeeded").register(registry);
        this.failed = Counter.builder("gwp_orchestrator_jobs_completed_total")
                .description("Total jobs completed").tag("status", "failed").register(registry);
        this.cancelled = Counter.builder("gwp_orchestrator_jobs_completed_total")
                .description("Total jobs completed").tag("status", "cancelled").register(registry);

        // SLO bucket 을 명시적으로 — 너무 fine-grained 면 cardinality 폭증, 너무 coarse 면
        // p95 가 부정확. 100ms / 250ms / 500ms / 1s / 2s / 5s / 10s 가 80% 의 사용 케이스를 cover.
        // exemplar 는 각 bucket 마다 1개 (per-bucket), 매 호출이 아니라 bucket 진입 trace 만 박힘.
        this.submitTimer = Timer.builder("gwp_orchestrator_job_submit_seconds")
                .description("submit() wall-clock time")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(100),
                        Duration.ofMillis(250),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10))
                .register(registry);

        this.dispatchLatency = Timer.builder("gwp_orchestrator_dispatch_latency_seconds")
                .description("submit → dispatch lag")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(100),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30))
                .register(registry);
    }

    public void recordSubmitted() { submitted.increment(); }
    public void recordSucceeded() { succeeded.increment(); }
    public void recordFailed() { failed.increment(); }
    public void recordCancelled() { cancelled.increment(); }

    public Timer submitTimer() { return submitTimer; }
    public Timer dispatchLatency() { return dispatchLatency; }
}
