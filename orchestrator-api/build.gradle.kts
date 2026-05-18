plugins {
    java
    // Kotlin 도입 — domain 패키지를 점진적으로 Kotlin 으로 마이그레이션. Java 호출자는
    // @JvmStatic / @get:JvmName / @JvmRecord 로 무변경 호환. plugin.spring 은 Spring 빈
    // 클래스를 자동 open 처리, plugin.jpa 는 @Entity 에 noarg 생성자를 합성.
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"
    // plugin.lombok — Kotlin 컴파일러가 Java 의 Lombok 어노테이션 (특히 @Getter / @Builder)
    // 으로 합성된 메서드를 인식하도록 한다. 도메인이 Java + Kotlin 혼재하는 동안 필요.
    kotlin("plugin.lombok") version "1.9.25"
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example.gwp"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // 인터페이스 default 메서드를 Java 측에 그대로 노출 (-Xjvm-default=all).
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

repositories {
    mavenCentral()
}

extra["springCloudVersion"] = "2023.0.5"
extra["fabric8Version"] = "6.13.5"
extra["openTelemetryVersion"] = "1.42.0"

dependencies {
    // Web + validation
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Security (OAuth2 Resource Server with JWT)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Persistence
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // Cache (Redis cache-aside)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // Messaging (Kafka)
    implementation("org.springframework.kafka:spring-kafka")

    // Resilience4j — 외부 의존성 (Kafka broker, K8s API server) 호출에 circuit breaker 를
    // 끼워 backend 장애 시 hot-loop 또는 hang 을 막는다. spring-boot3 starter 가
    // application.yml 의 resilience4j.circuitbreaker.* 설정을 자동 wiring.
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")

    // Observability
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Kubernetes client
    implementation("io.fabric8:kubernetes-client:${property("fabric8Version")}")

    // ShedLock — 다중 인스턴스에서 @Scheduled 메서드를 한 번에 한 노드만 돌리도록 DB
    // 행 락으로 보장. OutboxRelay / PreemptionScheduler / DependencyScanScheduler 가 사용.
    implementation("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0")

    // S3 (presigned URL) - production impl: add software.amazon.awssdk:s3-presigner
    // and uncomment S3PresignedUrlProvider. Default profile uses MockPresignedUrlProvider.

    // OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Jackson YAML — AsyncAPI spec 출력 (contract 패키지) 에 사용. starter-web 이 끌어오는
    // Jackson core 와 같은 BOM 으로 버전 관리 (별도 명시 안 함).
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // Utilities
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}
