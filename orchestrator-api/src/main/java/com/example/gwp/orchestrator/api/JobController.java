package com.example.gwp.orchestrator.api;

import com.example.gwp.orchestrator.api.dto.*;
import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.application.JobAccessControl;
import com.example.gwp.orchestrator.application.JobQueryService;
import com.example.gwp.orchestrator.domain.JobSpec;
import com.example.gwp.orchestrator.domain.JobStatus;
import com.example.gwp.orchestrator.application.JobSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
@Tag(name = "jobs", description = "GPU Job submission and lifecycle")
public class JobController {

    private final JobSubmissionService jobSubmissionService;
    private final JobQueryService jobQueryService;
    private final JobAccessControl jobAccessControl;

    @PostMapping
    @Operation(summary = "Submit a GPU job (with optional parent dependencies)")
    public ResponseEntity<JobResponse> submit(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody JobSubmissionRequest req
    ) {
        var caller = Caller.from(jwt);
        var spec = new JobSpec(
                caller.owner(),
                req.inputUri(),
                req.image(),
                req.gpuCount(),
                req.priority(),
                req.preemptionPolicy());
        Job job = (req.parentJobIds() == null || req.parentJobIds().isEmpty())
                ? jobSubmissionService.submit(spec)
                : jobSubmissionService.submit(spec, req.parentJobIds());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(job.getId()).toUri();
        return ResponseEntity.created(location).body(JobResponse.from(job));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get job by id (owner or admin)")
    public JobResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var caller = Caller.from(jwt);
        return JobResponse.from(jobAccessControl.getOwned(id, caller.owner(), caller.isAdmin()));
    }

    @GetMapping
    @Operation(summary = "List jobs of the authenticated user (admin can override owner)")
    public Page<JobResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) JobStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        var caller = Caller.from(jwt);
        String effectiveOwner = caller.isAdmin() && owner != null ? owner : caller.owner();
        return jobQueryService.list(effectiveOwner, status, pageable).map(JobResponse::from);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a queued or running job (owner or admin)")
    public JobResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var caller = Caller.from(jwt);
        return JobResponse.from(jobAccessControl.cancelOwned(id, caller.owner(), caller.isAdmin()));
    }

    @GetMapping("/{id}/result-url")
    @Operation(summary = "Get presigned download URL for the job result (owner or admin)")
    public ResultUrlResponse resultUrl(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var caller = Caller.from(jwt);
        return new ResultUrlResponse(
                jobAccessControl.resultUrlOwned(id, caller.owner(), caller.isAdmin()),
                3600);
    }
}
