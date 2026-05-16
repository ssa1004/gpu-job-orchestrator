package com.example.gwp.orchestrator.observability.baggage

import io.micrometer.tracing.BaggageManager
import io.micrometer.tracing.Tracer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Baggage 관련 빈 wiring + interceptor 등록.
 *
 * [Tracer] 는 Spring Boot 3 + Micrometer Tracing 의 자동설정으로 항상 등록됨.
 * BaggageManager 는 OTel bridge 에서 같이 등록됨 — 이 둘이 모두 있을 때만 활성.
 *
 * [BaggageHandlerInterceptor] 는 모든 `/api/` 하위 컨트롤러에 적용.
 * `/actuator/`, `/v3/api-docs` 같은 메타 엔드포인트는 baggage 채울
 * 사용자 컨텍스트가 없으니 제외.
 */
@Configuration
@ConditionalOnBean(Tracer::class)
class BaggageWebConfig {

    @Bean
    open fun baggagePopulator(baggageManagerProvider: ObjectProvider<BaggageManager>): BaggagePopulator {
        // BaggageManager 빈이 없으면 1.4.x 가 제공하는 NOOP 상수로 fallback — H2/dev 또는
        // tracing bridge 미설치 환경에서도 BaggagePopulator 가 안전하게 동작.
        val manager = baggageManagerProvider.getIfAvailable { BaggageManager.NOOP }
        return BaggagePopulator(manager)
    }

    @Bean
    open fun baggageHandlerInterceptor(populator: BaggagePopulator): BaggageHandlerInterceptor =
        BaggageHandlerInterceptor(populator)

    @Bean
    open fun baggageWebMvcConfigurer(interceptor: BaggageHandlerInterceptor): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                val registration = registry.addInterceptor(interceptor)
                registration.addPathPatterns("/api/**")
                registration.excludePathPatterns(
                    "/actuator/**",
                    "/v3/api-docs/**",
                    "/swagger",
                    "/swagger-ui/**",
                )
            }
        }
}
