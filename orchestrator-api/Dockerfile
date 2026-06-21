# ---- build stage ----
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /workspace

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ---- runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 베이스 이미지 빌드 이후 공개된 apk 보안 패치를 흡수한다 (base CVE 노출 축소).
# --no-cache 로 인덱스 캐시를 남기지 않아 레이어가 커지지 않는다.
RUN apk upgrade --no-cache

# OCI image annotations — 레지스트리 / 스캐너 / SBOM 도구가 출처를 추적.
LABEL org.opencontainers.image.title="orchestrator-api" \
      org.opencontainers.image.description="GPU Job 스케줄러 orchestrator-api (Spring Boot, Kotlin)" \
      org.opencontainers.image.source="https://github.com/ssa1004/gpu-job-orchestrator" \
      org.opencontainers.image.licenses="Apache-2.0"

RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=builder /workspace/build/libs/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
