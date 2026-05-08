package com.example.gwp.orchestrator.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 외부 의존성 (Kafka broker, K8s API server) 호출 보호용 resilience4j 빈.
 *
 * <p>Resilience4j-spring-boot3 starter 가 application.yml 의
 * {@code resilience4j.circuitbreaker.instances.<name>.*} 와
 * {@code resilience4j.retry.instances.<name>.*} 설정으로 각각 registry 를 자동 wire.
 * 이 클래스는 그 registry 에서 이름 별로 인스턴스를 꺼내 명시적 빈으로 노출 — 호출 측
 * (OutboxRelay / KubernetesJobDispatcher) 가 생성자 주입으로 받는다.</p>
 *
 * <p>인스턴스:</p>
 * <ul>
 *   <li>{@code kafka} — Outbox relay 의 Kafka send 호출 보호 (retry + circuit).</li>
 *   <li>{@code k8s} — fabric8 client 의 K8s API 호출 보호 (retry + circuit).</li>
 * </ul>
 *
 * <p>이름이 application.yml 에 정의되지 않아도 registry 가 default 설정으로 알아서
 * 인스턴스를 만들어 주므로 NPE 없이 안전. 운영에서 임계값 튜닝은 yml 수정으로 끝.</p>
 */
@Configuration
public class CircuitBreakerConfig {

    @Bean
    public CircuitBreaker kafkaCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("kafka");
    }

    @Bean
    public CircuitBreaker k8sCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("k8s");
    }

    @Bean
    public Retry k8sRetry(RetryRegistry registry) {
        return registry.retry("k8s");
    }

    @Bean
    public Retry kafkaRetry(RetryRegistry registry) {
        return registry.retry("kafka");
    }
}
