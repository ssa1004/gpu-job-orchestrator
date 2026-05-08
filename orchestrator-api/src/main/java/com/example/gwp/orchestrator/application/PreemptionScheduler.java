package com.example.gwp.orchestrator.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Preemption (높은 우선순위 잡이 들어오면 낮은 잡의 GPU 를 강제 회수) 정기 트리거 —
 * 매 분 한 번씩 {@link PreemptionService#runOnce} 호출.
 *
 * <p><b>왜 1분</b>: 너무 자주 (10초) 돌면 매번 큰 SELECT (active jobs 전체) → DB 부담.
 * 너무 가끔 (10분) 돌면 HIGH 우선순위 잡이 들어와도 GPU 가 풀릴 때까지 너무 오래 대기.
 * 1분이면 사용자 입장에서 거의 즉시 (Pod graceful shutdown 30초 + 1분 = 약 1.5분 후 시작).</p>
 *
 * <p><b>multi-instance 동시 실행 방지</b>: ShedLock (DB 행 락으로 한 번에 한 인스턴스만
 * 스케줄러를 돌리도록 보장하는 라이브러리) 적용. {@code @SchedulerLock} 으로 같은 시각에
 * 한 인스턴스만 메서드를 실행하므로 같은 victim 을 두 번 평가 / K8s API 호출하는 낭비를
 * 막는다. 도메인의 {@code Job.markPreempted} 가 OptimisticLock 으로 race 보호하니 안전성
 * 자체는 ShedLock 없이도 보장되지만 효율을 위해 lock.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PreemptionScheduler {

    private final PreemptionService preemptionService;

    @Scheduled(fixedDelayString = "${gwp.preemption.interval-ms:60000}")
    @SchedulerLock(name = "preemption-scheduler", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void runPeriodic() {
        try {
            int preempted = preemptionService.runOnce();
            if (preempted > 0) {
                log.info("preemption tick — {} victim(s) preempted", preempted);
            }
        } catch (RuntimeException ex) {
            // 다음 tick 에서 재시도. 한 번의 실패가 scheduler 자체를 멈추면 안 됨.
            log.error("preemption tick failed", ex);
        }
    }
}
