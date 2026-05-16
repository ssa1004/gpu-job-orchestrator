package com.example.gwp.orchestrator.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    open fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("GPU Workload Platform - Orchestrator API")
            .version("0.1.0")
            .description("REST API for submitting and managing GPU jobs"),
    )
}
