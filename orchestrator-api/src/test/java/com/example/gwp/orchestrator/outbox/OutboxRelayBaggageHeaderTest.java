package com.example.gwp.orchestrator.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * row 에 박제된 traceparent / baggage 가 Kafka {@link ProducerRecord} 의 같은 이름 헤더로
 * 정확히 복원되는지 검증. 이 contract 가 깨지면 consumer 가 trace 를 잃거나 owner 라벨을 못 본다.
 *
 * <p>{@link OutboxRelay#buildRecord} 가 package-private 메서드라 직접 호출 가능 — 통합 환경
 * (Kafka broker / Tracer wiring) 없이 단위 검증.</p>
 */
class OutboxRelayBaggageHeaderTest {

    @Test
    void buildRecord_addsTraceparentAndBaggageHeadersFromRow() {
        OutboxMessage msg = OutboxMessage.builder()
                .id(UUID.randomUUID())
                .aggregateType("Job").aggregateId("job-1")
                .eventType("JobSubmitted")
                .payload("{}")
                .createdAt(Instant.parse("2026-05-04T10:00:00Z"))
                .traceparent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                .baggage("owner=alice,cost-center=research-vision")
                .build();

        ProducerRecord<String, String> record = OutboxRelay.buildRecord("gwp.job.jobsubmitted", msg);

        String tp = headerAsString(record, "traceparent");
        String bg = headerAsString(record, "baggage");
        assertThat(tp).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertThat(bg).isEqualTo("owner=alice,cost-center=research-vision");
    }

    @Test
    void buildRecord_omitsHeadersWhenRowFieldsBlank() {
        OutboxMessage msg = OutboxMessage.builder()
                .id(UUID.randomUUID())
                .aggregateType("Job").aggregateId("job-2")
                .eventType("JobCompleted")
                .payload("{}")
                .createdAt(Instant.parse("2026-05-04T10:00:00Z"))
                .traceparent(null)
                .baggage(null)
                .build();

        ProducerRecord<String, String> record = OutboxRelay.buildRecord("gwp.job.jobcompleted", msg);

        // 두 헤더 모두 *반드시* 빠져 있어야 — null 값으로 채워 보내면 broker 단에서 파싱 사고.
        assertThat(record.headers().lastHeader("traceparent")).isNull();
        assertThat(record.headers().lastHeader("baggage")).isNull();
    }

    @Test
    void buildRecord_omitsBaggageWhenOnlyTraceparentPresent() {
        // V8 만 적용된 이전 row — traceparent 만 있고 baggage 컬럼이 NULL.
        OutboxMessage msg = OutboxMessage.builder()
                .id(UUID.randomUUID())
                .aggregateType("Job").aggregateId("job-3")
                .eventType("JobSubmitted")
                .payload("{}")
                .createdAt(Instant.parse("2026-05-04T10:00:00Z"))
                .traceparent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                .baggage(null)
                .build();

        ProducerRecord<String, String> record = OutboxRelay.buildRecord("gwp.job.jobsubmitted", msg);

        assertThat(headerAsString(record, "traceparent")).isNotNull();
        assertThat(record.headers().lastHeader("baggage")).isNull();
    }

    private static String headerAsString(ProducerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
