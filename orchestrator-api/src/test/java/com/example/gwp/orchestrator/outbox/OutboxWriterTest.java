package com.example.gwp.orchestrator.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock OutboxRepository outboxRepository;

    OutboxWriter writer;

    @BeforeEach
    void setUp() {
        writer = new OutboxWriter(outboxRepository, new ObjectMapper(), CLOCK);
    }

    @Test
    void write_persistsTypedEventAsJson() {
        var event = new JobEvent.JobSubmitted(
                "job-1", "alice", "engine:1.0", 2, "HIGH", "DISPATCHING", "trace-1");

        writer.write(event);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        OutboxMessage saved = captor.getValue();

        assertThat(saved.getAggregateType()).isEqualTo("Job");
        assertThat(saved.getAggregateId()).isEqualTo("job-1");
        assertThat(saved.getEventType()).isEqualTo("JobSubmitted");
        assertThat(saved.getCreatedAt()).isEqualTo(CLOCK.instant());
        assertThat(saved.getPublishedAt()).isNull();
        // payload JSON 에 record 모든 필드가 들어감
        assertThat(saved.getPayload()).contains("\"jobId\":\"job-1\"")
                .contains("\"owner\":\"alice\"")
                .contains("\"priority\":\"HIGH\"");
    }

    @Test
    void write_supportsCompletedEvent() {
        var event = new JobEvent.JobCompleted(
                "job-2", "SUCCEEDED", "s3://b/o", "", "2026-05-04T10:30:00Z");

        writer.write(event);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        OutboxMessage saved = captor.getValue();

        assertThat(saved.getEventType()).isEqualTo("JobCompleted");
        assertThat(saved.getPayload()).contains("\"status\":\"SUCCEEDED\"")
                .contains("\"resultUri\":\"s3://b/o\"");
    }
}
