package com.example.gwp.orchestrator.adapter.kubernetes;

import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobSpec;
import com.example.gwp.orchestrator.domain.JobStatus;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilientJobDispatcherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    private static Job sampleJob() {
        return Job.submit(new JobSpec("alice", "s3://b/i", "engine:1", 1), null, CLOCK);
    }

    private static CircuitBreaker openableCircuit() {
        return CircuitBreaker.of("k8s-test", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
    }

    private static Retry retryFor5xxOnly(int maxAttempts) {
        return Retry.of("k8s-test", RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(1))   // 테스트 빠르게 — 실 운영 500ms.
                .retryOnException(new RetryableExceptionPredicate())
                .build());
    }

    /**
     * minimum-number-of-calls 만큼 실패가 누적되면 OPEN — 그 시점부터는 같은 호출이
     * 즉시 fast-fail (JobDispatchException) 으로 떨어진다.
     */
    @Test
    void dispatch_failsfast_whenCircuitOpen() {
        CircuitBreaker breaker = openableCircuit();
        Retry retry = Retry.ofDefaults("k8s-test-retry");   // retry 비활성에 가까운 default

        JobDispatcher delegate = mock(JobDispatcher.class);
        when(delegate.dispatch(any())).thenThrow(new JobDispatchException("k8s down"));

        ResilientJobDispatcher resilient = new ResilientJobDispatcher(delegate, breaker, retry);

        // window 채우기 — 두 번 실패 → OPEN.
        assertThatThrownBy(() -> resilient.dispatch(sampleJob())).isInstanceOf(JobDispatchException.class);
        assertThatThrownBy(() -> resilient.dispatch(sampleJob())).isInstanceOf(JobDispatchException.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // OPEN 상태에서 호출 — fast-fail.
        assertThatThrownBy(() -> resilient.dispatch(sampleJob()))
                .isInstanceOf(JobDispatchException.class)
                .hasMessageContaining("circuit breaker OPEN");
    }

    /**
     * 정상 응답에는 회로가 OPEN 되지 않고 통과.
     */
    @Test
    void dispatch_passesThrough_onSuccess() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("k8s-test-ok");
        Retry retry = Retry.ofDefaults("k8s-test-ok-retry");
        JobDispatcher delegate = mock(JobDispatcher.class);
        when(delegate.dispatch(any())).thenReturn("k8s-job-name");

        ResilientJobDispatcher resilient = new ResilientJobDispatcher(delegate, breaker, retry);
        Job job = sampleJob();

        assertThat(resilient.dispatch(job)).isEqualTo("k8s-job-name");
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
    }

    /**
     * 5xx (transient) 면 retry 가 일어나서 두 번째 시도에서 성공 시 dispatch 정상 응답.
     */
    @Test
    void dispatch_retriesOn5xx_andSucceeds() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("k8s-retry-cb");
        Retry retry = retryFor5xxOnly(3);

        JobDispatcher delegate = mock(JobDispatcher.class);
        AtomicInteger calls = new AtomicInteger();
        when(delegate.dispatch(any())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n < 3) throw k8sException(503);
            return "ok-" + n;
        });

        ResilientJobDispatcher resilient = new ResilientJobDispatcher(delegate, breaker, retry);
        assertThat(resilient.dispatch(sampleJob())).isEqualTo("ok-3");
        verify(delegate, times(3)).dispatch(any());
    }

    /**
     * 4xx (영구 실패) 는 retry 안 함 — 첫 호출에서 즉시 fail.
     */
    @Test
    void dispatch_doesNotRetryOn4xx() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("k8s-no-retry-cb");
        Retry retry = retryFor5xxOnly(5);

        JobDispatcher delegate = mock(JobDispatcher.class);
        when(delegate.dispatch(any())).thenThrow(k8sException(409));   // conflict — 재시도 의미 X.

        ResilientJobDispatcher resilient = new ResilientJobDispatcher(delegate, breaker, retry);
        assertThatThrownBy(() -> resilient.dispatch(sampleJob()))
                .isInstanceOf(JobDispatchException.class);
        // delegate 가 정확히 *한 번* 만 호출 — retry 안 일어남.
        verify(delegate, times(1)).dispatch(any());
    }

    /**
     * Retry exhausted — max-attempts 다 써도 transient 풀리지 않으면 마지막 예외가 throw.
     */
    @Test
    void dispatch_retryExhausted_throwsLastException() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("k8s-exhaust-cb");
        Retry retry = retryFor5xxOnly(3);

        JobDispatcher delegate = mock(JobDispatcher.class);
        when(delegate.dispatch(any())).thenThrow(k8sException(503));

        ResilientJobDispatcher resilient = new ResilientJobDispatcher(delegate, breaker, retry);
        assertThatThrownBy(() -> resilient.dispatch(sampleJob()))
                .isInstanceOf(JobDispatchException.class);
        verify(delegate, times(3)).dispatch(any());
    }

    /**
     * 핵심 — Retry 가 *바깥쪽* 이라 회로 OPEN 이후의 retry 시도는 즉시 fast-fail 로 떨어진다.
     * 호출 횟수가 '시도 1번 + 회로 즉시 fast-fail' 로 끝나야 한다 — backend 가 hang 했을 때
     * 도 max-attempts 시간을 다 안 잡아먹는 의미.
     */
    @Test
    void dispatch_retryOutsideCircuit_fastFails_whenOpen() {
        CircuitBreaker breaker = openableCircuit();
        // 회로 미리 OPEN 으로 만들어둠.
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, k8sException(503));
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, k8sException(503));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Retry retry = retryFor5xxOnly(5);
        JobDispatcher delegate = mock(JobDispatcher.class);

        ResilientJobDispatcher resilient = new ResilientJobDispatcher(delegate, breaker, retry);
        assertThatThrownBy(() -> resilient.dispatch(sampleJob()))
                .isInstanceOf(JobDispatchException.class)
                .hasMessageContaining("circuit breaker OPEN");

        // delegate 는 한 번도 안 불렸다 — 회로가 모든 시도를 fast-fail.
        verify(delegate, times(0)).dispatch(any());
    }

    private static KubernetesClientException k8sException(int code) {
        var status = new StatusBuilder().withCode(code).withMessage("synthetic " + code).build();
        return new KubernetesClientException(status);
    }
}
