package com.example.gwp.orchestrator.outbox;

import com.example.gwp.orchestrator.observability.baggage.JobBaggage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.BaggageManager;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxWriter 가 활성 baggage 를 W3C 헤더 (RFC 9.5.3) 포맷 단일 문자열로 직렬화하는지 검증.
 *
 * <p>이 캡처가 제대로 동작해야 OutboxRelay 가 send 시점에 Kafka {@code baggage} 헤더로
 * 복원할 수 있고, consumer 측이 자기 trace 의 baggage 로 풀어 metric / log 라벨에 반영한다
 * (ADR-0021).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxWriterBaggageTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock OutboxRepository outboxRepository;
    @Mock Tracer tracer;

    @Test
    void write_serializesActiveBaggageIntoRfc9503Header() {
        Map<String, String> baggage = new LinkedHashMap<>();
        baggage.put(JobBaggage.OWNER, "alice");
        baggage.put(JobBaggage.COST_CENTER, "research-vision");
        OutboxWriter writer = new OutboxWriter(outboxRepository, new ObjectMapper(),
                CLOCK, tracer, fixed(baggage));
        when(tracer.currentSpan()).thenReturn(null);

        writer.write(new JobEvent.JobSubmitted(
                "job-9", "alice", "engine:1.0", 1, "NORMAL", "QUEUED", null));

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        // 입력 순서가 보존되어야 — LinkedHashMap 사용 + key=value,key=value 형태.
        assertThat(captor.getValue().getBaggage())
                .isEqualTo("owner=alice,cost-center=research-vision");
    }

    @Test
    void write_dropsNonWhitelistedBaggageEntries() {
        Map<String, String> baggage = new LinkedHashMap<>();
        baggage.put(JobBaggage.OWNER, "alice");
        baggage.put("authorization", "bearer-xxx");   // drop
        baggage.put("ssn", "123-45-6789");             // drop
        OutboxWriter writer = new OutboxWriter(outboxRepository, new ObjectMapper(),
                CLOCK, tracer, fixed(baggage));
        when(tracer.currentSpan()).thenReturn(null);

        writer.write(new JobEvent.JobSubmitted(
                "job-10", "alice", "engine:1.0", 1, "NORMAL", "QUEUED", null));

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        // sensitive 값은 어떤 형태로도 헤더에 들어가서는 안 된다.
        String header = captor.getValue().getBaggage();
        assertThat(header).isEqualTo("owner=alice");
        assertThat(header).doesNotContain("authorization", "bearer", "ssn", "123-45-6789");
    }

    @Test
    void write_percentEncodesValuesWithReservedCharacters() {
        Map<String, String> baggage = Map.of(JobBaggage.COST_CENTER, "team a, comma & equals=here");
        OutboxWriter writer = new OutboxWriter(outboxRepository, new ObjectMapper(),
                CLOCK, tracer, fixed(baggage));
        when(tracer.currentSpan()).thenReturn(null);

        writer.write(new JobEvent.JobSubmitted(
                "job-11", "alice", "engine:1.0", 1, "NORMAL", "QUEUED", null));

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        String header = captor.getValue().getBaggage();
        // raw 콤마 / 등호 가 그대로 들어가면 RFC 9.5.3 의 separator 규칙을 깬다 → encode 필요.
        assertThat(header).startsWith("cost-center=");
        assertThat(header).doesNotContain(", ").doesNotContain(" =");
        assertThat(header).contains("%2C");  // comma encode
        assertThat(header).contains("%3D");  // '=' encode
    }

    @Test
    void write_baggageNullWhenNoActiveBaggage() {
        OutboxWriter writer = new OutboxWriter(outboxRepository, new ObjectMapper(),
                CLOCK, tracer, fixed(Collections.emptyMap()));
        when(tracer.currentSpan()).thenReturn(null);

        writer.write(new JobEvent.JobSubmitted(
                "job-12", "alice", "engine:1.0", 1, "NORMAL", "QUEUED", null));

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getBaggage()).isNull();
    }

    /** 고정된 baggage map 을 반환하는 simple BaggageManager. */
    private static BaggageManager fixed(Map<String, String> baggage) {
        return new BaggageManager() {
            @Override public Map<String, String> getAllBaggage() { return baggage; }
            @Override public Map<String, String> getAllBaggage(TraceContext c) { return baggage; }
            @Override public Baggage getBaggage(String name) { return null; }
            @Override public Baggage getBaggage(TraceContext c, String name) { return null; }
            @Override public Baggage createBaggage(String name) { return Baggage.NOOP; }
            @Override public Baggage createBaggage(String name, String value) { return Baggage.NOOP; }
            @Override public BaggageInScope createBaggageInScope(String name, String value) {
                return BaggageInScope.NOOP;
            }
            @Override public BaggageInScope createBaggageInScope(TraceContext c, String name, String value) {
                return BaggageInScope.NOOP;
            }
        };
    }
}
