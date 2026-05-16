package com.example.gwp.orchestrator

import com.example.gwp.orchestrator.config.properties.GwpProperties
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [GwpProperties::class])
class OrchestratorApplication

fun main(args: Array<String>) {
    SpringApplication.run(OrchestratorApplication::class.java, *args)
}
