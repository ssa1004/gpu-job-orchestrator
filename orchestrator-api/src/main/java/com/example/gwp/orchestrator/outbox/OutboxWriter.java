package com.example.gwp.orchestrator.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * 도메인 서비스에서 호출. 호출 트랜잭션 안에서 outbox 테이블 (DB 안의 발신함) 에 INSERT.
 * DB 변경과 이벤트 발행 의도가 한 트랜잭션에서 묶이므로 둘 다 commit 또는 둘 다 rollback.
 *
 * <p>타입이 정해진 {@link JobEvent} 만 받음 — Map&lt;String, Object&gt; 같은 자유 payload
 * 는 의도적으로 받지 않아 컨슈머와의 contract (메시지 스키마) 안정성을 강제.</p>
 *
 * <h3>W3C trace context 박제</h3>
 *
 * <p>row INSERT 시점 (T0) 의 현재 span 으로부터 W3C {@code traceparent} 문자열을 만들어
 * row 에 박는다. 나중에 OutboxRelay 가 polling 으로 publish 할 때 (T1) 이 값을 Kafka
 * 헤더로 복원 → consumer 가 같은 trace 안에서 child span 을 만들 수 있다 (ADR-0018).</p>
 *
 * <p>왜 {@link io.micrometer.tracing.propagation.Propagator} 가 아닌 직접 포맷팅을 쓰는가:
 * Propagator API 는 *carrier 에 값을 주입* 이 목적이지 *trace context 를 단일 문자열로*
 * 추출하는 직접적 메서드가 없다. W3C 포맷은 RFC 9.5.1 로 표준화되어 있어 (55자, 고정 포맷)
 * 직접 포맷팅이 더 간단하고 명시적이다. inject 가 필요하면 send 시점에 OutboxRelay 가
 * 그때의 carrier (Kafka Headers) 에 직접 주입한다.</p>
 */
@Component
@RequiredArgsConstructor
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

    public void write(JobEvent event) {
        try {
            String traceparent = currentTraceparent();
            OutboxMessage msg = OutboxMessage.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("Job")
                    .aggregateId(event.aggregateId())
                    .eventType(event.type())
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(clock.instant())
                    .traceparent(traceparent)
                    .build();
            outboxRepository.save(msg);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload for " + event.type(), e);
        }
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
