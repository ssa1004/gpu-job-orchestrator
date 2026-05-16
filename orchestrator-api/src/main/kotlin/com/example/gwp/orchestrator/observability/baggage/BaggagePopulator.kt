package com.example.gwp.orchestrator.observability.baggage

import io.micrometer.tracing.BaggageInScope
import io.micrometer.tracing.BaggageManager
import org.slf4j.MDC
import java.util.LinkedHashMap

/**
 * 한 요청 / 한 작업 흐름의 시작점 (HTTP 컨트롤러 / 스케줄러 진입점) 에서 baggage 를
 * 활성 trace 에 채우고, 흐름 종료 시 자동 close 되는 try-with-resources 를 반환.
 *
 * ### 왜 BaggageScope 가 필요한가
 * baggage 는 *현재 활성 span 의 라이프타임* 동안만 유효하다. `makeCurrent()`
 * 호출이 thread-local 에 push, scope close 가 pop. scope 누락 시 baggage 가 다음 요청까지
 * 살아남아 *다른 사용자의 trace 에 owner 가 박히는* 사고가 난다 — 회계 / 보안 사고.
 *
 * ### MDC 동시 미러링
 * 로그 패턴 (`application.yml` 의 `logging.pattern.level`) 이 MDC 키를
 * 읽어 라인마다 박는다. baggage 를 MDC 에 동시 미러링하면 로그가 자동으로 owner /
 * cost-center 로 색인 가능. baggage 의 MDC 자동 동기화는 Spring Boot 3.4+ 에서 표준이
 * 되었지만, 우리는 명시적으로 둬서 *baggage 키 추가시 의식적으로 MDC 도 갱신* 하도록.
 */
class BaggagePopulator(private val baggageManager: BaggageManager) {

    /**
     * 한 요청 흐름에서 활성화할 baggage 묶음.
     *
     * @return AutoCloseable scope — try-with-resources 로 흐름 종료 시 자동 close.
     */
    fun activate(values: Map<String, String>): BaggageScope {
        val opened = LinkedHashMap<String, BaggageInScope>()
        val mdcSet = LinkedHashMap<String, String>()
        try {
            for ((key, value) in values) {
                if (!JobBaggage.isAllowed(key, value)) continue
                // create + makeCurrent — BaggageManager 가 trace 에 push.
                val inScope = baggageManager.createBaggageInScope(key, value)
                opened[key] = inScope
                MDC.put(key, value)
                mdcSet[key] = value
            }
            return BaggageScope(opened, mdcSet)
        } catch (e: RuntimeException) {
            // partial open 시 fail-safe — 이미 연 scope / MDC 를 모두 close.
            BaggageScope(opened, mdcSet).close()
            throw e
        }
    }

    /**
     * 활성화된 baggage / MDC 의 자동 close.
     * close 가 일어날 때 baggage 와 MDC 가 동시에 정리된다 — *반드시* try-with-resources
     * 로 사용.
     */
    class BaggageScope internal constructor(
        private val openedBaggage: MutableMap<String, BaggageInScope>,
        private val mdcSet: MutableMap<String, String>,
    ) : AutoCloseable {

        override fun close() {
            // baggage scope 닫고 MDC 도 같이 비운다. 둘 중 하나라도 빠지면 다음 요청에 leak.
            for (scope in openedBaggage.values) {
                try {
                    scope.close()
                } catch (_: RuntimeException) {
                    // 한 baggage close 실패가 나머지 close 를 막지 않게.
                }
            }
            for (key in mdcSet.keys) {
                MDC.remove(key)
            }
            openedBaggage.clear()
            mdcSet.clear()
        }
    }
}
