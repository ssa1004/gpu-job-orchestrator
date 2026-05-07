package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.cost.CostRate;
import com.example.gwp.orchestrator.cost.CostRateProvider;
import com.example.gwp.orchestrator.cost.JobCostRecord;
import com.example.gwp.orchestrator.cost.JobCostRecordRepository;
import com.example.gwp.orchestrator.domain.Job;
import com.example.gwp.orchestrator.domain.JobSpec;
import com.example.gwp.orchestrator.domain.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CostAttributionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock JobCostRecordRepository costRecords;
    @Mock CostRateProvider rateProvider;

    CostAttributionService service;

    @BeforeEach
    void setUp() {
        service = new CostAttributionService(costRecords, rateProvider, CLOCK);
        when(rateProvider.current()).thenReturn(new CostRate(new BigDecimal("5000")));
    }

    /** SUCCEEDED 잡 — record 1건 생성. owner / gpu / runtime 박제. */
    @Test
    void recordCost_succeededJob_persistsRecord() {
        Job job = aRunningJob("alice", 2);
        // 30분 후 종착하도록 살짝 흘러간 clock 시뮬레이션
        Clock later = Clock.fixed(CLOCK.instant().plusSeconds(1800), ZoneOffset.UTC);
        job.markSucceeded("s3://b/o", later);

        service.recordCost(job);

        ArgumentCaptor<JobCostRecord> captor = ArgumentCaptor.forClass(JobCostRecord.class);
        verify(costRecords).save(captor.capture());
        JobCostRecord saved = captor.getValue();
        assertThat(saved.getOwner()).isEqualTo("alice");
        assertThat(saved.getGpuCount()).isEqualTo(2);
        assertThat(saved.getFinalStatus()).isEqualTo(JobStatus.SUCCEEDED);
        // 5000 × 2 × 0.5 = 5,000
        assertThat(saved.getComputedCost()).isEqualByComparingTo("5000");
    }

    /** Non-terminal job — 무시 (warn log + skip). */
    @Test
    void recordCost_runningJob_skips() {
        Job job = aRunningJob("alice", 1);

        service.recordCost(job);

        verify(costRecords, never()).save(any());
    }

    /** UNIQUE 위반 (이미 기록됨) — 멱등 처리, 예외 안 던짐. */
    @Test
    void recordCost_duplicateInsert_swallowed() {
        Job job = aRunningJob("alice", 1);
        Clock later = Clock.fixed(CLOCK.instant().plusSeconds(60), ZoneOffset.UTC);
        job.markSucceeded("s3://b/o", later);
        when(costRecords.save(any(JobCostRecord.class)))
                .thenThrow(new DataIntegrityViolationException("uq_job_cost_job_id"));

        // throw 하지 않음
        service.recordCost(job);

        verify(costRecords).save(any());
    }

    /** Dispatch 실패 잡 — startedAt null, runtime 0 record 라도 영속됨. */
    @Test
    void recordCost_dispatchFailedJob_recordsZeroCost() {
        // RUNNING 안 거치고 곧장 FAILED — submit 직후 markFailed
        Job job = Job.submit(new JobSpec("alice", "s3://b/i", "eng:1", 4), null, CLOCK);
        job.markFailed("dispatch failed: k8s API down", CLOCK);

        service.recordCost(job);

        ArgumentCaptor<JobCostRecord> captor = ArgumentCaptor.forClass(JobCostRecord.class);
        verify(costRecords).save(captor.capture());
        JobCostRecord saved = captor.getValue();
        assertThat(saved.getJobStartedAt()).isNull();
        assertThat(saved.getRuntimeMillis()).isZero();
        assertThat(saved.getComputedCost()).isEqualByComparingTo("0");
        assertThat(saved.getFinalStatus()).isEqualTo(JobStatus.FAILED);
    }

    /** finishedAt null 가드 (이론상 없어야 하지만 방어) — skip. */
    @Test
    void recordCost_jobWithoutFinishedAt_skips() {
        Job job = aRunningJob("alice", 1);
        // markSucceeded 호출 안 함 — 비정상 상태지만 RUNNING 인 채로 (terminal 가드 먼저 작동).

        service.recordCost(job);

        verify(costRecords, never()).save(any());
    }

    private Job aRunningJob(String owner, int gpus) {
        Job job = Job.submit(new JobSpec(owner, "s3://b/i", "eng:1", gpus), null, CLOCK);
        job.markDispatched("k8s-1", CLOCK);
        job.markRunning(CLOCK);
        return job;
    }
}
