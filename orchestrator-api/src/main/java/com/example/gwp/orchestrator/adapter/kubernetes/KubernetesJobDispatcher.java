package com.example.gwp.orchestrator.adapter.kubernetes;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import com.example.gwp.orchestrator.domain.Job;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
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
        String jobName = "gwp-job-" + job.getId();

        var k8sJob = new JobBuilder()
                .withNewMetadata()
                .withName(jobName)
                .withNamespace(k8s.namespace())
                .withLabels(Map.of(
                        "app.kubernetes.io/managed-by", "gwp-orchestrator",
                        "gwp.io/owner", job.getOwner(),
                        "gwp.io/job-id", job.getId().toString()
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
        } catch (Exception e) {
            throw new JobDispatchException("failed to create k8s job " + jobName, e);
        }
    }

    @Override
    public void cancel(String k8sJobName) {
        var ns = properties.kubernetes().namespace();
        try {
            client.batch().v1().jobs().inNamespace(ns).withName(k8sJobName).delete();
            log.info("k8s job deleted name={} namespace={}", k8sJobName, ns);
        } catch (Exception e) {
            throw new JobDispatchException("failed to delete k8s job " + k8sJobName, e);
        }
    }
}
