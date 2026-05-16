package com.example.gwp.orchestrator.application

import com.example.gwp.orchestrator.cost.JobCostRecord
import com.example.gwp.orchestrator.cost.JobCostRecordRepository
import com.example.gwp.orchestrator.cost.JobCostRecordRepository.OwnerCostSummary
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Cost 조회 책임 — FinOps dashboard / 빌링 export 의 read 모델.
 *
 * 왜 분리: write (CostAttributionService) 와 read (CostQueryService) 책임 분리. read 는
 * readOnly 트랜잭션 + 캐시 친화적으로 진화 가능, write 는 정확성 / 멱등성 우선.
 *
 * 제공하는 view:
 * - 한 잡의 cost 단건 조회 — 사용자가 "내 잡 얼마 나왔지" 확인
 * - owner 별 시간 구간 합계 — 월별 청구서 기반 (예: alice 의 5월 1일 ~ 5월 31일)
 * - 전체 시간 구간 합계 — 운영 / 회계 (이번 달 GPU 비용 총합)
 * - Top spender — owner 별 group 정렬, 운영 dashboard 의 "GPU 많이 쓰는 팀 순위"
 *
 * 왜 운영 query 가 recordedAt 기준: 잡이 *언제 끝났느냐* 가 회계 기준.
 * jobStartedAt 은 잡이 *언제 시작했느냐* — 시작은 4월인데 끝은 5월인 잡이 있으면 5월에 청구.
 * (다른 정책이 필요하면 jobStartedAt / jobFinishedAt 인덱스 추가 후 별도 query.)
 *
 * Java 호출자 (Controller / TopSpendersResponse / Test) 무변경 — Kotlin primary
 * constructor 가 같은 positional 시그니처. 중첩 [TopSpender] 는 `@JvmRecord data class`
 * 라 Java 측에서 `CostQueryService.TopSpender` 로 보이고 `row.totalGpuMillis()` 등
 * record accessor 가 그대로 동작.
 */
@Service
class CostQueryService(
    private val costRecords: JobCostRecordRepository,
) {

    /** 한 잡의 cost record. 없으면 empty (아직 종착 안 했거나 dispatch 실패도 record 못 만든 corner). */
    @Transactional(readOnly = true)
    fun findByJobId(jobId: UUID): Optional<JobCostRecord> =
        costRecords.findByJobId(jobId).stream().findFirst()

    /**
     * 특정 owner 의 시간 구간 cost 요약. 결과 없으면 jobCount=0, 다른 필드도 0.
     *
     * @param owner 청구 대상
     * @param from  시작 (inclusive)
     * @param to    끝 (exclusive)
     */
    @Transactional(readOnly = true)
    fun summaryForOwner(owner: String, from: Instant?, to: Instant?): OwnerCostSummary {
        validateRange(from, to)
        val result = costRecords.aggregateByOwner(owner, from, to)
        return result ?: OwnerCostSummary(0, 0, 0, BigDecimal.ZERO)
    }

    /** 전체 owner 의 시간 구간 cost 요약. */
    @Transactional(readOnly = true)
    fun summaryAll(from: Instant?, to: Instant?): OwnerCostSummary {
        validateRange(from, to)
        val result = costRecords.aggregateAll(from, to)
        return result ?: OwnerCostSummary(0, 0, 0, BigDecimal.ZERO)
    }

    /**
     * Top spender — owner 별 group 합계, totalCost DESC 정렬, 상위 N.
     *
     * 운영 dashboard 의 "이번 주 GPU 많이 쓴 팀 TOP 10" 류 화면. 사람 dimension 이 owner
     * 외에 더 필요해지면 (team, project) GROUP BY 확장 — 후속 ADR.
     */
    @Transactional(readOnly = true)
    fun topSpenders(from: Instant?, to: Instant?, topN: Int): List<TopSpender> {
        validateRange(from, to)
        if (topN <= 0) throw IllegalArgumentException("topN must be positive")
        val limit = minOf(topN, MAX_TOP_N)
        return costRecords.aggregateByOwnerGroup(from, to, PageRequest.of(0, limit))
            .map { row ->
                TopSpender(
                    row[0] as String,
                    (row[1] as Number).toLong(),
                    (row[2] as Number).toLong(),
                    (row[3] as Number).toLong(),
                    row[4] as BigDecimal,
                )
            }
    }

    private fun validateRange(from: Instant?, to: Instant?) {
        if (from == null || to == null) throw IllegalArgumentException("from/to required")
        if (!from.isBefore(to)) throw IllegalArgumentException("from must be before to")
        if (Duration.between(from, to) > MAX_RANGE) {
            throw IllegalArgumentException(
                "range exceeds max " + MAX_RANGE.toDays() + " days — paginate the query",
            )
        }
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
    @JvmRecord
    data class TopSpender(
        val owner: String,
        val jobCount: Long,
        val totalRuntimeMillis: Long,
        val totalGpuMillis: Long,
        val totalCost: BigDecimal,
    )

    companion object {
        /** Top-N 의 안전 상한 — 한 페이지에 너무 많이 받으면 응답 크기 + DB 부하. */
        private const val MAX_TOP_N = 100

        /**
         * 시간 구간 합계 query 의 최대 범위 — 약 1년 + 하루 여유 (윤년 대비).
         *
         * 주된 use case 는 월간 청구서 / 연간 회계 export 라 1년이면 충분. 상한이 없으면
         * 호출자가 실수로 `from = Instant.EPOCH` 같은 값을 보냈을 때 (예: 클라이언트
         * default 값을 안 채움) 인덱스 스캔이 수백만 row 를 훑게 된다 → DB 부하 폭주.
         */
        private val MAX_RANGE: Duration = Duration.ofDays(366)
    }
}
