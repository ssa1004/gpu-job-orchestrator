package com.example.gwp.orchestrator.adapter.kubernetes

import com.example.gwp.orchestrator.domain.Job
import io.fabric8.kubernetes.client.KubernetesClientException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import org.slf4j.LoggerFactory
import java.util.function.Supplier

/**
 * [JobDispatcher] 의 retry + circuit breaker decorator. K8s API server 의 transient
 * 오류 (network blip, 429 throttling, 503 leader election) 는 retry 로 흡수, 영구 장애는
 * circuit breaker 로 fast-fail.
 *
 * ### 왜 retry 가 circuit breaker *바깥* 인가
 *
 * Resilience4j 의 표준 권장 순서: **Retry → CircuitBreaker → 실제 호출**. 이유:
 * - Retry 가 안쪽이면, retry 한 번마다 회로 호출 1회로 카운트되어 한 번의 클라이언트
 *   호출이 sliding-window 의 N 칸을 잡아먹음 → false-positive OPEN.
 * - Retry 가 바깥이면, 회로가 OPEN 상태에서는 retry 시도마다 즉시 fast-fail
 *   (CallNotPermittedException) — backend hang 시간을 기다리지 않고 바로 다음 retry
 *   대기 → max-attempts 안에 끝남.
 * - CircuitBreaker 가 retry exhausted 후의 마지막 결과만 카운트해야 *집계가 의미 있음*.
 *   client 가 여러 번 시도한 *논리적 호출* 단위로 실패율 집계.
 *
 * ### 왜 jitter
 *
 * 100 Pod 가 같은 시각에 503 을 받고 똑같이 500ms 후 retry 하면, broker 입장에서는
 * 한 번 더 spike. exponential backoff with full jitter 는 retry 시점을
 * `random(0, base * 2^attempt)` 구간에 균일 분포로 흩어 thundering herd 를 방지.
 * application.yml 의 `resilience4j.retry.configs.default` 참고.
 *
 * ### 이 클래스가 잡는 두 결말
 * - **Retry exhausted** — max-attempts 를 다 써도 transient 가 안 풀리면 마지막
 *   원인 ([KubernetesClientException]) 이 그대로 throw. CircuitBreaker 가 카운트.
 * - **Circuit OPEN** — 누적 실패율 / slow-call 비율이 임계 초과. retry 시도 자체가
 *   즉시 [CallNotPermittedException] 으로 떨어짐 → JobDispatchException 변환.
 *
 * 도메인 검증 실패 (`invalid gpuCount`) 는 retry / circuit 둘 다 카운트 안 함 —
 * backend 가 살아 있어도 발생. application.yml 의 retry-exceptions / ignore-exceptions
 * 화이트리스트로 분류.
 */
class ResilientJobDispatcher(
    private val delegate: JobDispatcher,
    private val circuitBreaker: CircuitBreaker?,
    private val retry: Retry?,
) : JobDispatcher {

    init {
        if (retry != null) {
            // 운영 가시성 — retry 시도 / 실패가 일어났는지 한 줄 로그.
            retry.eventPublisher.onRetry { e ->
                log.warn(
                    "k8s retry attempt {} after {} ms — cause={}",
                    e.numberOfRetryAttempts,
                    e.waitInterval.toMillis(),
                    e.lastThrowable?.toString() ?: "n/a",
                )
            }
        }
    }

    override fun dispatch(job: Job): String {
        val call = Supplier<String> { delegate.dispatch(job) }
        return try {
            decorate(call).get()
        } catch (e: CallNotPermittedException) {
            log.warn("k8s circuit OPEN — refusing dispatch for job={}", job.id)
            throw JobDispatchException("k8s circuit breaker OPEN", e)
        } catch (e: KubernetesClientException) {
            // Retry exhausted 또는 4xx (predicate 거절) — delegate 에서 raw 예외가 그대로
            // 올라온 경우. 호출자 일관성을 위해 JobDispatchException 으로 wrap.
            // 실제 KubernetesJobDispatcher 는 자체에서 wrap 하지만, 다른 delegate (테스트
            // mock 등) 가 raw 를 던질 수 있으므로 안전망.
            throw JobDispatchException("k8s API call failed: ${e.message}", e)
        }
    }

    override fun cancel(k8sJobName: String) {
        val call = Supplier<Unit> {
            delegate.cancel(k8sJobName)
        }
        try {
            decorate(call).get()
        } catch (e: CallNotPermittedException) {
            log.warn("k8s circuit OPEN — refusing cancel for name={}", k8sJobName)
            throw JobDispatchException("k8s circuit breaker OPEN", e)
        } catch (e: KubernetesClientException) {
            throw JobDispatchException("k8s API call failed: ${e.message}", e)
        }
    }

    /**
     * decorator chain — Retry 가 CircuitBreaker 의 *바깥쪽*. 호출 흐름:
     * Retry → CircuitBreaker → delegate.
     *
     * Supplier 를 안쪽 → 바깥쪽으로 wrap — 가장 마지막에 wrap 한 게 가장 바깥쪽.
     * CircuitBreaker.decorateSupplier 를 *먼저* 호출 (가장 안쪽) 한 후 Retry.decorateSupplier
     * 로 wrap 하면 호출 시 흐름이 Retry → CircuitBreaker → delegate 가 된다.
     * 이 호출 순서가 위에 설명한 retry 바깥 / CB 안쪽 의미를 정확히 만든다.
     */
    private fun <T> decorate(call: Supplier<T>): Supplier<T> {
        var chain: Supplier<T> = call
        if (circuitBreaker != null) {
            chain = CircuitBreaker.decorateSupplier(circuitBreaker, chain)
        }
        if (retry != null) {
            chain = Retry.decorateSupplier(retry, chain)
        }
        return chain
    }

    companion object {
        private val log = LoggerFactory.getLogger(ResilientJobDispatcher::class.java)
    }
}
