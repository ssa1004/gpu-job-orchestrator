package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.cost.CostRate;
import com.example.gwp.orchestrator.cost.JobCostRecord;
import com.example.gwp.orchestrator.cost.JobCostRecordRepository;
import com.example.gwp.orchestrator.cost.JobCostRecordRepository.OwnerCostSummary;
import com.example.gwp.orchestrator.domain.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CostQueryServiceTest {

    private static final Instant FROM = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-01T00:00:00Z");

    @Mock JobCostRecordRepository repo;

    CostQueryService service;

    @BeforeEach
    void setUp() {
        service = new CostQueryService(repo);
    }

    /** 단건 조회 — record 가 있으면 첫 번째 반환. UNIQUE(job_id) 라 1건 이상은 없음. */
    @Test
    void findByJobId_returnsRecordWhenPresent() {
        UUID jobId = UUID.randomUUID();
        JobCostRecord record = JobCostRecord.forJob(
                jobId, "alice", 1, FROM, FROM.plusSeconds(3600), JobStatus.SUCCEEDED,
                new CostRate(new BigDecimal("5000")), FROM.plusSeconds(3600));
        when(repo.findByJobId(jobId)).thenReturn(List.of(record));

        Optional<JobCostRecord> result = service.findByJobId(jobId);

        assertThat(result).isPresent();
        assertThat(result.get().getOwner()).isEqualTo("alice");
    }

    @Test
    void findByJobId_returnsEmptyWhenAbsent() {
        UUID jobId = UUID.randomUUID();
        when(repo.findByJobId(jobId)).thenReturn(List.of());

        assertThat(service.findByJobId(jobId)).isEmpty();
    }

    /** owner 합계 — repo 가 결과 반환하면 그대로. */
    @Test
    void summaryForOwner_returnsAggregate() {
        OwnerCostSummary expected = new OwnerCostSummary(5L, 18_000_000L, 36_000_000L, new BigDecimal("50000"));
        when(repo.aggregateByOwner("alice", FROM, TO)).thenReturn(expected);

        OwnerCostSummary actual = service.summaryForOwner("alice", FROM, TO);

        assertThat(actual).isEqualTo(expected);
    }

    /** owner 합계 — 결과 null 이면 0 으로 채운 dummy 반환 (NPE 회피). */
    @Test
    void summaryForOwner_nullResultReturnsZero() {
        when(repo.aggregateByOwner("alice", FROM, TO)).thenReturn(null);

        OwnerCostSummary actual = service.summaryForOwner("alice", FROM, TO);

        assertThat(actual.jobCount()).isZero();
        assertThat(actual.totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void summaryAll_returnsAggregate() {
        OwnerCostSummary expected = new OwnerCostSummary(120L, 432_000_000L, 864_000_000L, new BigDecimal("1200000"));
        when(repo.aggregateAll(FROM, TO)).thenReturn(expected);

        assertThat(service.summaryAll(FROM, TO)).isEqualTo(expected);
    }

    /** Top spender — Object[] row 들을 record 로 변환. */
    @Test
    void topSpenders_mapsRowsToRecords() {
        Object[] row1 = {"alice", 10L, 36_000_000L, 36_000_000L, new BigDecimal("50000")};
        Object[] row2 = {"bob", 3L, 7_200_000L, 14_400_000L, new BigDecimal("20000")};
        when(repo.aggregateByOwnerGroup(eq(FROM), eq(TO), any(Pageable.class)))
                .thenReturn(List.of(row1, row2));

        List<CostQueryService.TopSpender> result = service.topSpenders(FROM, TO, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).owner()).isEqualTo("alice");
        assertThat(result.get(0).totalCost()).isEqualByComparingTo("50000");
        assertThat(result.get(1).owner()).isEqualTo("bob");
    }

    /** topN 0 이하 거절 — 운영 dashboard 의 무의미한 query 차단. */
    @Test
    void topSpenders_zeroOrNegativeTopN_rejected() {
        assertThatThrownBy(() -> service.topSpenders(FROM, TO, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.topSpenders(FROM, TO, -1))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).aggregateByOwnerGroup(any(), any(), any());
    }

    /** topN 상한 — MAX_TOP_N 을 넘으면 clamp (예외 안 던짐 — UI 가 기본 limit 으로 보낼 수도 있음). */
    @Test
    void topSpenders_topNAboveMax_clampedToMax() {
        when(repo.aggregateByOwnerGroup(eq(FROM), eq(TO), any(Pageable.class))).thenReturn(List.of());

        service.topSpenders(FROM, TO, 10_000);

        verify(repo).aggregateByOwnerGroup(eq(FROM), eq(TO),
                org.mockito.ArgumentMatchers.argThat(p -> p.getPageSize() == 100));
    }

    /** from == to 또는 from > to 거절. */
    @Test
    void timeRange_emptyOrInverted_rejected() {
        assertThatThrownBy(() -> service.summaryAll(FROM, FROM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before");
        assertThatThrownBy(() -> service.summaryAll(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 시간 구간 상한 — 1년 + 1일 같은 거대 query 거절. */
    @Test
    void timeRange_exceedsMax_rejected() {
        Instant tooFar = FROM.plus(Duration.ofDays(400));

        assertThatThrownBy(() -> service.summaryAll(FROM, tooFar))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range exceeds max");
        assertThatThrownBy(() -> service.summaryForOwner("alice", FROM, tooFar))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.topSpenders(FROM, tooFar, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 정확히 366일 (포함) 은 허용. */
    @Test
    void timeRange_exactlyMax_allowed() {
        Instant boundary = FROM.plus(Duration.ofDays(366));
        when(repo.aggregateAll(FROM, boundary)).thenReturn(null);

        // 예외 안 던짐
        OwnerCostSummary result = service.summaryAll(FROM, boundary);

        assertThat(result.jobCount()).isZero();
    }

    @Test
    void timeRange_nullValues_rejected() {
        assertThatThrownBy(() -> service.summaryAll(null, TO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summaryAll(FROM, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
