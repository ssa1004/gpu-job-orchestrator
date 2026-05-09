package com.example.gwp.orchestrator.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxWriterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock OutboxRepository outboxRepository;
    @Mock Tracer tracer;

    OutboxWriter writer;

    @BeforeEach
    void setUp() {
        writer = new OutboxWriter(outboxRepository, new ObjectMapper(), CLOCK, tracer);
    }

    @Test
    void write_persistsTypedEventAsJson() {
        var event = new JobEvent.JobSubmitted(
                "job-1", "alice", "engine:1.0", 2, "HIGH", "DISPATCHING", "trace-1");
        when(tracer.currentSpan()).thenReturn(null);   // 트레이서 비활성 케이스 — traceparent 빈 채로

        writer.write(event);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        OutboxMessage saved = captor.getValue();

        assertThat(saved.getAggregateType()).isEqualTo("Job");
        assertThat(saved.getAggregateId()).isEqualTo("job-1");
        assertThat(saved.getEventType()).isEqualTo("JobSubmitted");
        assertThat(saved.getCreatedAt()).isEqualTo(CLOCK.instant());
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getTraceparent()).isNull();
        // payload JSON 에 record 모든 필드가 들어감
        assertThat(saved.getPayload()).contains("\"jobId\":\"job-1\"")
                .contains("\"owner\":\"alice\"")
                .contains("\"priority\":\"HIGH\"");
    }

    @Test
    void write_supportsCompletedEvent() {
        var event = new JobEvent.JobCompleted(
                "job-2", "SUCCEEDED", "s3://b/o", "", "2026-05-04T10:30:00Z");
        when(tracer.currentSpan()).thenReturn(null);

        writer.write(event);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        OutboxMessage saved = captor.getValue();

        assertThat(saved.getEventType()).isEqualTo("JobCompleted");
        assertThat(saved.getPayload()).contains("\"status\":\"SUCCEEDED\"")
                .contains("\"resultUri\":\"s3://b/o\"");
    }

    /**
     * <b>traceparent 캡처 핵심</b>: 활성 span 의 (traceId, spanId, sampled) 가 W3C
     * 포맷으로 outbox row 에 보관되어야 한다 (RFC 9.5.1, 55자 고정).
     *
     * <p>예: traceId=0af7651916cd43dd8448eb211c80319c, spanId=b7ad6b7169203331, sampled=true
     * → "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"</p>
     */
    @Test
    void write_capturesActiveTraceContextAsW3cTraceparent() {
        var event = new JobEvent.JobSubmitted(
                "job-3", "bob", "trainer:2.0", 1, "NORMAL", "DISPATCHING", "trace-3");
        Span span = org.mockito.Mockito.mock(Span.class);
        TraceContext ctx = org.mockito.Mockito.mock(TraceContext.class);
        when(span.isNoop()).thenReturn(false);
        when(span.context()).thenReturn(ctx);
        when(ctx.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
        when(ctx.spanId()).thenReturn("b7ad6b7169203331");
        when(ctx.sampled()).thenReturn(Boolean.TRUE);
        when(tracer.currentSpan()).thenReturn(span);

        writer.write(event);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getTraceparent())
                .isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    }

    /** sampled=false 면 flags=00 으로 박힘 — consumer 가 같은 sampling 결정을 따라야 함. */
    @Test
    void write_traceparent_reflectsNotSampledFlag() {
        var event = new JobEvent.JobCompleted(
                "job-4", "FAILED", "", "OOM", "2026-05-04T10:00:00Z");
        Span span = org.mockito.Mockito.mock(Span.class);
        TraceContext ctx = org.mockito.Mockito.mock(TraceContext.class);
        when(span.isNoop()).thenReturn(false);
        when(span.context()).thenReturn(ctx);
        when(ctx.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
        when(ctx.spanId()).thenReturn("b7ad6b7169203331");
        when(ctx.sampled()).thenReturn(Boolean.FALSE);
        when(tracer.currentSpan()).thenReturn(span);

        writer.write(event);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getTraceparent()).endsWith("-00");
    }
}
