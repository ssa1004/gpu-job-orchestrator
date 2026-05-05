package com.example.gwp.orchestrator.api;

import com.example.gwp.orchestrator.api.dto.JobResponse;
import com.example.gwp.orchestrator.api.dto.StatusCallbackRequest;
import com.example.gwp.orchestrator.config.properties.GwpProperties;
import com.example.gwp.orchestrator.application.JobLifecycleService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@RestController
@RequestMapping("/internal/jobs")
@RequiredArgsConstructor
@Hidden
public class InternalCallbackController {

    private final JobLifecycleService jobLifecycleService;
    private final GwpProperties properties;

    @PostMapping("/{id}/status")
    public ResponseEntity<JobResponse> updateStatus(
            @PathVariable UUID id,
            @RequestHeader(value = "X-GWP-Callback-Secret", required = false) String secret,
            @Valid @RequestBody StatusCallbackRequest req
    ) {
        if (!constantTimeEquals(properties.callback().sharedSecret(), secret)) {
            return ResponseEntity.status(401).build();
        }
        var job = jobLifecycleService.updateStatusFromCallback(
                id, req.status(), req.resultUri(), req.errorMessage());
        return ResponseEntity.ok(JobResponse.from(job));
    }

    /** Timing-attack 안전 비교. {@link String#equals} 는 첫 차이 바이트에서 short-circuit 하므로 사용 불가. */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.isBlank()) return false;
        byte[] e = expected.getBytes(StandardCharsets.UTF_8);
        byte[] a = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(e, a);
    }
}
