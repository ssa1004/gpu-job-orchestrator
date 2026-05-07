package com.example.gwp.orchestrator.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기적으로 WAITING_DEPS 잡들을 스캔 — lifecycle event 기반 cascade 가 유실됐거나 race
 * condition (parent 가 child 보다 먼저 SUCCEEDED 되어 cascade 시점에 child 가 아직 등록 전)
 * 에 대한 보강 메커니즘.
 *
 * <p>매 분 호출 — idempotent 라 자주 돌려도 안전. 이미 처리된 child 는 no-op.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DependencyScanScheduler {

    private final DependencyResolutionService resolution;

    @Scheduled(fixedDelayString = "${gwp.deps.scan-interval-ms:60000}")
    public void scan() {
        try {
            int changed = resolution.scanWaitingJobs();
            if (changed > 0) {
                log.info("dependency scan changed={} jobs", changed);
            }
        } catch (RuntimeException ex) {
            // 한 번의 실패가 scheduler 자체를 멈추면 안 됨 — 다음 tick 에서 재시도
            log.error("dependency scan failed", ex);
        }
    }
}
