package com.example.gwp.orchestrator.config

import com.example.gwp.orchestrator.adapter.kubernetes.JobDispatcher
import com.example.gwp.orchestrator.adapter.kubernetes.KubernetesJobDispatcher
import com.example.gwp.orchestrator.adapter.kubernetes.MockJobDispatcher
import com.example.gwp.orchestrator.adapter.kubernetes.ResilientJobDispatcher
import com.example.gwp.orchestrator.adapter.storage.MockPresignedUrlProvider
import com.example.gwp.orchestrator.adapter.storage.PresignedUrlProvider
import com.example.gwp.orchestrator.config.properties.GwpProperties
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import io.micrometer.tracing.BaggageManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AdapterConfig {

    /**
     * fabric8 client 의 connect/request timeout 을 명시. 기본값(10s/10s)은 운영에서 짧을 수 있어
     * connect 5s / request 30s 로 설정. K8s API 가 응답하지 않을 때 워커 dispatch 가 무한 대기하는 걸 막는다.
     */
    @Bean
    @ConditionalOnProperty(name = ["gwp.kubernetes.enabled"], havingValue = "true")
    open fun kubernetesClient(): KubernetesClient {
        val config = ConfigBuilder()
            .withConnectionTimeout(5_000)
            .withRequestTimeout(30_000)
            .build()
        return KubernetesClientBuilder().withConfig(config).build()
    }

    /**
     * K8s 디스패처는 retry + circuit breaker 로 감싸서 노출. transient 오류 (network blip,
     * 429, 503) 는 retry 가 흡수, 영구 장애는 circuit breaker 가 fast-fail. Retry 가
     * CircuitBreaker 의 *바깥쪽* 이라 회로가 OPEN 인 상태에서 retry 시도가 즉시 fast-fail
     * 로 떨어진다 — Resilience4j 표준 권장 chain. ADR-0025 참고.
     */
    @Bean
    @ConditionalOnProperty(name = ["gwp.kubernetes.enabled"], havingValue = "true")
    open fun kubernetesJobDispatcher(
        client: KubernetesClient,
        properties: GwpProperties,
        k8sCircuitBreaker: CircuitBreaker,
        k8sRetry: Retry,
        baggageManagerProvider: ObjectProvider<BaggageManager>,
    ): JobDispatcher {
        // baggage manager 가 있으면 worker Pod env 로 OTEL_BAGGAGE 흘림 (ADR-0021).
        // tracing bridge 미설치 환경 (테스트 / 로컬) 에서는 NOOP fallback.
        val baggageManager = baggageManagerProvider.getIfAvailable { BaggageManager.NOOP }
        val raw = KubernetesJobDispatcher(client, properties, baggageManager)
        return ResilientJobDispatcher(raw, k8sCircuitBreaker, k8sRetry)
    }

    @Bean
    @ConditionalOnProperty(
        name = ["gwp.kubernetes.enabled"],
        havingValue = "false",
        matchIfMissing = true,
    )
    open fun mockJobDispatcher(): JobDispatcher = MockJobDispatcher()

    @Bean
    open fun presignedUrlProvider(): PresignedUrlProvider = MockPresignedUrlProvider()
}
