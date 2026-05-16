package com.example.gwp.orchestrator.outbox

import com.example.gwp.orchestrator.observability.baggage.JobBaggage
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.tracing.BaggageManager
import io.micrometer.tracing.Tracer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.UUID

/**
 * 도메인 서비스에서 호출. 호출 트랜잭션 안에서 outbox 테이블 (DB 안의 발신함) 에 INSERT.
 * DB 변경과 이벤트 발행 의도가 한 트랜잭션에서 묶이므로 둘 다 commit 또는 둘 다 rollback.
 *
 * 타입이 정해진 [JobEvent] 만 받음 — `Map<String, Object>` 같은 자유 payload
 * 는 의도적으로 받지 않아 컨슈머와의 contract (메시지 스키마) 안정성을 강제.
 *
 * ### W3C trace context 스냅샷
 *
 * row INSERT 시점 (T0) 의 현재 span 으로부터 W3C `traceparent` 문자열을 만들어
 * row 에 그대로 보관한다. 나중에 OutboxRelay 가 polling 으로 publish 할 때 (T1) 이 값을
 * Kafka 헤더로 복원 → consumer 가 같은 trace 안에서 child span 을 만들 수 있다 (ADR-0018).
 *
 * 왜 [io.micrometer.tracing.propagation.Propagator] 가 아닌 직접 포맷팅을 쓰는가:
 * Propagator API 는 *carrier 에 값을 주입* 이 목적이지 *trace context 를 단일 문자열로*
 * 추출하는 직접적 메서드가 없다. W3C 포맷은 RFC 9.5.1 로 표준화되어 있어 (55자, 고정 포맷)
 * 직접 포맷팅이 더 간단하고 명시적이다. inject 가 필요하면 send 시점에 OutboxRelay 가
 * 그때의 carrier (Kafka Headers) 에 직접 주입한다.
 *
 * Java 호출자 — 4-arg / 5-arg / package-private constructor 모두 보존. Spring 은
 * `@Autowired` 가 붙은 5-arg `ObjectProvider<BaggageManager>` 생성자를 wiring 한다.
 */
@Component
class OutboxWriter internal constructor(
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val tracer: Tracer,
    /**
     * baggage 캡처용. 빈이 없으면 (테스트 / tracing bridge 미설치) [BaggageManager.NOOP]
     * 으로 fallback — getAllBaggage 가 빈 맵이라 baggage 컬럼은 null.
     */
    private val baggageManager: BaggageManager,
) {

    /**
     * 기존 (BaggageManager 없는) 호출자 호환용. 테스트 / 트레이서 비활성 환경에서
     * baggage 캡처는 silent skip.
     */
    constructor(
        outboxRepository: OutboxRepository,
        objectMapper: ObjectMapper,
        clock: Clock,
        tracer: Tracer,
    ) : this(outboxRepository, objectMapper, clock, tracer, BaggageManager.NOOP)

    /**
     * Spring 이 호출. 빈 컨테이너에 BaggageManager 가 있으면 그걸 wiring, 없으면 NOOP fallback.
     */
    @Autowired
    constructor(
        outboxRepository: OutboxRepository,
        objectMapper: ObjectMapper,
        clock: Clock,
        tracer: Tracer,
        baggageManagerProvider: ObjectProvider<BaggageManager>,
    ) : this(
        outboxRepository,
        objectMapper,
        clock,
        tracer,
        baggageManagerProvider.getIfAvailable { BaggageManager.NOOP },
    )

    fun write(event: JobEvent) {
        try {
            val traceparent = currentTraceparent()
            val baggage = currentBaggageHeader()
            val msg = OutboxMessage.builder()
                .id(UUID.randomUUID())
                .aggregateType("Job")
                .aggregateId(event.aggregateId())
                .eventType(event.type())
                .payload(objectMapper.writeValueAsString(event))
                .createdAt(clock.instant())
                .traceparent(traceparent)
                .baggage(baggage)
                .build()
            outboxRepository.save(msg)
        } catch (e: JsonProcessingException) {
            throw IllegalStateException("failed to serialize outbox payload for ${event.type()}", e)
        }
    }

    /**
     * 지금 활성 baggage 를 W3C baggage 헤더 (RFC 9.5.3) 단일 문자열로 직렬화.
     *
     * 포맷: `key1=value1,key2=value2`. 값에 reserved 문자 (콤마 / 등호 / 세미콜론)
     * 가 들어가면 RFC 가 percent-encoding 을 권장 — [URLEncoder] 로 안전 인코딩.
     *
     * 화이트리스트 ([JobBaggage.ALLOWED]) 외 키는 drop. baggage 가 비어 있으면
     * null 반환 → OutboxRelay 가 헤더 주입을 건너뛴다.
     */
    private fun currentBaggageHeader(): String? {
        val all = baggageManager.getAllBaggage()
        if (all.isNullOrEmpty()) return null
        val sb = StringBuilder()
        for ((key, value) in all) {
            if (!JobBaggage.isAllowed(key, value)) continue
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(key).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8))
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * 지금 활성 span 으로부터 W3C traceparent 문자열을 만든다. 활성 span 이 없거나
     * noop 트레이서면 null — 이 경우 OutboxRelay 가 헤더 주입을 건너뛴다.
     *
     * 포맷: `00-{traceId 32hex}-{spanId 16hex}-{flags 2hex}` (55자 고정).
     */
    private fun currentTraceparent(): String? {
        val span = tracer.currentSpan() ?: return null
        if (span.isNoop) return null
        val ctx = span.context()
        val traceId = ctx.traceId() ?: return null
        val spanId = ctx.spanId() ?: return null
        val flags = if (java.lang.Boolean.TRUE == ctx.sampled()) FLAG_SAMPLED else FLAG_NOT_SAMPLED
        return "$TRACEPARENT_VERSION-$traceId-$spanId-$flags"
    }

    companion object {
        /** W3C traceparent (RFC 9.5.1) version 필드 — 현재 표준 단일 값. */
        const val TRACEPARENT_VERSION = "00"

        /** sampled 플래그. 1 = sampled (export 함), 0 = unsampled (drop 가능). */
        const val FLAG_SAMPLED = "01"
        const val FLAG_NOT_SAMPLED = "00"
    }
}
