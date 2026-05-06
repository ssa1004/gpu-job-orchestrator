package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.domain.*;

import com.example.gwp.orchestrator.adapter.kubernetes.JobDispatcher;
import com.example.gwp.orchestrator.observability.JobMetrics;
import com.example.gwp.orchestrator.outbox.JobEvent;
import com.example.gwp.orchestrator.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Job 라이프사이클 변경 책임 — 워커 콜백 + 사용자 취소.
 *
 * <p>{@code @CacheEvict} 가 정상 동작하려면 외부 컴포넌트에서 호출되어야 함 (AOP proxy).
 * 같은 트랜잭션 안에서 Outbox 도 발행 (트랜잭션 안전).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobLifecycleService {

    private final JobRepository jobRepository;
    private final JobDispatcher jobDispatcher;
    private final JobMetrics jobMetrics;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    @CacheEvict(cacheNames = "jobs", key = "#id")
    @Transactional
    public Job updateStatusFromCallback(UUID id, JobStatus newStatus, String resultUri, String errorMessage) {
        Job job = jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));

        if (job.getStatus().isTerminal()) {
            log.warn("ignored callback for terminal job id={} from={} to={}",
                    id, job.getStatus(), newStatus);
            return job;
        }

        switch (newStatus) {
            case RUNNING -> job.markRunning(clock);
            case SUCCEEDED -> {
                job.markSucceeded(resultUri, clock);
                jobMetrics.recordSucceeded();
            }
            case FAILED -> {
                job.markFailed(errorMessage, clock);
                jobMetrics.recordFailed();
            }
            default -> throw new IllegalArgumentException("unsupported callback status: " + newStatus);
        }

        Job persisted = jobRepository.save(job);
        if (newStatus.isTerminal()) {
            outboxWriter.write(new JobEvent.JobCompleted(
                    persisted.getId().toString(),
                    persisted.getStatus().name(),
                    persisted.getResultUri() != null ? persisted.getResultUri() : "",
                    persisted.getErrorMessage() != null ? persisted.getErrorMessage() : "",
                    persisted.getFinishedAt() != null ? persisted.getFinishedAt().toString() : ""
            ));
        }
        return persisted;
    }

    @CacheEvict(cacheNames = "jobs", key = "#id")
    @Transactional
    public Job cancel(UUID id) {
        Job job = jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));

        if (job.getStatus().isTerminal()) {
            return job;   // 멱등 — 이미 종료된 Job 은 그대로 반환
        }
        if (job.getK8sJobName() != null) {
            jobDispatcher.cancel(job.getK8sJobName());
        }
        job.markCancelled(clock);
        jobMetrics.recordCancelled();

        Job persisted = jobRepository.save(job);
        outboxWriter.write(new JobEvent.JobCompleted(
                persisted.getId().toString(),
                persisted.getStatus().name(),
                "",
                "",
                persisted.getFinishedAt() != null ? persisted.getFinishedAt().toString() : ""
        ));
        return persisted;
    }
}
