package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.domain.*;

import com.example.gwp.orchestrator.adapter.kubernetes.JobDispatcher;
import com.example.gwp.orchestrator.observability.JobMetrics;
import com.example.gwp.orchestrator.outbox.JobEvent;
import com.example.gwp.orchestrator.outbox.OutboxWriter;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Job 제출 책임 — 쿼터 검사 → DB 저장 → K8s 디스패치 → Outbox 발행 → 메트릭 기록.
 *
 * <p>한 트랜잭션 안에서 DB INSERT 와 Outbox INSERT 가 같이 commit 되어 atomicity 보장.
 * Kafka publish 는 OutboxRelay 가 별도 트랜잭션으로 처리.</p>
 *
 * <p>dispatch 실패는 catch 하여 Job 을 FAILED 로 기록하고 client 에게는 정상 응답
 * (job ID 발급 + status FAILED) — 그래야 client 가 재시도 시 새 ID 가 아닌 같은 ID 로 추적 가능.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobSubmissionService {

    private final JobRepository jobRepository;
    private final JobDependencyRepository jobDependencyRepository;
    private final JobDispatcher jobDispatcher;
    private final JobMetrics jobMetrics;
    private final Tracer tracer;
    private final QuotaService quotaService;
    private final OutboxWriter outboxWriter;
    private final CostAttributionService costAttribution;
    private final Clock clock;

    /** Parent 없는 일반 잡 — 즉시 dispatch path. */
    @Transactional
    public Job submit(JobSpec spec) {
        return submit(spec, Set.of());
    }

    /**
     * Parent 의존성을 갖는 잡 제출. parent 가 비어 있으면 일반 잡과 동일.
     *
     * <p><b>흐름</b>:
     * <ol>
     *   <li>쿼터 검사 (parent 와 무관 — 잡 자체의 자원 한도)</li>
     *   <li>parent 들이 모두 *존재* 하는지 + cycle 검사 (영속화 전 거절)</li>
     *   <li>parent 가 비어 있으면 일반 dispatch / 있으면 WAITING_DEPS 로 저장 + edge 영속</li>
     *   <li>이미 모든 parent 가 SUCCEEDED 면 즉시 promote (race 시 scheduler 가 보강)</li>
     * </ol>
     */
    @Transactional
    public Job submit(JobSpec spec, Set<UUID> parentJobIds) {
        quotaService.enforceForSubmission(spec.owner(), spec.gpuCount());
        String traceId = currentTraceId();

        if (parentJobIds == null || parentJobIds.isEmpty()) {
            // 일반 잡 — 기존 path 그대로
            Job job = Job.submit(spec, traceId, clock);
            jobRepository.save(job);
            jobMetrics.recordSubmitted();
            log.info("job submitted id={} owner={} image={} gpu={}",
                    job.getId(), spec.owner(), spec.image(), spec.gpuCount());
            dispatchOrFail(job);
            Job persisted = jobRepository.save(job);
            publishSubmittedEvent(persisted, spec, traceId);
            return persisted;
        }

        // 1. parent 들 모두 존재 검증
        for (UUID parentId : parentJobIds) {
            if (!jobRepository.existsById(parentId)) {
                throw new JobNotFoundException(parentId);
            }
        }

        // 2. cycle 검사 — 새 잡이 추가되면서 사이클 만들지 않는지 (기존 그래프 + 새 edges).
        //    아직 영속화 전이라 placeholder UUID 로 검사 — INSERT 는 cycle-free 확인 후 진행.
        UUID placeholderId = UUID.randomUUID();
        validateNoCycle(placeholderId, parentJobIds);

        // 3. 잡 + edges 저장 (WAITING_DEPS) — 도메인 factory 사용 (id 는 도메인이 발급)
        Job job = Job.submit(spec, traceId, true, clock);
        UUID newJobId = job.getId();
        jobRepository.save(job);
        Instant now = clock.instant();
        for (UUID parentId : parentJobIds) {
            jobDependencyRepository.save(JobDependency.edge(newJobId, parentId, now));
        }
        jobMetrics.recordSubmitted();
        log.info("job submitted with deps id={} owner={} parents={}",
                newJobId, spec.owner(), parentJobIds);

        // 4. parent 가 *이미 모두 SUCCEEDED* 인 corner case — 즉시 promote.
        //    아니면 그냥 WAITING_DEPS 로 두고 scheduler / parent terminal hook 이 처리.
        if (allParentsAlreadySucceeded(parentJobIds)) {
            job.markReadyToQueue(clock);
            jobRepository.save(job);
            log.info("job id={} parents already done — promoted immediately", newJobId);
            // 일반 dispatch path 로 진행
            dispatchOrFail(job);
            Job persisted = jobRepository.save(job);
            publishSubmittedEvent(persisted, spec, traceId);
            return persisted;
        }
        publishSubmittedEvent(job, spec, traceId);
        return job;
    }

    private void validateNoCycle(UUID newJobId, Set<UUID> newParents) {
        // 기존 그래프 + 새 edges 합쳐 child→parents map 구성
        Map<UUID, Set<UUID>> graph = new HashMap<>();
        for (var edge : jobDependencyRepository.findAll()) {
            graph.computeIfAbsent(edge.getChildJobId(), k -> new HashSet<>())
                    .add(edge.getParentJobId());
        }
        graph.put(newJobId, new LinkedHashSet<>(newParents));
        DependencyGraph.detectCycle(graph);
    }

    private boolean allParentsAlreadySucceeded(Set<UUID> parentJobIds) {
        List<Job> parents = jobRepository.findAllById(parentJobIds);
        if (parents.size() != parentJobIds.size()) return false;   // 누락 = 즉시 promote 안 함
        return parents.stream().allMatch(p -> p.getStatus() == JobStatus.SUCCEEDED);
    }

    private void dispatchOrFail(Job job) {
        try {
            String k8sJobName = jobDispatcher.dispatch(job);
            job.markDispatched(k8sJobName, clock);
        } catch (Exception e) {
            log.error("dispatch failed id={} reason={}", job.getId(), e.getMessage(), e);
            job.markFailed("dispatch failed: " + e.getMessage(), clock);
            jobMetrics.recordFailed();
            // dispatch 실패 = RUNNING 한 적 없음 → runtime 0, cost 0.
            // 그래도 record 는 만든다 — *어떤 잡이 dispatch 실패했는지* 운영에서 추적 가능.
            costAttribution.recordCost(job);
        }
    }

    private void publishSubmittedEvent(Job job, JobSpec spec, String traceId) {
        outboxWriter.write(new JobEvent.JobSubmitted(
                job.getId().toString(),
                spec.owner(),
                spec.image(),
                spec.gpuCount(),
                spec.priority().name(),
                job.getStatus().name(),
                traceId != null ? traceId : ""
        ));
    }

    private String currentTraceId() {
        var span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
