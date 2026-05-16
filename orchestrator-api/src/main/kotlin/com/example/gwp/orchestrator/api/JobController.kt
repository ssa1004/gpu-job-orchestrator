package com.example.gwp.orchestrator.api

import com.example.gwp.orchestrator.api.dto.JobResponse
import com.example.gwp.orchestrator.api.dto.JobSubmissionRequest
import com.example.gwp.orchestrator.api.dto.ResultUrlResponse
import com.example.gwp.orchestrator.application.JobAccessControl
import com.example.gwp.orchestrator.application.JobQueryService
import com.example.gwp.orchestrator.application.JobSubmissionService
import com.example.gwp.orchestrator.domain.JobSpec
import com.example.gwp.orchestrator.domain.JobStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "jobs", description = "GPU Job submission and lifecycle")
class JobController(
    private val jobSubmissionService: JobSubmissionService,
    private val jobQueryService: JobQueryService,
    private val jobAccessControl: JobAccessControl,
) {

    @PostMapping
    @Operation(summary = "Submit a GPU job (with optional parent dependencies)")
    open fun submit(
        @AuthenticationPrincipal jwt: Jwt?,
        @Valid @RequestBody req: JobSubmissionRequest,
    ): ResponseEntity<JobResponse> {
        val caller = Caller.from(jwt)
        val spec = JobSpec(
            caller.owner,
            req.inputUri,
            req.image,
            req.gpuCount,
            req.priority,
            req.preemptionPolicy,
        )
        val parents = req.parentJobIds
        val job = if (parents.isNullOrEmpty()) {
            jobSubmissionService.submit(spec)
        } else {
            jobSubmissionService.submit(spec, parents)
        }
        val location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(job.id).toUri()
        return ResponseEntity.created(location).body(JobResponse.from(job))
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get job by id (owner or admin)")
    open fun get(@AuthenticationPrincipal jwt: Jwt?, @PathVariable id: UUID): JobResponse {
        val caller = Caller.from(jwt)
        return JobResponse.from(jobAccessControl.getOwned(id, caller.owner, caller.isAdmin))
    }

    @GetMapping
    @Operation(summary = "List jobs of the authenticated user (admin can override owner)")
    open fun list(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam(required = false) owner: String?,
        @RequestParam(required = false) status: JobStatus?,
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
    ): Page<JobResponse> {
        val caller = Caller.from(jwt)
        val effectiveOwner = if (caller.isAdmin && owner != null) owner else caller.owner
        return jobQueryService.list(effectiveOwner, status, pageable).map { JobResponse.from(it) }
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a queued or running job (owner or admin)")
    open fun cancel(@AuthenticationPrincipal jwt: Jwt?, @PathVariable id: UUID): JobResponse {
        val caller = Caller.from(jwt)
        return JobResponse.from(jobAccessControl.cancelOwned(id, caller.owner, caller.isAdmin))
    }

    @GetMapping("/{id}/result-url")
    @Operation(summary = "Get presigned download URL for the job result (owner or admin)")
    open fun resultUrl(@AuthenticationPrincipal jwt: Jwt?, @PathVariable id: UUID): ResultUrlResponse {
        val caller = Caller.from(jwt)
        return ResultUrlResponse(
            jobAccessControl.resultUrlOwned(id, caller.owner, caller.isAdmin),
            3600,
        )
    }
}
