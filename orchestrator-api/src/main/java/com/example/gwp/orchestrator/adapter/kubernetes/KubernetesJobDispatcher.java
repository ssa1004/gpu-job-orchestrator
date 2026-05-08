package com.example.gwp.orchestrator.adapter.kubernetes;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import com.example.gwp.orchestrator.domain.Job;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public class KubernetesJobDispatcher implements JobDispatcher {

    private final KubernetesClient client;
    private final GwpProperties properties;

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
                        .withEnv(List.of(
                                new EnvVar("JOB_ID", job.getId().toString(), null),
                                new EnvVar("INPUT_URI", job.getInputUri(), null),
                                new EnvVar("CALLBACK_URL", k8s.callbackUrl() + "/" + job.getId() + "/status", null),
                                // SECURITY: 운영에서는 K8s Secret + projected volume mount (Secret 을 파일로
                                //           Pod 안에 마운트하는 방식) 권장. env var 로 주입하면 같은 Pod 안의
                                //           모든 프로세스가 읽을 수 있음. dev 편의로 일단 env 주입.
                                new EnvVarBuilder()
                                        .withName("CALLBACK_SECRET")
                                        .withNewValueFrom()
                                            .withNewSecretKeyRef()
                                                .withName("gwp-callback-secret")
                                                .withKey("token")
                                                .withOptional(true)   // 미존재 시 기본 fallback (아래 plain env)
                                            .endSecretKeyRef()
                                        .endValueFrom()
                                        .build(),
                                new EnvVar("CALLBACK_SECRET_FALLBACK", callbackSecret, null),
                                new EnvVar("OTEL_TRACE_ID", job.getTraceId(), null)
                        ))
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
}
