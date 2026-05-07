package com.example.gwp.orchestrator.api;

import com.example.gwp.orchestrator.api.exception.GlobalExceptionHandler;
import com.example.gwp.orchestrator.config.PermissiveSecurityConfig;
import com.example.gwp.orchestrator.domain.AccessDeniedException;
import com.example.gwp.orchestrator.domain.IllegalJobTransitionException;
import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.application.JobAccessControl;
import com.example.gwp.orchestrator.domain.JobNotFoundException;
import com.example.gwp.orchestrator.domain.JobPriority;
import com.example.gwp.orchestrator.domain.JobStatus;
import com.example.gwp.orchestrator.application.JobQueryService;
import com.example.gwp.orchestrator.domain.JobSpec;
import com.example.gwp.orchestrator.application.JobSubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JobController.class)
@Import({GlobalExceptionHandler.class, PermissiveSecurityConfig.class})
class JobControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @MockBean JobSubmissionService jobSubmissionService;
    @MockBean JobQueryService jobQueryService;
    @MockBean JobAccessControl jobAccessControl;
    @MockBean Tracer tracer;
    @MockBean Clock clock;

    @Test
    void submit_returns201_andLocationHeader() throws Exception {
        Job job = Job.submit(new JobSpec("anonymous", "s3://b/i", "engine:1.0", 1, JobPriority.NORMAL),
                "trace-1", CLOCK);
        job.markDispatched("k8s-1", CLOCK);
        when(jobSubmissionService.submit(any())).thenReturn(job);

        mvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "inputUri", "s3://bucket/in.bin",
                                "image", "engine:1.0",
                                "gpuCount", 1))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").value(job.getId().toString()))
                .andExpect(jsonPath("$.status").value("DISPATCHING"))
                .andExpect(jsonPath("$.priority").value("NORMAL"));
    }

    @Test
    void submit_returns400_onInvalidInput() throws Exception {
        mvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "inputUri", "not-an-s3-uri",
                                "image", "",
                                "gpuCount", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void submit_returns400_onMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void get_returns404_whenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobAccessControl.getOwned(eq(id), anyString(), anyBoolean()))
                .thenThrow(new JobNotFoundException(id));

        mvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void get_returns403_whenAccessDenied() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobAccessControl.getOwned(eq(id), anyString(), anyBoolean()))
                .thenThrow(new AccessDeniedException(id, "anonymous"));

        mvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void cancel_returns409_onIllegalTransition() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobAccessControl.cancelOwned(eq(id), anyString(), anyBoolean()))
                .thenThrow(new IllegalJobTransitionException(JobStatus.SUCCEEDED, JobStatus.CANCELLED));

        mvc.perform(post("/api/v1/jobs/{id}/cancel", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_JOB_TRANSITION"));
    }
}
