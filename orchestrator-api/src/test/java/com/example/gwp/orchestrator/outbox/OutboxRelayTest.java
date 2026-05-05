package com.example.gwp.orchestrator.outbox;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock OutboxRepository outboxRepository;
    @Mock @SuppressWarnings("rawtypes") KafkaTemplate kafkaTemplate;

    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        var props = new GwpProperties(
                new GwpProperties.Kubernetes(false, "ns", 86400, "http://cb"),
                new GwpProperties.Storage(false, 3600),
                new GwpProperties.Callback("secret"),
                new GwpProperties.Security(new GwpProperties.Security.Jwt(false)),
                new GwpProperties.Outbox(new GwpProperties.Outbox.Relay(true, 1000, 100, 5000, "gwp.")),
                new GwpProperties.Quota(10, 16)
        );
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kt = kafkaTemplate;
        relay = new OutboxRelay(outboxRepository, kt, CLOCK, props);
    }

    private OutboxMessage msg() {
        return OutboxMessage.builder()
                .id(UUID.randomUUID())
                .aggregateType("Job")
                .aggregateId("job-1")
                .eventType("JobSubmitted")
                .payload("{\"jobId\":\"job-1\"}")
                .createdAt(CLOCK.instant())
                .build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishPending_sendsToKafkaAndMarksPublished() {
        OutboxMessage m = msg();
        when(outboxRepository.findUnpublished(any(PageRequest.class))).thenReturn(List.of(m));
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);

        relay.publishPending();

        verify(kafkaTemplate).send(eq("gwp.job.jobsubmitted"), eq("job-1"), eq(m.getPayload()));
        verify(outboxRepository).markPublished(eq(m.getId()), any(Instant.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishPending_doesNotMarkPublished_whenSendFails() {
        OutboxMessage m = msg();
        when(outboxRepository.findUnpublished(any(PageRequest.class))).thenReturn(List.of(m));
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.failedFuture(
                new ExecutionException("broker down", new RuntimeException("connection refused")));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);

        relay.publishPending();

        verify(outboxRepository, never()).markPublished(any(), any());
    }

    @Test
    void publishPending_noOpWhenEmpty() {
        when(outboxRepository.findUnpublished(any(PageRequest.class))).thenReturn(List.of());

        relay.publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(outboxRepository, never()).markPublished(any(), any());
    }
}
