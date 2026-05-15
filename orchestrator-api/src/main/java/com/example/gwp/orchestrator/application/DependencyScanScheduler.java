package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.leader.LeaderElector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
    /** 비-리더 인스턴스는 매 tick 즉시 return. */
    private final LeaderElector leaderElector;

    // lockAtMostFor 는 노드가 락을 못 풀고 죽었을 때의 안전망 — 이 시간이 지나야 다른
    // 인스턴스가 takeover 한다. scan 은 단일 패스 DB 쿼리라 1분이면 정상 실행 + 충분한
    // 여유. OutboxRelay 와 같은 PT1M 으로 통일 (예전 PT5M 은 takeover 가 불필요하게 늦음).
    @Scheduled(fixedDelayString = "${gwp.deps.scan-interval-ms:60000}")
    @SchedulerLock(name = "dependency-scan", lockAtMostFor = "PT1M", lockAtLeastFor = "PT10S")
    public void scan() {
        if (!leaderElector.isLeader()) return;
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
