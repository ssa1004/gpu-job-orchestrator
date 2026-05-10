package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.adapter.kubernetes.JobDispatcher;
import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobPriority;
import com.example.gwp.orchestrator.domain.JobRepository;
import com.example.gwp.orchestrator.domain.JobSpec;
import com.example.gwp.orchestrator.domain.JobStatus;
import com.example.gwp.orchestrator.domain.PreemptionHistoryRepository;
import com.example.gwp.orchestrator.domain.PreemptionPolicy;
import com.example.gwp.orchestrator.outbox.JobEvent;
import com.example.gwp.orchestrator.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PreemptionService 통합 동작 검증 — evaluator 결정을 받아 victim 종료 + history 기록 +
 * cost 기록 + 이벤트 발행이 모두 한 트랜잭션에서 일어남.
 *
 * <p>Evaluator 단위 검증은 {@link com.example.gwp.orchestrator.domain.PreemptionEvaluatorTest}.
 * 여기서는 Service 가 결과를 *어떻게 반영* 하는지 검증.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreemptionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock JobRepository jobs;
    @Mock PreemptionHistoryRepository history;
    @Mock JobDispatcher dispatcher;
    @Mock OutboxWriter outbox;
    @Mock CostAttributionService costAttribution;

    PreemptionService service;

    @BeforeEach
    void setUp() {
        service = new PreemptionService(jobs, history, dispatcher, outbox, costAttribution, CLOCK);
        when(jobs.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        // 기본값 — victim 의 in-memory 상태 (RUNNING) 를 그대로 DB 가 답하도록. 개별 테스트가
        // race 시뮬레이션을 위해 override 가능 (LENIENT 라 unmatched stub 은 무시).
        when(jobs.findCurrentStatusById(any())).thenAnswer(inv -> {
            java.util.UUID id = inv.getArgument(0);
            return java.util.Optional.of(JobStatus.RUNNING);
        });
    }

    private static Job queued(JobPriority pri, int gpu) {
        return Job.submit(new JobSpec("requestor", "s3://b/k", "img:1", gpu, pri, PreemptionPolicy.PREEMPTABLE),
                "trace", CLOCK);
    }

    private static Job running(JobPriority pri, int gpu, PreemptionPolicy pol) {
        Job j = Job.submit(new JobSpec("victim-owner", "s3://b/k", "img:1", gpu, pri, pol), "trace", CLOCK);
        // K8s job name 은 잡 id 로 유일 — 실제 dispatcher 와 같은 형식. 같은 이름을 두 victim 에
        // 공유하면 mock dispatcher.cancel 로 한쪽만 throw 시키는 시나리오가 모호해짐.
        j.markDispatched("k8s-" + j.getId(), CLOCK);
        j.markRunning(CLOCK);
        return j;
    }

    /** 전형적 시나리오 — HIGH preemptor 가 LOW victim 1개 죽임. */
    @Test
    void runOnce_preemptsVictim_recordsCostAndHistory() {
        Job preemptor = queued(JobPriority.HIGH, 1);
        Job victim = running(JobPriority.LOW, 1, PreemptionPolicy.PREEMPTABLE);

        when(jobs.findQueuedForScheduling(any(Pageable.class))).thenReturn(List.of(preemptor));
        when(jobs.findActivePreemptables(eq(JobStatus.activeStatuses()))).thenReturn(List.of(victim));

        int killed = service.runOnce();

        assertThat(killed).isEqualTo(1);
        assertThat(victim.getStatus()).isEqualTo(JobStatus.PREEMPTED);
        verify(dispatcher).cancel(victim.getK8sJobName());           // K8s Pod 종료 요청
        verify(history).save(any());                                 // 영속 history 기록
        verify(costAttribution).recordCost(victim);                  // 그때까지 GPU-시간 청구
        verify(outbox).write(any(JobEvent.JobPreempted.class));      // 다운스트림 이벤트
    }

    /**
     * <b>핵심 invariant</b>: NEVER 정책 잡은 절대 preempt 안 됨.
     * Evaluator 단계에서 후보에서 제외 → service 도 죽이지 않음.
     */
    @Test
    void runOnce_neverPolicy_isProtectedEvenIfLowerPriority() {
        Job preemptor = queued(JobPriority.HIGH, 1);
        Job neverVictim = running(JobPriority.LOW, 1, PreemptionPolicy.NEVER);

        when(jobs.findQueuedForScheduling(any(Pageable.class))).thenReturn(List.of(preemptor));
        when(jobs.findActivePreemptables(eq(JobStatus.activeStatuses()))).thenReturn(List.of(neverVictim));

        int killed = service.runOnce();

        assertThat(killed).isZero();
        assertThat(neverVictim.getStatus()).isEqualTo(JobStatus.RUNNING);   // 그대로
        verify(dispatcher, never()).cancel(any());
        verify(costAttribution, never()).recordCost(any());
    }

    /**
     * <b>핵심 invariant</b>: 같은 priority 잡은 절대 서로 preempt 안 됨.
     * NORMAL 잡은 NORMAL 잡을 죽이지 못함.
     */
    @Test
    void runOnce_samePriority_doesNotPreempt() {
        Job preemptor = queued(JobPriority.NORMAL, 1);
        Job sameVictim = running(JobPriority.NORMAL, 1, PreemptionPolicy.PREEMPTABLE);

        when(jobs.findQueuedForScheduling(any(Pageable.class))).thenReturn(List.of(preemptor));
        when(jobs.findActivePreemptables(eq(JobStatus.activeStatuses()))).thenReturn(List.of(sameVictim));

        int killed = service.runOnce();

        assertThat(killed).isZero();
        assertThat(sameVictim.getStatus()).isEqualTo(JobStatus.RUNNING);
        verify(dispatcher, never()).cancel(any());
    }

    /** 대기 잡이 없으면 평가 자체 안 함. */
    @Test
    void runOnce_emptyQueue_returnsZero() {
        when(jobs.findQueuedForScheduling(any(Pageable.class))).thenReturn(List.of());

        int killed = service.runOnce();

        assertThat(killed).isZero();
        verify(jobs, never()).findActivePreemptables(any());
    }

    /** ACTIVE 잡이 없으면 죽일 후보가 없으니 즉시 반환. */
    @Test
    void runOnce_noActivePreemptables_returnsZero() {
        Job preemptor = queued(JobPriority.HIGH, 1);
        when(jobs.findQueuedForScheduling(any(Pageable.class))).thenReturn(List.of(preemptor));
        when(jobs.findActivePreemptables(any())).thenReturn(List.of());

        int killed = service.runOnce();

        assertThat(killed).isZero();
    }

    /**
     * 한 victim 처리에서 예외가 나도 다른 victim 처리는 계속 — 부분 실패 허용.
     * (OptimisticLock 충돌 / K8s API 일시 오류 시뮬레이션)
     */
    @Test
    void runOnce_oneVictimFailsToCancel_othersStillProcessed() {
        Job preemptor = queued(JobPriority.HIGH, 4);
        Job v1 = running(JobPriority.LOW, 2, PreemptionPolicy.PREEMPTABLE);
        Job v2 = running(JobPriority.LOW, 2, PreemptionPolicy.PREEMPTABLE);

        when(jobs.findQueuedForScheduling(any(Pageable.class))).thenReturn(List.of(preemptor));
        when(jobs.findActivePreemptables(any())).thenReturn(List.of(v1, v2));
        // v1 의 K8s cancel 만 실패하도록 — v2 는 정상 처리
        org.mockito.Mockito.doThrow(new RuntimeException("k8s timeout"))
                .when(dispatcher).cancel(v1.getK8sJobName());

        int killed = service.runOnce();

        assertThat(killed).isEqualTo(1);                        // v2 만 성공
        verify(dispatcher).cancel(v2.getK8sJobName());
        verify(costAttribution, times(1)).recordCost(any());    // v2 만 cost 기록
    }

    /**
     * 같은 victim 이 두 preemptor 에게 죽임당하지 않도록 — 한 tick 안에서 victim 목록은
     * 처리 후 제거되어야 함.
     */
    @Test
    void runOnce_victimNotDoubleKilled_acrossPreemptors() {
        Job p1 = queued(JobPriority.HIGH, 1);
        Job p2 = queued(JobPriority.HIGH, 1);
        Job onlyVictim = running(JobPriority.LOW, 1, PreemptionPolicy.PREEMPTABLE);

        when(jobs.findQueuedForScheduling(any(Pageable.class))).thenReturn(List.of(p1, p2));
        when(jobs.findActivePreemptables(any())).thenReturn(List.of(onlyVictim));

        int killed = service.runOnce();

        assertThat(killed).isEqualTo(1);                         // 한 번만 죽음
        verify(dispatcher, times(1)).cancel(any());
        verify(costAttribution, times(1)).recordCost(any());
    }

    /**
     * Race: findActivePreemptables 시점엔 RUNNING 이었으나 그 사이 worker callback 으로
     * SUCCEEDED 종착한 victim. 새 status re-check 가 short-circuit 해야 한다 — K8s cancel
     * / markPreempted / cost 기록 모두 안 일어나야.
     *
     * <p>이 short-circuit 이 없으면: K8s API 에 의미 없는 delete 요청 (이미 끝난 Pod) +
     * markPreempted 가 IllegalJobTransitionException → catch 로 빠지긴 하지만 broker 부하
     * 와 무용한 retry round-trip 발생.</p>
     */
    @Test
    void runOnce_victimAlreadyTerminal_skipsCancelAndPreempt() {
        Job preemptor = queued(JobPriority.HIGH, 1);
        Job victim = running(JobPriority.LOW, 1, PreemptionPolicy.PREEMPTABLE);

        when(jobs.findQueuedForScheduling(any(Pageable.class))).thenReturn(List.of(preemptor));
        when(jobs.findActivePreemptables(any())).thenReturn(List.of(victim));
        // DB 는 이미 SUCCEEDED 라고 답함 — snapshot 과 mismatch
        when(jobs.findCurrentStatusById(victim.getId()))
                .thenReturn(java.util.Optional.of(JobStatus.SUCCEEDED));

        int killed = service.runOnce();

        assertThat(killed).isZero();
        verify(dispatcher, never()).cancel(any());
        verify(costAttribution, never()).recordCost(any());
        verify(outbox, never()).write(any());
        verify(history, never()).save(any());
    }

    /**
     * victim 이 그 사이 사라진 corner — findCurrentStatusById 가 empty. 안전 측 default 로
     * skip (한 tick 누락은 허용, 다음 tick 에서 보강).
     */
    @Test
    void runOnce_victimVanished_skipsPreempt() {
        Job preemptor = queued(JobPriority.HIGH, 1);
        Job victim = running(JobPriority.LOW, 1, PreemptionPolicy.PREEMPTABLE);

        when(jobs.findQueuedForScheduling(any(Pageable.class))).thenReturn(List.of(preemptor));
        when(jobs.findActivePreemptables(any())).thenReturn(List.of(victim));
        when(jobs.findCurrentStatusById(victim.getId()))
                .thenReturn(java.util.Optional.empty());

        int killed = service.runOnce();

        assertThat(killed).isZero();
        verify(dispatcher, never()).cancel(any());
    }
}
