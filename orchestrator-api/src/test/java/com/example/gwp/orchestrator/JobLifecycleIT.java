package com.example.gwp.orchestrator;

import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.application.JobLifecycleService;
import com.example.gwp.orchestrator.application.JobQueryService;
import com.example.gwp.orchestrator.domain.JobSpec;
import com.example.gwp.orchestrator.domain.JobStatus;
import com.example.gwp.orchestrator.application.JobSubmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E (Postgres) — submit → callback → result URL 흐름. 마이그레이션 V1+V2 적용.
 * (V3 PG-only 도 운영 location 활성화 시 같이 적용 가능하지만, IT 는 default location 만 사용.)
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("it")
class JobLifecycleIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JobSubmissionService jobSubmissionService;
    @Autowired JobLifecycleService jobLifecycleService;
    @Autowired JobQueryService jobQueryService;

    @Test
    void submit_then_callback_then_resultUrl() {
        Job submitted = jobSubmissionService.submit(new JobSpec(
                "alice", "s3://bucket/input.bin", "engine:1.0", 1));
        UUID id = submitted.getId();

        assertThat(submitted.getStatus()).isIn(JobStatus.DISPATCHING, JobStatus.QUEUED);

        jobLifecycleService.updateStatusFromCallback(id, JobStatus.RUNNING, null, null);
        assertThat(jobQueryService.get(id).getStatus()).isEqualTo(JobStatus.RUNNING);

        jobLifecycleService.updateStatusFromCallback(id, JobStatus.SUCCEEDED, "s3://bucket/out.bin", null);
        Job done = jobQueryService.get(id);
        assertThat(done.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.getResultUri()).isEqualTo("s3://bucket/out.bin");

        String url = jobQueryService.resultUrl(id);
        assertThat(url).contains("s3://bucket/out.bin");
    }
}
