package com.example.gwp.orchestrator.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Preemption 정기 트리거 — 매 분 한 번씩 {@link PreemptionService#runOnce} 호출.
 *
 * <p><b>왜 1분</b>: 너무 자주 (10초) 돌면 매번 큰 SELECT (active jobs 전체) → DB 부담.
 * 너무 가끔 (10분) 돌면 HIGH 우선순위 잡이 들어와도 GPU 가 풀릴 때까지 너무 오래 대기.
 * 1분이면 사용자 perception 상 거의 즉시 (Pod graceful shutdown 30초 + 1분 = 약 1.5분 후 시작).</p>
 *
 * <p><b>multi-instance 동시 실행 방지</b>: 본 프로젝트는 ShedLock 미사용 (단일 leader Pod 가정).
 * 여러 인스턴스가 동시에 평가하면 같은 victim 을 두 번 죽이려 할 수 있는데, 도메인의
 * {@code Job.markPreempted} 가 이미 PREEMPTED 면 {@code IllegalJobTransitionException} → 두 번째
 * 시도는 거절. 이중 처리는 catch 로 무해하지만 K8s API 호출은 낭비. 운영에서 더 큰 규모면
 * ShedLock 도입.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PreemptionScheduler {

    private final PreemptionService preemptionService;

    @Scheduled(fixedDelayString = "${gwp.preemption.interval-ms:60000}")
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
