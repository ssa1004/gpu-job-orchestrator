package com.example.gwp.orchestrator.api

import com.example.gwp.orchestrator.api.dto.JobResponse
import com.example.gwp.orchestrator.api.dto.StatusCallbackRequest
import com.example.gwp.orchestrator.application.JobLifecycleService
import com.example.gwp.orchestrator.config.properties.GwpProperties
import io.swagger.v3.oas.annotations.Hidden
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@RestController
@RequestMapping("/internal/jobs")
@Hidden
class InternalCallbackController(
    private val jobLifecycleService: JobLifecycleService,
    private val properties: GwpProperties,
) {

    @PostMapping("/{id}/status")
    open fun updateStatus(
        @PathVariable id: UUID,
        @RequestHeader(value = "X-GWP-Callback-Secret", required = false) secret: String?,
        @Valid @RequestBody req: StatusCallbackRequest,
    ): ResponseEntity<JobResponse> {
        if (!constantTimeEquals(properties.callback.sharedSecret, secret)) {
            return ResponseEntity.status(401).build()
        }
        val job = jobLifecycleService.updateStatusFromCallback(
            id, req.status, req.resultUri, req.errorMessage,
        )
        return ResponseEntity.ok(JobResponse.from(job))
    }

    companion object {

        /** Timing-attack (응답 시간 차이로 비밀값을 추측하는 공격) 안전 비교. [String.equals]
         *  는 첫 차이 바이트에서 즉시 false 를 반환 (short-circuit) 해서 비교 시간이 일치 길이
         *  에 따라 달라지므로 사용 불가. */
        private fun constantTimeEquals(expected: String?, actual: String?): Boolean {
            if (expected == null || actual == null || expected.isBlank()) return false
            val e = expected.toByteArray(StandardCharsets.UTF_8)
            val a = actual.toByteArray(StandardCharsets.UTF_8)
            return MessageDigest.isEqual(e, a)
        }
    }
}
