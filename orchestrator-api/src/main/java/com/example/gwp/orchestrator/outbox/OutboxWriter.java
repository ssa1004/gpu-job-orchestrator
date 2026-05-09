package com.example.gwp.orchestrator.outbox;

import com.example.gwp.orchestrator.observability.baggage.JobBaggage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.BaggageManager;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 도메인 서비스에서 호출. 호출 트랜잭션 안에서 outbox 테이블 (DB 안의 발신함) 에 INSERT.
 * DB 변경과 이벤트 발행 의도가 한 트랜잭션에서 묶이므로 둘 다 commit 또는 둘 다 rollback.
 *
 * <p>타입이 정해진 {@link JobEvent} 만 받음 — Map&lt;String, Object&gt; 같은 자유 payload
 * 는 의도적으로 받지 않아 컨슈머와의 contract (메시지 스키마) 안정성을 강제.</p>
 *
 * <h3>W3C trace context 스냅샷</h3>
 *
 * <p>row INSERT 시점 (T0) 의 현재 span 으로부터 W3C {@code traceparent} 문자열을 만들어
 * row 에 그대로 보관한다. 나중에 OutboxRelay 가 polling 으로 publish 할 때 (T1) 이 값을
 * Kafka 헤더로 복원 → consumer 가 같은 trace 안에서 child span 을 만들 수 있다 (ADR-0018).</p>
 *
 * <p>왜 {@link io.micrometer.tracing.propagation.Propagator} 가 아닌 직접 포맷팅을 쓰는가:
 * Propagator API 는 *carrier 에 값을 주입* 이 목적이지 *trace context 를 단일 문자열로*
 * 추출하는 직접적 메서드가 없다. W3C 포맷은 RFC 9.5.1 로 표준화되어 있어 (55자, 고정 포맷)
 * 직접 포맷팅이 더 간단하고 명시적이다. inject 가 필요하면 send 시점에 OutboxRelay 가
 * 그때의 carrier (Kafka Headers) 에 직접 주입한다.</p>
 */
@Component
public class OutboxWriter {

    /** W3C traceparent (RFC 9.5.1) version 필드 — 현재 표준 단일 값. */
    static final String TRACEPARENT_VERSION = "00";
    /** sampled 플래그. 1 = sampled (export 함), 0 = unsampled (drop 가능). */
    static final String FLAG_SAMPLED = "01";
    static final String FLAG_NOT_SAMPLED = "00";

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Tracer tracer;
    /**
     * baggage 캡처용. 빈이 없으면 (테스트 / tracing bridge 미설치) {@link BaggageManager#NOOP}
     * 으로 fallback — getAllBaggage 가 빈 맵이라 baggage 컬럼은 null.
     */
    private final BaggageManager baggageManager;

    /**
     * 기존 (BaggageManager 없는) 호출자 호환용. 테스트 / 트레이서 비활성 환경에서
     * baggage 캡처는 silent skip.
     */
    public OutboxWriter(OutboxRepository outboxRepository,
                        ObjectMapper objectMapper,
                        Clock clock,
                        Tracer tracer) {
        this(outboxRepository, objectMapper, clock, tracer, BaggageManager.NOOP);
    }

    /**
     * Spring 이 호출. 빈 컨테이너에 BaggageManager 가 있으면 그걸 wiring, 없으면 NOOP fallback.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public OutboxWriter(OutboxRepository outboxRepository,
                        ObjectMapper objectMapper,
                        Clock clock,
                        Tracer tracer,
                        ObjectProvider<BaggageManager> baggageManagerProvider) {
        this(outboxRepository, objectMapper, clock, tracer,
                baggageManagerProvider.getIfAvailable(() -> BaggageManager.NOOP));
    }

    /** 테스트 / 직접 wiring 용 — 모든 의존을 명시. */
    OutboxWriter(OutboxRepository outboxRepository,
                 ObjectMapper objectMapper,
                 Clock clock,
                 Tracer tracer,
                 BaggageManager baggageManager) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tracer = tracer;
        this.baggageManager = baggageManager;
    }

    public void write(JobEvent event) {
        try {
            String traceparent = currentTraceparent();
            String baggage = currentBaggageHeader();
            OutboxMessage msg = OutboxMessage.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("Job")
                    .aggregateId(event.aggregateId())
                    .eventType(event.type())
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(clock.instant())
                    .traceparent(traceparent)
                    .baggage(baggage)
                    .build();
            outboxRepository.save(msg);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload for " + event.type(), e);
        }
    }

    /**
     * 지금 활성 baggage 를 W3C baggage 헤더 (RFC 9.5.3) 단일 문자열로 직렬화.
     *
     * <p>포맷: {@code key1=value1,key2=value2}. 값에 reserved 문자 (콤마 / 등호 / 세미콜론)
     * 가 들어가면 RFC 가 percent-encoding 을 권장 — {@link URLEncoder} 로 안전 인코딩.</p>
     *
     * <p>화이트리스트 ({@link JobBaggage#ALLOWED}) 외 키는 drop. baggage 가 비어 있으면
     * null 반환 → OutboxRelay 가 헤더 주입을 건너뛴다.</p>
     */
    private String currentBaggageHeader() {
        Map<String, String> all = baggageManager.getAllBaggage();
        if (all == null || all.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (var entry : all.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!JobBaggage.isAllowed(key, value)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(key).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 지금 활성 span 으로부터 W3C traceparent 문자열을 만든다. 활성 span 이 없거나
     * noop 트레이서면 null — 이 경우 OutboxRelay 가 헤더 주입을 건너뛴다.
     *
     * <p>포맷: {@code 00-{traceId 32hex}-{spanId 16hex}-{flags 2hex}} (55자 고정).</p>
     */
    private String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop()) return null;
        var ctx = span.context();
        String traceId = ctx.traceId();
        String spanId = ctx.spanId();
        if (traceId == null || spanId == null) return null;
        String flags = Boolean.TRUE.equals(ctx.sampled()) ? FLAG_SAMPLED : FLAG_NOT_SAMPLED;
        return TRACEPARENT_VERSION + "-" + traceId + "-" + spanId + "-" + flags;
    }
}
