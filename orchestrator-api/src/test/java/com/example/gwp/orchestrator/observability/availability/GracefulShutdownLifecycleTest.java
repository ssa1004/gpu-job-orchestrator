package com.example.gwp.orchestrator.observability.availability;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SmartLifecycle 이 stop() 호출 시 정확히 한 번 GracefulShutdownInitiated 를 publish 하는지 검증.
 */
class GracefulShutdownLifecycleTest {

    @Test
    void stop_publishesGracefulShutdownEvent_onlyOnce() {
        AtomicInteger count = new AtomicInteger(0);
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof ApplicationReadinessCoordinator.GracefulShutdownInitiated) {
                count.incrementAndGet();
            }
        };
        var lifecycle = new GracefulShutdownLifecycle(publisher);

        // start → stop → stop (idempotent)
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();

        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(count.get()).isEqualTo(1);

        // 두 번째 stop 은 무시 — running flag CAS 가 false → 조기 return.
        lifecycle.stop();
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void phase_isVeryLow_soStopsFirst() {
        var lifecycle = new GracefulShutdownLifecycle(event -> {});
        // 가장 먼저 stop() 되어야 — 다른 lifecycle bean 들의 phase=0 보다 훨씬 낮음.
        assertThat(lifecycle.getPhase()).isLessThan(0);
        assertThat(lifecycle.getPhase()).isLessThan(Integer.MIN_VALUE + 1000);
    }
}
