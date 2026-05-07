package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.cost.JobCostRecord;
import com.example.gwp.orchestrator.cost.JobCostRecordRepository;
import com.example.gwp.orchestrator.cost.JobCostRecordRepository.OwnerCostSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cost 조회 책임 — FinOps dashboard / 빌링 export 의 read 모델.
 *
 * <p><b>왜 분리</b>: write (CostAttributionService) 와 read (CostQueryService) 책임 분리.
 * read 는 readOnly 트랜잭션 + 캐시 친화적으로 진화 가능, write 는 정확성 / 멱등성 우선.</p>
 *
 * <p><b>제공하는 view</b>:
 * <ul>
 *   <li>한 잡의 cost 단건 조회 — 사용자가 "내 잡 얼마 나왔지" 확인</li>
 *   <li>owner 별 시간 구간 합계 — 월별 청구서 기반 (예: alice 의 5월 1일 ~ 5월 31일)</li>
 *   <li>전체 시간 구간 합계 — 운영 / 회계 (이번 달 GPU 비용 총합)</li>
 *   <li>Top spender — owner 별 group 정렬, 운영 dashboard 의 "GPU 많이 쓰는 팀 순위"</li>
 * </ul>
 *
 * <p><b>왜 운영 query 가 recordedAt 기준</b>: 잡이 *언제 끝났느냐* 가 회계 기준.
 * jobStartedAt 은 잡이 *언제 시작했느냐* — 시작은 4월인데 끝은 5월인 잡이 있으면 5월에 청구.
 * (다른 정책이 필요하면 jobStartedAt / jobFinishedAt 인덱스 추가 후 별도 query.)</p>
 */
@Service
@RequiredArgsConstructor
public class CostQueryService {

    /** Top-N 의 안전 상한 — 한 페이지에 너무 많이 받으면 응답 크기 + DB 부하. */
    private static final int MAX_TOP_N = 100;

    private final JobCostRecordRepository costRecords;

    /** 한 잡의 cost record. 없으면 empty (아직 종착 안 했거나 dispatch 실패도 record 못 만든 corner). */
    @Transactional(readOnly = true)
    public Optional<JobCostRecord> findByJobId(UUID jobId) {
        return costRecords.findByJobId(jobId).stream().findFirst();
    }

    /**
     * 특정 owner 의 시간 구간 cost 요약. 결과 없으면 jobCount=0, 다른 필드도 0.
     *
     * @param owner 청구 대상
     * @param from  시작 (inclusive)
     * @param to    끝 (exclusive)
     */
    @Transactional(readOnly = true)
    public OwnerCostSummary summaryForOwner(String owner, Instant from, Instant to) {
        validateRange(from, to);
        OwnerCostSummary result = costRecords.aggregateByOwner(owner, from, to);
        return result != null ? result
                : new OwnerCostSummary(0, 0, 0, BigDecimal.ZERO);
    }

    /** 전체 owner 의 시간 구간 cost 요약. */
    @Transactional(readOnly = true)
    public OwnerCostSummary summaryAll(Instant from, Instant to) {
        validateRange(from, to);
        OwnerCostSummary result = costRecords.aggregateAll(from, to);
        return result != null ? result
                : new OwnerCostSummary(0, 0, 0, BigDecimal.ZERO);
    }

    /**
     * Top spender — owner 별 group 합계, totalCost DESC 정렬, 상위 N.
     *
     * <p>운영 dashboard 의 "이번 주 GPU 많이 쓴 팀 TOP 10" 류 화면. 사람 dimension 이 owner 외에
     * 더 필요해지면 (team, project) GROUP BY 확장 — 후속 ADR.</p>
     */
    @Transactional(readOnly = true)
    public List<TopSpender> topSpenders(Instant from, Instant to, int topN) {
        validateRange(from, to);
        if (topN <= 0) throw new IllegalArgumentException("topN must be positive");
        int limit = Math.min(topN, MAX_TOP_N);
        return costRecords.aggregateByOwnerGroup(from, to, PageRequest.of(0, limit)).stream()
                .map(row -> new TopSpender(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue(),
                        ((Number) row[3]).longValue(),
                        (BigDecimal) row[4]
                ))
                .toList();
    }

    private void validateRange(Instant from, Instant to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
    }

    /**
     * 운영 dashboard 의 한 row.
     *
     * @param owner               잡 owner
     * @param jobCount            구간 내 종착 잡 수
     * @param totalRuntimeMillis  총 runtime (잡 수 × runtime 평균과 비교 가능)
     * @param totalGpuMillis      GPU-시간 (millis 단위) — gpuCount × runtime 의 합
     * @param totalCost           청구 금액 (KRW)
     */
    public record TopSpender(
            String owner,
            long jobCount,
            long totalRuntimeMillis,
            long totalGpuMillis,
            BigDecimal totalCost
    ) {}
}
