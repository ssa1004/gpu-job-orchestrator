package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.leader.LeaderElector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Preemption (높은 우선순위 잡이 들어오면 낮은 잡의 GPU 를 강제 회수) 정기 트리거 —
 * 매 분 한 번씩 {@link PreemptionService#runOnce} 호출.
 *
 * <p><b>왜 1분 주기</b>: 너무 자주 (10초) 돌면 매 tick 의 SELECT (active jobs 전체) 가
 * DB 부담. 너무 가끔 (10분) 돌면 HIGH 잡이 GPU 풀릴 때까지 오래 대기 → SLA 침해.
 * 1분이면 사용자 입장에서 거의 즉시 (victim Pod graceful shutdown 30초 + 다음 tick =
 * 약 1.5분 후 preemptor 시작).</p>
 *
 * <h3>왜 ShedLock 과 OptimisticLock 둘 다 쓰나 — 보호하는 게 다르다</h3>
 * <ul>
 *   <li><b>OptimisticLock</b> (도메인 레벨, {@code Job.@Version}): *데이터 정확성*.
 *       두 트랜잭션이 같은 victim row 를 동시에 markPreempted 해도 DB UPDATE 의 version
 *       조건에 걸려 한쪽만 commit, 다른 한쪽은 OptimisticLockException 으로 rollback.
 *       이게 없으면 같은 잡을 두 번 종료 처리 / 이중 cost record 등 데이터 손상.</li>
 *   <li><b>ShedLock</b> (스케줄러 레벨, {@code @SchedulerLock}): *비효율 방지*.
 *       두 인스턴스가 같은 시각에 runOnce 를 돌리면 결국 OptimisticLock 이 한쪽을
 *       막아주긴 하지만, 그 전에 같은 SELECT / K8s API cancel 호출이 두 번씩 나간다 →
 *       broker 부하 + 무용한 retry. 한 번에 한 인스턴스만 메서드 진입을 보장해 그 낭비를
 *       원천 차단.</li>
 * </ul>
 *
 * <p>요약: OptimisticLock 이 *안전선*, ShedLock 이 *효율선*. 둘이 직교한다.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PreemptionScheduler {

    private final PreemptionService preemptionService;
    /**
     * 다중 인스턴스에서 비-리더는 매 tick 즉시 return. K8s Lease 모드에서는 Pod 한 개만
     * 활성. ShedLock 모드에서는 항상 true → @SchedulerLock 이 직렬화.
     */
    private final LeaderElector leaderElector;

    // lockAtMostFor 는 노드가 락을 못 풀고 죽었을 때의 안전망 — 이 시간이 지나야 다른
    // 인스턴스가 takeover 한다. runOnce 는 active jobs SELECT + victim cancel 한 패스라
    // 1분이면 정상 실행 + 여유. OutboxRelay / DependencyScanScheduler 와 같은 PT1M 으로
    // 통일 (예전 PT5M 은 leader 가 죽었을 때 preemption 재개가 불필요하게 늦음).
    @Scheduled(fixedDelayString = "${gwp.preemption.interval-ms:60000}")
    @SchedulerLock(name = "preemption-scheduler", lockAtMostFor = "PT1M", lockAtLeastFor = "PT10S")
    public void runPeriodic() {
        if (!leaderElector.isLeader()) return;
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
