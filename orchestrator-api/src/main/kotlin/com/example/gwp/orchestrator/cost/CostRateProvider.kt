package com.example.gwp.orchestrator.cost

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 현재 적용되는 GPU-hour 단가 — 단순 application.yml 기반 (단일 단가).
 *
 * 대형 운영에서는 instance type 별 (A100 / H100 / V100) 다른 rate, 시간대별 (day/night),
 * customer/team 별 차등 등 복잡해짐. 그때는 별도 aggregate `GpuRateCard` 도입 + DB 영속.
 * 본 ADR 의 경계는 *단일 정적 단가*.
 *
 * 운영에서 단가가 바뀌면 application.yml 수정 + restart. 과거 cost 는 영향을 받지 않는다.
 * 이미 저장된 JobCostRecord 가 계산 시점 단가를 그대로 보관하기 때문이다 (record 의
 * ratePerGpuHour 컬럼).
 *
 * **시작 시점 검증**: 잘못된 설정 (음수 / 숫자 아님) 은 첫 잡이 종착할 때까지 미발견되면
 * 곤란 — `@PostConstruct` 로 즉시 검증해 잘못된 설정 시 컨테이너가 시작 자체를 실패하게.
 * 같은 이유로 [CostRate] 인스턴스도 startup 에 한 번 만들어 캐시 (매 잡마다 BigDecimal
 * 재생성 안 함).
 *
 * Java 호출자 (`rateProvider.current()`) 무변경 — `plugin.spring` 이 `@Component` 자동 open.
 */
@Component
class CostRateProvider {

    /** 기본 5,000원/GPU-시간 — 클라우드 GPU 가격 대략. application.yml 에서 override 가능. */
    @Value("\${gwp.cost.gpu-hour-rate-krw:5000}")
    private lateinit var configuredRate: String

    private lateinit var cachedRate: CostRate

    @PostConstruct
    fun validateAndCache() {
        val parsed: BigDecimal = try {
            BigDecimal(configuredRate)
        } catch (ex: NumberFormatException) {
            throw IllegalStateException(
                "Invalid gwp.cost.gpu-hour-rate-krw value: '$configuredRate'" +
                    " — must be a non-negative decimal number",
                ex,
            )
        }
        // CostRate 생성자가 음수 검증을 수행 — 음수면 IllegalArgumentException → 컨테이너 시작 실패.
        this.cachedRate = CostRate(parsed)
    }

    fun current(): CostRate = cachedRate
}
