package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobDependency;
import com.example.gwp.orchestrator.domain.JobDependencyRepository;
import com.example.gwp.orchestrator.domain.JobRepository;
import com.example.gwp.orchestrator.domain.JobSpec;
import com.example.gwp.orchestrator.domain.JobStatus;
import com.example.gwp.orchestrator.outbox.JobEvent;
import com.example.gwp.orchestrator.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dependency resolution 의 cascade 정책 + cost 기록 검증.
 *
 * <ul>
 *   <li>parent SUCCEEDED + 모든 parent SUCCEEDED → child WAITING_DEPS → QUEUED (cost 안 만듦)</li>
 *   <li>parent FAILED / CANCELLED → child cascade-cancel + cost record (runtime 0)</li>
 *   <li>parent PREEMPTED → child 그대로 WAITING (preempt 후 재투입 가능)</li>
 *   <li>이미 처리된 child 재호출 — idempotent</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DependencyResolutionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock JobRepository jobs;
    @Mock JobDependencyRepository dependencies;
    @Mock OutboxWriter outbox;
    @Mock CostAttributionService costAttribution;

    DependencyResolutionService service;

    @BeforeEach
    void setUp() {
        service = new DependencyResolutionService(jobs, dependencies, outbox, costAttribution, CLOCK);
        when(jobs.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** parent batch lookup 모킹 — findAllById 가 주어진 parent 들 중 ids 와 일치하는 것을 반환. */
    @SuppressWarnings("unchecked")
    private void stubBatchLookup(Job... parents) {
        when(jobs.findAllById(anyCollection())).thenAnswer(inv -> {
            Iterable<UUID> requested = inv.getArgument(0);
            Set<UUID> idSet = new java.util.HashSet<>();
            requested.forEach(idSet::add);
            return java.util.Arrays.stream(parents)
                    .filter(p -> idSet.contains(p.getId()))
                    .toList();
        });
    }

    private static Job waitingChild() {
        return Job.submit(new JobSpec("alice", "s3://b/i", "img:1", 1), null, true, CLOCK);
    }

    private static Job parentIn(JobStatus terminal) {
        Job p = Job.submit(new JobSpec("alice", "s3://b/p", "img:1", 1), null, CLOCK);
        switch (terminal) {
            case SUCCEEDED -> {
                p.markDispatched("k8s-p", CLOCK);
                p.markRunning(CLOCK);
                p.markSucceeded("s3://b/po", CLOCK);
            }
            case FAILED -> p.markFailed("oom", CLOCK);
            case CANCELLED -> p.markCancelled(CLOCK);
            default -> throw new IllegalArgumentException("only terminal allowed: " + terminal);
        }
        return p;
    }

    @Test
    void onParentTerminal_succeededAndAllParentsDone_promotesChild() {
        Job parent = parentIn(JobStatus.SUCCEEDED);
        Job child = waitingChild();
        when(jobs.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(jobs.findById(child.getId())).thenReturn(Optional.of(child));
        when(dependencies.findByParentJobId(parent.getId()))
                .thenReturn(List.of(JobDependency.edge(child.getId(), parent.getId(), CLOCK.instant())));
        when(dependencies.findByChildJobId(child.getId()))
                .thenReturn(List.of(JobDependency.edge(child.getId(), parent.getId(), CLOCK.instant())));
        stubBatchLookup(parent);

        service.onParentTerminal(parent.getId());

        assertThat(child.getStatus()).isEqualTo(JobStatus.QUEUED);
        // promote 는 cost 안 만들고 — 종착 아님
        verify(costAttribution, never()).recordCost(any());
    }

    /**
     * <b>핵심 cascade-cancel + cost 기록</b>: parent FAILED 면 child 도 자동 CANCELLED 되고,
     * runtime 0 cost record 가 만들어져야 한다. 운영 dashboard 에서 추적 가능하다.
     */
    @Test
    void onParentTerminal_parentFailed_cascadeCancelsChildAndRecordsCost() {
        Job parent = parentIn(JobStatus.FAILED);
        Job child = waitingChild();
        when(jobs.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(jobs.findById(child.getId())).thenReturn(Optional.of(child));
        when(dependencies.findByParentJobId(parent.getId()))
                .thenReturn(List.of(JobDependency.edge(child.getId(), parent.getId(), CLOCK.instant())));
        when(dependencies.findByChildJobId(child.getId()))
                .thenReturn(List.of(JobDependency.edge(child.getId(), parent.getId(), CLOCK.instant())));
        stubBatchLookup(parent);

        service.onParentTerminal(parent.getId());

        assertThat(child.getStatus()).isEqualTo(JobStatus.CANCELLED);
        verify(costAttribution).recordCost(child);                          // cascade-cancel cost 기록
        verify(outbox).write(any(JobEvent.JobCompleted.class));
    }

    @Test
    void onParentTerminal_parentCancelled_cascadeCancelsChildAndRecordsCost() {
        Job parent = parentIn(JobStatus.CANCELLED);
        Job child = waitingChild();
        when(jobs.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(jobs.findById(child.getId())).thenReturn(Optional.of(child));
        when(dependencies.findByParentJobId(parent.getId()))
                .thenReturn(List.of(JobDependency.edge(child.getId(), parent.getId(), CLOCK.instant())));
        when(dependencies.findByChildJobId(child.getId()))
                .thenReturn(List.of(JobDependency.edge(child.getId(), parent.getId(), CLOCK.instant())));
        stubBatchLookup(parent);

        service.onParentTerminal(parent.getId());

        assertThat(child.getStatus()).isEqualTo(JobStatus.CANCELLED);
        verify(costAttribution).recordCost(child);
    }

    /** 이미 종착된 child 는 재처리 안 함 (idempotent) — cost / 이벤트 추가로 안 만들어짐. */
    @Test
    void onParentTerminal_childAlreadyTerminal_idempotent() {
        Job parent = parentIn(JobStatus.SUCCEEDED);
        Job child = waitingChild();
        child.markCancelled(CLOCK);                                        // 이미 cancel 됨
        when(jobs.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(jobs.findById(child.getId())).thenReturn(Optional.of(child));
        when(dependencies.findByParentJobId(parent.getId()))
                .thenReturn(List.of(JobDependency.edge(child.getId(), parent.getId(), CLOCK.instant())));

        service.onParentTerminal(parent.getId());

        // status 그대로, cost 추가 호출 없음
        assertThat(child.getStatus()).isEqualTo(JobStatus.CANCELLED);
        verify(jobs, never()).save(child);
        verify(costAttribution, never()).recordCost(any());
    }

    /**
     * parent 중 하나라도 아직 active 면 promote 안 함 — child 는 그대로 WAITING_DEPS.
     */
    @Test
    void onParentTerminal_anotherParentStillActive_keepsChildWaiting() {
        Job parent1 = parentIn(JobStatus.SUCCEEDED);
        Job parent2 = Job.submit(new JobSpec("alice", "s3://b/p2", "img:1", 1), null, CLOCK);
        // parent2 는 RUNNING 으로 두기
        parent2.markDispatched("k8s-p2", CLOCK);
        parent2.markRunning(CLOCK);
        Job child = waitingChild();

        when(jobs.findById(parent1.getId())).thenReturn(Optional.of(parent1));
        when(jobs.findById(parent2.getId())).thenReturn(Optional.of(parent2));
        when(jobs.findById(child.getId())).thenReturn(Optional.of(child));
        when(dependencies.findByParentJobId(parent1.getId()))
                .thenReturn(List.of(JobDependency.edge(child.getId(), parent1.getId(), CLOCK.instant())));
        when(dependencies.findByChildJobId(child.getId())).thenReturn(List.of(
                JobDependency.edge(child.getId(), parent1.getId(), CLOCK.instant()),
                JobDependency.edge(child.getId(), parent2.getId(), CLOCK.instant())
        ));
        stubBatchLookup(parent1, parent2);

        service.onParentTerminal(parent1.getId());

        assertThat(child.getStatus()).isEqualTo(JobStatus.WAITING_DEPS);   // 변화 없음
        verify(costAttribution, never()).recordCost(any());
    }

    /**
     * <b>parent batch 로딩 (N+1 회귀 방지)</b>: parent 가 여러 개여도 한 번의 findAllById 호출
     * 로 끝나야 한다. parent 마다 findById 를 호출하면 N+1 → 큰 fan-in 일 때 promotion latency 가
     * parent 수에 비례해 늘어남.
     */
    @Test
    void tryResolveChild_batchLoadsParentsOnce() {
        Job parent1 = parentIn(JobStatus.SUCCEEDED);
        Job parent2 = parentIn(JobStatus.SUCCEEDED);
        Job parent3 = parentIn(JobStatus.SUCCEEDED);
        Job child = waitingChild();

        when(jobs.findById(parent1.getId())).thenReturn(Optional.of(parent1));
        when(jobs.findById(child.getId())).thenReturn(Optional.of(child));
        when(dependencies.findByParentJobId(parent1.getId()))
                .thenReturn(List.of(JobDependency.edge(child.getId(), parent1.getId(), CLOCK.instant())));
        when(dependencies.findByChildJobId(child.getId())).thenReturn(List.of(
                JobDependency.edge(child.getId(), parent1.getId(), CLOCK.instant()),
                JobDependency.edge(child.getId(), parent2.getId(), CLOCK.instant()),
                JobDependency.edge(child.getId(), parent3.getId(), CLOCK.instant())
        ));
        stubBatchLookup(parent1, parent2, parent3);

        service.onParentTerminal(parent1.getId());

        assertThat(child.getStatus()).isEqualTo(JobStatus.QUEUED);
        verify(jobs).findAllById(anyCollection());                         // batch 호출 정확히 1회
        // child / event-trigger parent 만 findById, 다른 parent 들은 findById 안 됨
        verify(jobs, never()).findById(parent2.getId());
        verify(jobs, never()).findById(parent3.getId());
    }

    /** 비-종착 parent 로 호출되면 경고만 남기고 no-op. */
    @Test
    void onParentTerminal_nonTerminalParent_isNoop() {
        Job parent = Job.submit(new JobSpec("alice", "s3://b/p", "img:1", 1), null, CLOCK);
        parent.markDispatched("k8s-p", CLOCK);
        parent.markRunning(CLOCK);
        when(jobs.findById(parent.getId())).thenReturn(Optional.of(parent));

        service.onParentTerminal(parent.getId());

        verify(dependencies, never()).findByParentJobId(any());
        verify(costAttribution, never()).recordCost(any());
    }
}
