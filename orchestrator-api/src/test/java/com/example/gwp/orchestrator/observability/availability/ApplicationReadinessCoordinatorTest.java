package com.example.gwp.orchestrator.observability.availability;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coordinator 가 회로 상태에 따라 readiness 를 토글하는지 검증.
 *
 * <p>Spring Boot 의 {@link ApplicationAvailability} 는 단독 인스턴스화가 어려워 (실제
 * 구현은 ApplicationContext 안의 listener 에 의존), GenericApplicationContext 를 띄워
 * 진짜 publish/listen 을 검증한다.</p>
 */
class ApplicationReadinessCoordinatorTest {

    private AnnotationConfigApplicationContext ctx;
    private static CircuitBreakerRegistry registry;
    private CircuitBreaker breaker;
    private ApplicationReadinessCoordinator coordinator;
    private static AtomicReference<ReadinessState> latestRef;
    private final AtomicReference<ReadinessState> latest = new AtomicReference<>();

    /**
     * Spring 의 EventListener 어노테이션이 자동 매핑되도록 AnnotationConfigApplicationContext
     * 사용. Configuration class 의 @Bean 으로 빈을 선언하고 같은 컨텍스트에서 spy 도 wire.
     */
    @Configuration
    static class TestConfig {

        @Bean
        org.springframework.boot.availability.ApplicationAvailabilityBean availability() {
            return new org.springframework.boot.availability.ApplicationAvailabilityBean();
        }

        @Bean
        ReadinessSpy spy() {
            return new ReadinessSpy(latestRef);
        }

        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry() {
            return registry;
        }

        @Bean
        ApplicationReadinessCoordinator coordinator(
                org.springframework.context.ApplicationEventPublisher publisher,
                org.springframework.boot.availability.ApplicationAvailability availability,
                CircuitBreakerRegistry circuitBreakerRegistry) {
            return new ApplicationReadinessCoordinator(publisher, availability, circuitBreakerRegistry);
        }
    }

    @BeforeEach
    void setUp() {
        latestRef = latest;

        // 작은 sliding window 의 회로 1개로 빠른 트리거.
        var cfg = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        registry = CircuitBreakerRegistry.of(cfg);
        breaker = registry.circuitBreaker("k8s-test");

        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        coordinator = ctx.getBean(ApplicationReadinessCoordinator.class);

        // 초기에는 ACCEPTING_TRAFFIC.
        AvailabilityChangeEvent.publish(ctx, this, ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void readiness_flipsToRefusing_whenAnyCircuitOpens() {
        // window 채워서 OPEN.
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("boom"));
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("boom"));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 회로의 onStateTransition 이벤트가 listener (coordinator) 를 동기적으로 호출.
        assertThat(latest.get()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
    }

    @Test
    void readiness_returnsToAccepting_whenAllCircuitsClose() {
        // 우선 OPEN.
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("boom"));
        breaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("boom"));
        assertThat(latest.get()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);

        // OPEN → HALF_OPEN 강제 후 성공으로 CLOSED.
        breaker.transitionToHalfOpenState();
        breaker.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        breaker.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        breaker.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        // HALF_OPEN 의 success 가 임계 채우면 자동 CLOSED.

        // listener 가 CLOSED 전이까지 받았으면 ACCEPTING.
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(latest.get()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void markUnready_publishesRefusingTraffic_directly() {
        coordinator.markUnready();
        assertThat(latest.get()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
    }

    @Test
    void gracefulShutdownEvent_flipsToRefusing() {
        ctx.publishEvent(new ApplicationReadinessCoordinator.GracefulShutdownInitiated());
        assertThat(latest.get()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
    }

    /** AvailabilityChangeEvent 를 listen 해서 최신 ReadinessState 를 기록. */
    static class ReadinessSpy {
        private final AtomicReference<ReadinessState> latest;
        ReadinessSpy(AtomicReference<ReadinessState> latest) { this.latest = latest; }

        @EventListener
        public void onChange(AvailabilityChangeEvent<ReadinessState> event) {
            latest.set(event.getState());
        }
    }
}
