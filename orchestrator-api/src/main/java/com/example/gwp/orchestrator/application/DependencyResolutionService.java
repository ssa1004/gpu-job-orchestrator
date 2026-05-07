package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobDependencyRepository;
import com.example.gwp.orchestrator.domain.JobNotFoundException;
import com.example.gwp.orchestrator.domain.JobRepository;
import com.example.gwp.orchestrator.domain.JobStatus;
import com.example.gwp.orchestrator.outbox.JobEvent;
import com.example.gwp.orchestrator.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Dependency (잡 사이의 선후 관계) 해소 — parent 상태 변경 또는 주기적 검사로 child
 * 들의 시작 가능 여부 판단. DAG (Directed Acyclic Graph — 방향성 있고 순환 없는 그래프)
 * 워크플로우.
 *
 * <p><b>두 가지 trigger</b>:
 * <ol>
 *   <li><b>{@link #onParentTerminal(UUID)}</b> — parent 가 SUCCEEDED / FAILED / CANCELLED
 *       로 전이된 직후 호출 (lifecycle service 가 같은 트랜잭션에서 직접 호출). 즉시
 *       cascade (parent 결과를 child 에게 자동 적용).</li>
 *   <li><b>{@link #scanWaitingJobs()}</b> — scheduler 가 매 분 호출. lifecycle 이벤트가
 *       유실되거나 job 이 늦게 등록된 경우 보강 (idempotent — 이미 처리된 child 는
 *       no-op, 즉 아무 것도 안 함).</li>
 * </ol>
 *
 * <p><b>Cascade 정책</b>:
 * <ul>
 *   <li>parent SUCCEEDED + child 의 *모든* parent SUCCEEDED → child WAITING_DEPS → QUEUED</li>
 *   <li>parent FAILED / CANCELLED → child *자동 CANCELLED* (cascade-cancel)</li>
 *   <li>parent PREEMPTED → child 는 그대로 WAITING — preempt (양보 당함) 된 잡은 사용자
 *       또는 스케줄러가 재투입할 수 있어 child 도 그 결과를 기다림. (반대 정책도 가능 —
 *       후속 ADR 참고)</li>
 * </ul>
 *
 * <p><b>왜 이벤트 listener 가 아닌 명시 호출</b>: lifecycle service 의 트랜잭션 안에서
 * 같이 commit 되어야 함. Spring ApplicationEvent + `@TransactionalEventListener` 는
 * 트랜잭션 commit 이후 다른 트랜잭션으로 처리 → cascade 가 별도 트랜잭션이 되어 race
 * window (race 발생 가능 시간 창) 생김. 명시 호출이 트랜잭션 경계를 분명히 함.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DependencyResolutionService {

    private final JobRepository jobs;
    private final JobDependencyRepository dependencies;
    private final OutboxWriter outboxWriter;
    private final CostAttributionService costAttribution;
    private final Clock clock;

    /**
     * Parent 가 종착 상태에 도달했을 때 호출. 영향받는 child 들의 promote / cascade-cancel 처리.
     */
    @Transactional
    public void onParentTerminal(UUID parentJobId) {
        Job parent = jobs.findById(parentJobId).orElseThrow(() -> new JobNotFoundException(parentJobId));
        if (!parent.getStatus().isTerminal()) {
            log.warn("onParentTerminal called for non-terminal parent={} status={}",
                    parentJobId, parent.getStatus());
            return;
        }
        var edges = dependencies.findByParentJobId(parentJobId);
        for (var edge : edges) {
            tryResolveChild(edge.getChildJobId(), parent.getStatus());
        }
    }

    /**
     * 주기 스캔 — WAITING_DEPS 인 child 들을 모두 검사.
     * 이벤트 유실 / 잡이 parent 보다 늦게 등록된 경우 등 corner case 보강.
     */
    @Transactional
    public int scanWaitingJobs() {
        // 단순 구현 — 모든 WAITING_DEPS child 를 조회하고 각각 검사.
        // 트래픽 늘면 페이징 / chunk 도입.
        List<Job> waiting = jobs.findAll().stream()
                .filter(j -> j.getStatus() == JobStatus.WAITING_DEPS)
                .toList();
        int promoted = 0;
        int cancelled = 0;
        for (Job child : waiting) {
            var result = tryResolveChild(child.getId(), null);
            if (result == ResolutionOutcome.PROMOTED) promoted++;
            if (result == ResolutionOutcome.CASCADED_CANCEL) cancelled++;
        }
        if (promoted > 0 || cancelled > 0) {
            log.info("dependency scan finished waiting={} promoted={} cascaded={}",
                    waiting.size(), promoted, cancelled);
        }
        return promoted + cancelled;
    }

    private ResolutionOutcome tryResolveChild(UUID childJobId, JobStatus knownParentStatus) {
        Job child = jobs.findById(childJobId).orElse(null);
        if (child == null) {
            log.warn("child job vanished id={}", childJobId);
            return ResolutionOutcome.NOOP;
        }
        if (child.getStatus() != JobStatus.WAITING_DEPS) {
            // 이미 promote / cancel 됨 — idempotent
            return ResolutionOutcome.NOOP;
        }

        var edges = dependencies.findByChildJobId(childJobId);
        if (edges.isEmpty()) {
            // 의존 없는데 WAITING_DEPS 라는 건 비정상 상태 — 그냥 promote
            child.markReadyToQueue(clock);
            jobs.save(child);
            log.warn("child={} had no parents but was WAITING — promoted", childJobId);
            return ResolutionOutcome.PROMOTED;
        }

        boolean anyParentBlocked = false;
        boolean anyParentFailed = false;
        for (var edge : edges) {
            var parent = jobs.findById(edge.getParentJobId()).orElse(null);
            if (parent == null) {
                // parent 가 사라졌으면 — 영영 만족 못함. cascade cancel.
                anyParentFailed = true;
                break;
            }
            JobStatus s = parent.getStatus();
            if (s == JobStatus.FAILED || s == JobStatus.CANCELLED) {
                anyParentFailed = true;
                break;
            }
            if (s != JobStatus.SUCCEEDED) {
                // RUNNING / DISPATCHING / QUEUED / WAITING_DEPS / PREEMPTED — 아직 대기
                anyParentBlocked = true;
            }
        }

        if (anyParentFailed) {
            child.markCancelled(clock);
            Job persisted = jobs.save(child);
            outboxWriter.write(new JobEvent.JobCompleted(
                    child.getId().toString(),
                    JobStatus.CANCELLED.name(),
                    null,
                    "cascade-cancel from failed/cancelled parent",
                    clock.instant().toString()
            ));
            // Cascade-cancel 도 종착 — runtime 0 / cost 0 이지만 record 는 만든다.
            // 운영 dashboard 에서 "이 잡은 왜 안 돌고 cancelled 됐나" 추적 가능.
            costAttribution.recordCost(persisted);
            log.info("dep cascade-cancel child={} due to parent failure", childJobId);
            return ResolutionOutcome.CASCADED_CANCEL;
        }
        if (anyParentBlocked) {
            return ResolutionOutcome.NOOP;
        }
        // 모두 SUCCEEDED → promote
        child.markReadyToQueue(clock);
        jobs.save(child);
        log.info("dep promoted child={} (all parents succeeded)", childJobId);
        return ResolutionOutcome.PROMOTED;
    }

    private enum ResolutionOutcome { PROMOTED, CASCADED_CANCEL, NOOP }
}
