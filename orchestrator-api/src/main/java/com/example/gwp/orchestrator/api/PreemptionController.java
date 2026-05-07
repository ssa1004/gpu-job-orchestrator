package com.example.gwp.orchestrator.api;

import com.example.gwp.orchestrator.api.dto.PreemptionHistoryResponse;
import com.example.gwp.orchestrator.domain.PreemptionHistoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Preemption 이력 조회 — 운영자 / 사용자 모두 사용.
 *
 * <ul>
 *   <li>{@code GET /api/v1/jobs/{id}/preemption-history} — 한 잡이 죽었던 이력 (보통 1건)</li>
 *   <li>{@code GET /api/v1/preemption-history?limit=N} — 최근 발생한 preemption 전체 (운영 모니터링)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "preemption", description = "GPU 우선순위 기반 양보 (preemption) 이력")
class PreemptionController {

    private final PreemptionHistoryRepository history;

    @GetMapping("/jobs/{jobId}/preemption-history")
    @Operation(summary = "한 잡이 다른 잡에게 GPU 를 양보한 이력 (victim 시점)")
    ResponseEntity<PreemptionHistoryResponse> jobHistory(@PathVariable UUID jobId) {
        var entries = history.findByVictimJobIdOrderByPreemptedAtDesc(jobId);
        return ResponseEntity.ok(PreemptionHistoryResponse.from(entries));
    }

    @GetMapping("/preemption-history")
    @Operation(summary = "최근 preemption 이벤트 timeline (운영자용)")
    ResponseEntity<PreemptionHistoryResponse> recent(
            @RequestParam(defaultValue = "100") int limit
    ) {
        var entries = history.findAllByOrderByPreemptedAtDesc(PageRequest.of(0, limit));
        return ResponseEntity.ok(PreemptionHistoryResponse.from(entries));
    }
}
