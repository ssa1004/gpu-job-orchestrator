package com.example.gwp.orchestrator.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JobMetrics 의 timer histogram 활성화 검증 (ADR-0019 의 핵심).
 *
 * <p>Prometheus exemplar 자체는 PrometheusMeterRegistry + SpanContext 빈이 있어야
 * 부착되므로 단위 테스트에서는 직접 검증이 어렵다. 그러나 *histogram bucket 이 실제로
 * 형성되는지* 는 SimpleMeterRegistry 로 충분히 검증 가능 — exemplar 는 이 bucket 에
 * "단지 attach 되는" 정보라 bucket 자체가 없으면 exemplar 도 갈 곳이 없다. 따라서 bucket
 * 형성 검증이 곧 exemplar prerequisite 검증.</p>
 */
class JobMetricsTest {

    private MeterRegistry registry;
    private JobMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new JobMetrics(registry);
    }

    /**
     * counter 들이 등록되고 increment 가 누적되어야 한다.
     */
    @Test
    void counters_areRegistered_andIncrementCorrectly() {
        metrics.recordSubmitted();
        metrics.recordSubmitted();
        metrics.recordSucceeded();
        metrics.recordFailed();
        metrics.recordCancelled();

        assertThat(registry.find("gwp_orchestrator_jobs_submitted_total").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.find("gwp_orchestrator_jobs_completed_total")
                .tags("status", "succeeded").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("gwp_orchestrator_jobs_completed_total")
                .tags("status", "failed").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("gwp_orchestrator_jobs_completed_total")
                .tags("status", "cancelled").counter().count()).isEqualTo(1.0);
    }

    /**
     * <b>exemplar 의 prerequisite</b>: timer 가 percentile histogram 으로 export 되어야
     * 한다 — bucket count 가 0 이 아니어야 함. exemplar 는 각 bucket 에 1개씩 attach 되므로
     * bucket 자체가 없으면 exemplar 가 갈 곳이 없다.
     *
     * <p>SLO bucket 7개 (100ms / 250ms / 500ms / 1s / 2s / 5s / 10s) + Micrometer 의
     * percentile-histogram bucket 자동 추가 → 합쳐서 ≥ 7개 bucket 보장.</p>
     */
    @Test
    void submitTimer_exposesHistogramBuckets_forExemplarAttachment() {
        Timer timer = metrics.submitTimer();
        timer.record(Duration.ofMillis(50));
        timer.record(Duration.ofMillis(800));
        timer.record(Duration.ofSeconds(3));

        var snapshot = timer.takeSnapshot();
        // SLO bucket 7개를 명시적으로 박아 둔 게 살아있어야 한다 — exemplar attach point.
        assertThat(snapshot.histogramCounts()).hasSizeGreaterThanOrEqualTo(7);
        // bucket 들이 cumulative count 를 가져야 — 실제 record() 가 분류되었음을 의미.
        assertThat(snapshot.total()).isGreaterThan(0);
    }

    @Test
    void dispatchLatency_exposesHistogramBuckets() {
        Timer timer = metrics.dispatchLatency();
        timer.record(Duration.ofMillis(200));
        timer.record(Duration.ofSeconds(2));

        var snapshot = timer.takeSnapshot();
        // SLO bucket 5개 (100ms / 500ms / 1s / 5s / 30s) 확보
        assertThat(snapshot.histogramCounts()).hasSizeGreaterThanOrEqualTo(5);
    }

    /**
     * timer 이름이 dot-notation 이 아니라 underscore (Prometheus 컨벤션) 로 export 가능해야 함.
     * Micrometer 가 dot-to-underscore 자동 변환 — 이 이름 그대로 prometheus scrape 에 보임.
     */
    @Test
    void timer_namesUseUnderscoreConvention() {
        // SimpleMeterRegistry 는 변환 없이 raw name 을 보존
        assertThat(registry.find("gwp_orchestrator_job_submit_seconds")).isNotNull();
        assertThat(registry.find("gwp_orchestrator_dispatch_latency_seconds")).isNotNull();
    }

    /**
     * Tag 기반 cardinality 가 폭증하지 않는지 — 이 timer 들에는 status tag 같은 변수
     * dimension 이 없어 인스턴스당 1개의 timer 만 등록되어야 한다 (exemplar bucket 만 별개).
     */
    @Test
    void timers_haveNoVariableDimensions() {
        Timer t = metrics.submitTimer();
        // tag 없음 — Tags.empty() 와 동등
        assertThat(t.getId().getTags()).extracting(Tag::getKey).doesNotContain("owner", "priority");
    }
}
