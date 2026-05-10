package com.example.gwp.orchestrator.adapter.kubernetes;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.observability.baggage.JobBaggage;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.micrometer.tracing.BaggageManager;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class KubernetesJobDispatcher implements JobDispatcher {

    private final KubernetesClient client;
    private final GwpProperties properties;
    /**
     * Worker Pod 으로 W3C baggage 를 env var 로 흘릴 때 사용. 빈이 없으면 (테스트 / tracing
     * bridge 미설치) {@link BaggageManager#NOOP} — getAllBaggage 가 빈 맵이라 OTEL_BAGGAGE
     * env 자체가 안 박힌다.
     */
    private final BaggageManager baggageManager;

    /** 기존 호출자 호환 — BaggageManager 없는 환경 (테스트) 에서는 NOOP fallback. */
    public KubernetesJobDispatcher(KubernetesClient client, GwpProperties properties) {
        this(client, properties, BaggageManager.NOOP);
    }

    public KubernetesJobDispatcher(KubernetesClient client,
                                   GwpProperties properties,
                                   BaggageManager baggageManager) {
        this.client = client;
        this.properties = properties;
        this.baggageManager = baggageManager != null ? baggageManager : BaggageManager.NOOP;
    }

    @Override
    public String dispatch(Job job) {
        var k8s = properties.kubernetes();
        var callbackSecret = properties.callback().sharedSecret();
        // 도메인 invariant 가 잡힌 후 (gpuCount >= 1) 라도 K8s manifest 로 변환할 때 한 번
        // 더 검증 — 0 이나 음수가 들어오면 K8s 에서 의미 없는 요청이 생성됨. 이 path 는
        // tests / mock dispatch 에서도 실제 fabric8 가 처리할 일이 없는 corner.
        if (job.getGpuCount() <= 0) {
            throw new JobDispatchException("invalid gpuCount=" + job.getGpuCount() + " for job=" + job.getId());
        }
        String jobName = "gwp-job-" + job.getId();

        // owner 는 JWT subject — 이메일 (e.g. user@org) 등 K8s label value 에 허용되지 않는
        // 문자가 들어갈 수 있어 그대로 라벨에 박으면 K8s API 가 422 로 거절. KubernetesLabels
        // 로 sanitize 해서 alphanumeric / `-_.` 만 남긴다 (label injection 방지 겸용).
        // 원본 owner 는 annotation 으로 따로 보존 (annotation value 는 자유 형식 허용).
        String ownerLabel = KubernetesLabels.sanitizeLabelValue(job.getOwner());
        var k8sJob = new JobBuilder()
                .withNewMetadata()
                .withName(jobName)
                .withNamespace(k8s.namespace())
                .withLabels(Map.of(
                        "app.kubernetes.io/managed-by", "gwp-orchestrator",
                        "gwp.io/owner", ownerLabel,
                        "gwp.io/job-id", job.getId().toString()
                ))
                .withAnnotations(Map.of(
                        "gwp.io/owner-original", job.getOwner() != null ? job.getOwner() : ""
                ))
                .endMetadata()
                .withNewSpec()
                .withTtlSecondsAfterFinished(k8s.jobTtlSeconds())
                .withBackoffLimit(2)
                .withNewTemplate()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withContainers(new ContainerBuilder()
                        .withName("worker")
                        .withImage(job.getImage())
                        .withEnv(buildEnv(job, k8s.callbackUrl(), callbackSecret))
                        .withResources(new ResourceRequirementsBuilder()
                                .withLimits(Map.of("nvidia.com/gpu", new Quantity(String.valueOf(job.getGpuCount()))))
                                .build())
                        .build())
                .withTolerations(new TolerationBuilder()
                        .withKey("nvidia.com/gpu")
                        .withOperator("Exists")
                        .withEffect("NoSchedule")
                        .build())
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        try {
            client.batch().v1().jobs().inNamespace(k8s.namespace()).resource(k8sJob).create();
            log.info("k8s job created name={} namespace={} jobId={}", jobName, k8s.namespace(), job.getId());
            return jobName;
        } catch (KubernetesClientException e) {
            // fabric8 client 의 표준 예외만 잡고 다른 예외 (RuntimeException 등) 는 그대로
            // 위로 전파 — 예상 못한 NPE 같은 버그가 dispatch 실패로 둔갑되어 디버깅이
            // 어려워지는 걸 방지. KubernetesClientException 은 API 응답 코드 / 메시지가
            // 들어 있으니 운영 추적용 로그에 일부 남긴다.
            log.warn("k8s job create failed name={} namespace={} code={} reason={}",
                    jobName, k8s.namespace(), e.getCode(), e.getMessage());
            throw new JobDispatchException("failed to create k8s job " + jobName, e);
        }
    }

    @Override
    public void cancel(String k8sJobName) {
        var ns = properties.kubernetes().namespace();
        try {
            client.batch().v1().jobs().inNamespace(ns).withName(k8sJobName).delete();
            log.info("k8s job deleted name={} namespace={}", k8sJobName, ns);
        } catch (KubernetesClientException e) {
            log.warn("k8s job delete failed name={} namespace={} code={} reason={}",
                    k8sJobName, ns, e.getCode(), e.getMessage());
            throw new JobDispatchException("failed to delete k8s job " + k8sJobName, e);
        }
    }

    /**
     * Worker Pod 의 env var 목록 구성. 핵심:
     * <ul>
     *   <li>traceId 가 비어 있으면 OTEL_TRACE_ID env 자체를 안 박는다 — 예전엔 null value
     *       env 가 manifest 에 그대로 들어가 K8s 가 빈 문자열로 직렬화, worker 가 빈 trace
     *       id 를 'set 됨' 으로 오해할 위험이 있었다.</li>
     *   <li>현재 활성 baggage (owner / cost-center / priority) 를 W3C baggage 헤더 포맷
     *       (RFC 9.5.3) 으로 직렬화해 OTEL_BAGGAGE 로 흘린다. consumer (worker) 측이 baggage
     *       을 자기 trace 에 복원하면 cross-system propagation 완성 (ADR-0021 의 worker
     *       wiring 후속).</li>
     * </ul>
     */
    List<EnvVar> buildEnv(Job job, String callbackUrl, String callbackSecret) {
        List<EnvVar> env = new ArrayList<>(8);
        env.add(new EnvVar("JOB_ID", job.getId().toString(), null));
        env.add(new EnvVar("INPUT_URI", job.getInputUri(), null));
        env.add(new EnvVar("CALLBACK_URL", callbackUrl + "/" + job.getId() + "/status", null));
        // SECURITY: 운영에서는 K8s Secret + projected volume mount (Secret 을 파일로 Pod
        //           안에 마운트) 권장. env var 로 주입하면 같은 Pod 안의 모든 프로세스가
        //           읽을 수 있음. dev 편의로 일단 env 주입.
        env.add(new EnvVarBuilder()
                .withName("CALLBACK_SECRET")
                .withNewValueFrom()
                    .withNewSecretKeyRef()
                        .withName("gwp-callback-secret")
                        .withKey("token")
                        .withOptional(true)   // 미존재 시 fallback (아래 plain env)
                    .endSecretKeyRef()
                .endValueFrom()
                .build());
        env.add(new EnvVar("CALLBACK_SECRET_FALLBACK", callbackSecret, null));

        // OTEL_TRACE_ID — null 이면 env 자체 skip. worker 가 'set 안 됨' 과 '빈 문자열로
        // set' 을 구분할 수 있도록.
        String traceId = job.getTraceId();
        if (traceId != null && !traceId.isBlank()) {
            env.add(new EnvVar("OTEL_TRACE_ID", traceId, null));
        }

        // OTEL_BAGGAGE — 활성 baggage 가 있을 때만. JobBaggage 화이트리스트 외 키는 drop.
        String baggageHeader = currentBaggageHeader();
        if (baggageHeader != null) {
            env.add(new EnvVar("OTEL_BAGGAGE", baggageHeader, null));
        }
        return env;
    }

    /**
     * 현재 활성 baggage 를 W3C baggage 헤더 (RFC 9.5.3) 단일 문자열로 직렬화.
     * 화이트리스트 ({@link JobBaggage#ALLOWED}) 외 키는 drop, 비어 있으면 null.
     *
     * <p>OutboxWriter 의 같은 이름 helper 와 동일한 포맷 — 도메인 일관성 유지. 둘이
     * 변형되면 cross-system propagation 이 깨지므로 같은 RFC 포맷을 명시적으로 지킨다.</p>
     */
    private String currentBaggageHeader() {
        Map<String, String> all = baggageManager.getAllBaggage();
        if (all == null || all.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (var entry : all.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!JobBaggage.isAllowed(key, value)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(key).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
