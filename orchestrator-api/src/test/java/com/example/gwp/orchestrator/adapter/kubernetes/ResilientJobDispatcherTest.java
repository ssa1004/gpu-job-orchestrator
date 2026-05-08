package com.example.gwp.orchestrator.adapter.kubernetes;

import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobSpec;
import com.example.gwp.orchestrator.domain.JobStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResilientJobDispatcherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    /**
     * minimum-number-of-calls 만큼 실패가 누적되면 OPEN — 그 시점부터는 같은 호출이
     * 즉시 fast-fail (JobDispatchException) 으로 떨어진다. delegate 는 한 번도 호출되지 않음.
     */
    @Test
    void dispatch_failsfast_whenCircuitOpen() {
        CircuitBreaker breaker = CircuitBreaker.of("k8s-test", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());

        JobDispatcher delegate = mock(JobDispatcher.class);
        when(delegate.dispatch(any())).thenThrow(new JobDispatchException("k8s down"));

        ResilientJobDispatcher resilient = new ResilientJobDispatcher(delegate, breaker);

        Job job = Job.submit(new JobSpec("alice", "s3://b/i", "engine:1", 1), null, CLOCK);

        // window 채우기 — 두 번 실패 → OPEN.
        assertThatThrownBy(() -> resilient.dispatch(job)).isInstanceOf(JobDispatchException.class);
        assertThatThrownBy(() -> resilient.dispatch(job)).isInstanceOf(JobDispatchException.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // OPEN 상태에서 호출 — fast-fail. delegate 는 더 이상 안 불려야 한다.
        assertThatThrownBy(() -> resilient.dispatch(job))
                .isInstanceOf(JobDispatchException.class)
                .hasMessageContaining("circuit breaker OPEN");
    }

    /**
     * 정상 응답에는 회로가 OPEN 되지 않고 통과.
     */
    @Test
    void dispatch_passesThrough_onSuccess() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("k8s-test-ok");
        JobDispatcher delegate = mock(JobDispatcher.class);
        when(delegate.dispatch(any())).thenReturn("k8s-job-name");

        ResilientJobDispatcher resilient = new ResilientJobDispatcher(delegate, breaker);
        Job job = Job.submit(new JobSpec("alice", "s3://b/i", "engine:1", 1), null, CLOCK);

        assertThat(resilient.dispatch(job)).isEqualTo("k8s-job-name");
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
    }
}
