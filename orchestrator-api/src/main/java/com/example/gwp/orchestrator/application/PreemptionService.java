package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.adapter.kubernetes.JobDispatcher;
import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobRepository;
import com.example.gwp.orchestrator.domain.JobStatus;
import com.example.gwp.orchestrator.domain.PreemptionDecision;
import com.example.gwp.orchestrator.domain.PreemptionEvaluator;
import com.example.gwp.orchestrator.domain.PreemptionHistoryEntry;
import com.example.gwp.orchestrator.domain.PreemptionHistoryRepository;
import com.example.gwp.orchestrator.outbox.JobEvent;
import com.example.gwp.orchestrator.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Preemption 실행 책임 — 평가 (PreemptionEvaluator) + 실제 victim 죽이기 + history 기록.
 *
 * <p><b>흐름</b> (한 트랜잭션):
 * <ol>
 *   <li>QUEUED 중 priority 높은 잡 N개 (preemptor 후보) 픽업</li>
 *   <li>현재 ACTIVE + PREEMPTABLE 잡 목록 로드 (PreemptionEvaluator 의 input)</li>
 *   <li>각 preemptor 별로 evaluate → victim 들 결정</li>
 *   <li>victim 별: K8s 에 cancel 요청 → Job.markPreempted → history 기록 → 이벤트 발행</li>
 *   <li>preemptor 는 *바로 dispatch 하지 않음* — 다음 scheduler tick 에서 일반 dispatch.
 *       그 시점이면 victim 들이 RUNNING → PREEMPTED 로 전이됐고 GPU 가 비어 있음.</li>
 * </ol>
 *
 * <p><b>왜 preemptor 를 즉시 dispatch 하지 않나</b>: K8s Pod 가 종료되는데 시간이 걸림 (graceful
 * shutdown 30초 등). 즉시 dispatch 하면 GPU 가 아직 점유 중이라 새 Pod 가 Pending 으로 들어감.
 * 다음 scheduler tick (1분 후) 에서 시도하면 victim Pod 가 사라지고 GPU 가 비어 정상 시작.
 * 트래픽 늘어 1분 latency 가 문제되면 *예약 (reserved binding)* 패턴 도입 검토 — 후속.</p>
 *
 * <p><b>OptimisticLock</b>: victim 을 markPreempted 할 때 다른 트랜잭션 (callback / 사용자 cancel
 * 등) 이 같은 row 변경 시 OptimisticLockException → 한 victim 실패 시 catch + log,
 * 나머지 victim 처리는 계속.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreemptionService {

    /** 한 tick 에 처리할 preemptor 수. 너무 많으면 트랜잭션 커지고 lock contention. */
    private static final int PREEMPTOR_BATCH = 10;

    private final JobRepository jobs;
    private final PreemptionHistoryRepository history;
    private final JobDispatcher k8sDispatcher;
    private final OutboxWriter outboxWriter;
    private final CostAttributionService costAttribution;
    private final Clock clock;

    /**
     * 한 tick 의 preemption 평가 + 실행.
     *
     * @return 이번 tick 에 PREEMPT 된 victim 총 개수
     */
    @Transactional
    public int runOnce() {
        List<Job> queuedHighPriority = jobs.findQueuedForScheduling(PageRequest.of(0, PREEMPTOR_BATCH));
        if (queuedHighPriority.isEmpty()) return 0;

        List<Job> activePreemptables = jobs.findActivePreemptables(JobStatus.activeStatuses());
        if (activePreemptables.isEmpty()) return 0;   // 죽일 후보가 없으면 평가 의미 없음

        Instant now = clock.instant();
        int totalPreempted = 0;

        for (Job preemptor : queuedHighPriority) {
            PreemptionDecision decision = PreemptionEvaluator.evaluate(preemptor, activePreemptables);
            if (!decision.shouldPreempt()) continue;

            int killedThisRound = preempt(decision, now);
            totalPreempted += killedThisRound;

            // 같은 victim 이 두 preemptor 에게 죽임당하지 않게 후보 목록에서 제거
            activePreemptables = activePreemptables.stream()
                    .filter(j -> !decision.victims().contains(j))
                    .toList();

            log.info("preempted {} victim(s) ({} GPU) for preemptor={} priority={}",
                    decision.victims().size(), decision.totalGpuFreed(),
                    preemptor.getId(), preemptor.getPriority());
        }
        return totalPreempted;
    }

    private int preempt(PreemptionDecision decision, Instant now) {
        Job preemptor = decision.preemptor();
        int killed = 0;
        for (Job victim : decision.victims()) {
            try {
                // 1. K8s 에 Pod 종료 요청 — graceful shutdown 시작
                if (victim.getK8sJobName() != null) {
                    k8sDispatcher.cancel(victim.getK8sJobName());
                }
                // 2. Job 도메인 상태 전이
                String reason = "preempted by job=%s priority=%s"
                        .formatted(preemptor.getId(), preemptor.getPriority());
                victim.markPreempted(preemptor.getId(), reason, clock);
                Job preemptedVictim = jobs.save(victim);

                // 3. History 기록
                history.save(PreemptionHistoryEntry.record(victim, preemptor, reason, now));

                // 4. Cost 박제 — PREEMPT 도 그때까지 사용한 GPU-시간은 청구.
                //    내 잘못 (낮은 priority 로 제출) 이라 회계상 정당, 후속 자동 requeue 시
                //    재시작된 잡은 *별도 record* (다른 jobId).
                costAttribution.recordCost(preemptedVictim);

                // 5. Outbox 이벤트 — 컨슈머가 customer 알림 / 빌링 정산 / 자동 재시도 결정
                outboxWriter.write(new JobEvent.JobPreempted(
                        victim.getId().toString(),
                        victim.getOwner(),
                        preemptor.getId().toString(),
                        preemptor.getPriority().name(),
                        reason,
                        now.toString()
                ));
                killed++;
            } catch (RuntimeException ex) {
                // OptimisticLock / K8s API 일시 장애 — 다른 victim 처리는 계속
                log.warn("preempt victim failed id={}: {}", victim.getId(), ex.getMessage());
            }
        }
        return killed;
    }
}
