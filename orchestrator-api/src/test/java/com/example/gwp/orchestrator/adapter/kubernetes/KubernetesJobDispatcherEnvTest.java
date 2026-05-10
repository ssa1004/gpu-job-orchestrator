package com.example.gwp.orchestrator.adapter.kubernetes;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobPriority;
import com.example.gwp.orchestrator.domain.JobSpec;
import com.example.gwp.orchestrator.domain.PreemptionPolicy;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.micrometer.tracing.BaggageManager;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KubernetesJobDispatcher.buildEnv 회귀 테스트 — Pod env 구성의 미묘한 corner.
 *
 * <ul>
 *   <li>traceId 가 null/blank 이면 OTEL_TRACE_ID env 자체를 안 박는다 (예전엔 null value
 *       env 가 manifest 에 들어가 K8s 가 빈 문자열로 직렬화 → worker 가 'set 됨' 으로 오해).</li>
 *   <li>활성 baggage 가 있으면 OTEL_BAGGAGE 를 W3C 헤더 포맷 (RFC 9.5.3) 으로 흘린다.
 *       JobBaggage 화이트리스트 외 키는 silent drop.</li>
 * </ul>
 */
class KubernetesJobDispatcherEnvTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);
    private static final GwpProperties.Kubernetes K8S = new GwpProperties.Kubernetes(
            true, "gwp-jobs", 86400, "http://orchestrator-api/internal/jobs");

    private static Job sampleJob(String traceId) {
        Job j = Job.submit(new JobSpec("alice", "s3://b/in", "engine:1", 1,
                JobPriority.NORMAL, PreemptionPolicy.PREEMPTABLE), traceId, CLOCK);
        return j;
    }

    private static KubernetesJobDispatcher dispatcher(BaggageManager baggageManager) {
        // KubernetesClient 는 buildEnv 가 안 쓰므로 null 로 OK — 테스트 대상은 env 구성 로직만.
        return new KubernetesJobDispatcher(null, null, baggageManager);
    }

    /** 정상 traceId — OTEL_TRACE_ID 가 들어간다. baggage 없으면 OTEL_BAGGAGE 는 빠짐. */
    @Test
    void buildEnv_withTraceId_includesOtelTraceId() {
        Job job = sampleJob("trace-123");
        var env = dispatcher(BaggageManager.NOOP).buildEnv(job, K8S.callbackUrl(), "secret");

        assertThat(envValue(env, "OTEL_TRACE_ID")).isEqualTo("trace-123");
        assertThat(envNames(env)).doesNotContain("OTEL_BAGGAGE");
    }

    /**
     * <b>핵심 회귀</b> — traceId 가 null 일 때 OTEL_TRACE_ID env 자체가 없어야.
     * 예전 구현은 {@code new EnvVar("OTEL_TRACE_ID", null, null)} 로 manifest 에 박았다.
     */
    @Test
    void buildEnv_nullTraceId_skipsOtelTraceIdEnv() {
        Job job = sampleJob(null);
        var env = dispatcher(BaggageManager.NOOP).buildEnv(job, K8S.callbackUrl(), "secret");

        assertThat(envNames(env)).doesNotContain("OTEL_TRACE_ID");
        // 다른 핵심 env 는 그대로 박혀야 함
        assertThat(envValue(env, "JOB_ID")).isEqualTo(job.getId().toString());
        assertThat(envValue(env, "INPUT_URI")).isEqualTo(job.getInputUri());
    }

    /** blank traceId 도 동일 — empty string 이 OTEL_TRACE_ID 로 박히면 안 됨. */
    @Test
    void buildEnv_blankTraceId_skipsOtelTraceIdEnv() {
        Job job = sampleJob("   ");
        var env = dispatcher(BaggageManager.NOOP).buildEnv(job, K8S.callbackUrl(), "secret");

        assertThat(envNames(env)).doesNotContain("OTEL_TRACE_ID");
    }

    /**
     * 활성 baggage 가 있으면 OTEL_BAGGAGE 가 W3C 포맷으로 박힌다. 화이트리스트
     * (owner / cost-center / priority) 외 키는 drop.
     */
    @Test
    void buildEnv_activeBaggage_propagatesAsOtelBaggageEnv() {
        BaggageManager bm = stubBaggageManager(Map.of(
                "owner", "alice",
                "cost-center", "research-vision",
                "secret-token", "leak"   // 화이트리스트 외 — drop 되어야
        ));
        Job job = sampleJob("trace-xyz");

        var env = dispatcher(bm).buildEnv(job, K8S.callbackUrl(), "secret");

        String baggage = envValue(env, "OTEL_BAGGAGE");
        assertThat(baggage).isNotNull();
        // RFC 9.5.3 — key=value, 콤마 구분. 키 순서는 BaggageManager 구현에 따름.
        assertThat(baggage).contains("owner=alice");
        assertThat(baggage).contains("cost-center=research-vision");
        assertThat(baggage).doesNotContain("secret-token");
        assertThat(baggage).doesNotContain("leak");
    }

    /** baggage 가 비어 있거나 모든 키가 화이트리스트 외 → OTEL_BAGGAGE env 자체 없음. */
    @Test
    void buildEnv_emptyOrAllDroppedBaggage_skipsOtelBaggageEnv() {
        BaggageManager empty = stubBaggageManager(Map.of());
        BaggageManager allDropped = stubBaggageManager(Map.of("not-in-whitelist", "x"));
        Job job = sampleJob("trace-1");

        assertThat(envNames(dispatcher(empty).buildEnv(job, K8S.callbackUrl(), "secret")))
                .doesNotContain("OTEL_BAGGAGE");
        assertThat(envNames(dispatcher(allDropped).buildEnv(job, K8S.callbackUrl(), "secret")))
                .doesNotContain("OTEL_BAGGAGE");
    }

    /** 핵심 callback / job 식별 env 는 traceId / baggage 와 무관하게 항상 박혀야. */
    @Test
    void buildEnv_alwaysIncludesCoreCallbackEnv() {
        Job job = sampleJob(null);
        var env = dispatcher(BaggageManager.NOOP).buildEnv(job, K8S.callbackUrl(), "secret-token");

        assertThat(envNames(env))
                .contains("JOB_ID", "INPUT_URI", "CALLBACK_URL",
                        "CALLBACK_SECRET", "CALLBACK_SECRET_FALLBACK");
        assertThat(envValue(env, "CALLBACK_URL"))
                .isEqualTo(K8S.callbackUrl() + "/" + job.getId() + "/status");
        assertThat(envValue(env, "CALLBACK_SECRET_FALLBACK")).isEqualTo("secret-token");
    }

    private static String envValue(List<EnvVar> env, String name) {
        return env.stream()
                .filter(e -> e.getName().equals(name))
                .map(EnvVar::getValue)
                .findFirst()
                .orElse(null);
    }

    private static List<String> envNames(List<EnvVar> env) {
        return env.stream().map(EnvVar::getName).toList();
    }

    /** 테스트용 BaggageManager — getAllBaggage 만 의미 있게 stub. */
    private static BaggageManager stubBaggageManager(Map<String, String> baggage) {
        return new BaggageManager() {
            @Override
            public Map<String, String> getAllBaggage() {
                return baggage;
            }

            @Override
            public Map<String, String> getAllBaggage(io.micrometer.tracing.TraceContext traceContext) {
                return baggage;
            }

            @Override
            public io.micrometer.tracing.Baggage getBaggage(String name) {
                return null;
            }

            @Override
            public io.micrometer.tracing.Baggage getBaggage(io.micrometer.tracing.TraceContext traceContext, String name) {
                return null;
            }

            @Override
            @Deprecated
            public io.micrometer.tracing.Baggage createBaggage(String name) {
                return null;
            }

            @Override
            @Deprecated
            public io.micrometer.tracing.Baggage createBaggage(String name, String value) {
                return null;
            }

            @Override
            public io.micrometer.tracing.BaggageInScope createBaggageInScope(String name, String value) {
                return null;
            }

            @Override
            public io.micrometer.tracing.BaggageInScope createBaggageInScope(io.micrometer.tracing.TraceContext traceContext, String name, String value) {
                return null;
            }
        };
    }
}
