package com.example.gwp.orchestrator.config;

import com.example.gwp.orchestrator.adapter.kubernetes.JobDispatcher;
import com.example.gwp.orchestrator.adapter.kubernetes.KubernetesJobDispatcher;
import com.example.gwp.orchestrator.adapter.kubernetes.MockJobDispatcher;
import com.example.gwp.orchestrator.adapter.kubernetes.ResilientJobDispatcher;
import com.example.gwp.orchestrator.adapter.storage.MockPresignedUrlProvider;
import com.example.gwp.orchestrator.adapter.storage.PresignedUrlProvider;
import com.example.gwp.orchestrator.config.properties.GwpProperties;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdapterConfig {

    /**
     * fabric8 client 의 connect/request timeout 을 명시. 기본값(10s/10s)은 운영에서 짧을 수 있어
     * connect 5s / request 30s 로 설정. K8s API 가 응답하지 않을 때 워커 dispatch 가 무한 대기하는 걸 막는다.
     */
    @Bean
    @ConditionalOnProperty(name = "gwp.kubernetes.enabled", havingValue = "true")
    public KubernetesClient kubernetesClient() {
        var config = new ConfigBuilder()
                .withConnectionTimeout(5_000)
                .withRequestTimeout(30_000)
                .build();
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    /**
     * K8s 디스패처는 circuit breaker 로 감싸서 노출. fabric8 client 자체가 응답 없을 때
     * 호출 스레드가 hang 되는 것을 막고, 일정 비율 이상 실패 시 OPEN 으로 전환되어 같은
     * tick 의 후속 dispatch 가 즉시 fast-fail 로 떨어진다.
     */
    @Bean
    @ConditionalOnProperty(name = "gwp.kubernetes.enabled", havingValue = "true")
    public JobDispatcher kubernetesJobDispatcher(KubernetesClient client,
                                                 GwpProperties properties,
                                                 CircuitBreaker k8sCircuitBreaker) {
        var raw = new KubernetesJobDispatcher(client, properties);
        return new ResilientJobDispatcher(raw, k8sCircuitBreaker);
    }

    @Bean
    @ConditionalOnProperty(name = "gwp.kubernetes.enabled", havingValue = "false", matchIfMissing = true)
    public JobDispatcher mockJobDispatcher() {
        return new MockJobDispatcher();
    }

    @Bean
    public PresignedUrlProvider presignedUrlProvider() {
        return new MockPresignedUrlProvider();
    }
}
