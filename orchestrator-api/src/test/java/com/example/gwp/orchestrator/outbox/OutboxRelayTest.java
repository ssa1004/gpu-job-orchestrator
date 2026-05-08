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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
    @Mock PlatformTransactionManager txManager;

    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = newRelay(3);   // 기본 max-attempts=3 (poison pill 케이스를 작게 잡아 검증 쉽게)
    }

    @SuppressWarnings("unchecked")
    private OutboxRelay newRelay(int maxAttempts) {
        var props = new GwpProperties(
                new GwpProperties.Kubernetes(false, "ns", 86400, "http://cb"),
                new GwpProperties.Storage(false, 3600),
                new GwpProperties.Callback("secret"),
                new GwpProperties.Security(new GwpProperties.Security.Jwt(false)),
                new GwpProperties.Outbox(new GwpProperties.Outbox.Relay(true, 1000, 100, 5000, "gwp.", maxAttempts)),
                new GwpProperties.Quota(10, 16)
        );
        KafkaTemplate<String, String> kt = kafkaTemplate;
        // TransactionTemplate 이 PlatformTransactionManager.getTransaction() 을 호출하므로
        // mock 이 빈 status 를 돌려주도록 설정. commit / rollback 도 호출되지만 noop.
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new OutboxRelay(outboxRepository, kt, CLOCK, props, txManager);
    }

    private OutboxMessage msg() {
        return msg("job-1");
    }

    private OutboxMessage msg(String aggregateId) {
        return OutboxMessage.builder()
                .id(UUID.randomUUID())
                .aggregateType("Job")
                .aggregateId(aggregateId)
                .eventType("JobSubmitted")
                .payload("{\"jobId\":\"" + aggregateId + "\"}")
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
        // 첫 실패는 attempt 카운터만 올리고 DLQ 격리 안 함
        verify(outboxRepository).recordAttemptFailure(eq(m.getId()), any(Instant.class), anyString());
        verify(outboxRepository, never()).markDeadLettered(any(), any(), anyString());
    }

    @Test
    void publishPending_noOpWhenEmpty() {
        when(outboxRepository.findUnpublished(any(PageRequest.class))).thenReturn(List.of());

        relay.publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(outboxRepository, never()).markPublished(any(), any());
    }

    /**
     * <b>poison pill 격리</b>: attempt_count 가 max-attempts-1 인 메시지가 다시 실패하면
     * (이번 attempt 까지 max 도달) DLQ 로 격리되어 다음 polling 에서 빠져야 한다.
     */
    @SuppressWarnings("unchecked")
    @Test
    void publishPending_deadLetters_afterMaxAttempts() {
        OutboxMessage m = msg();
        m.setAttemptCount(2);                                     // 다음 실패 = 3회 (max=3 도달)
        when(outboxRepository.findUnpublished(any(PageRequest.class))).thenReturn(List.of(m));
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.failedFuture(
                new ExecutionException("permanent", new RuntimeException("serializer error")));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);

        relay.publishPending();

        verify(outboxRepository, never()).markPublished(any(), any());
        verify(outboxRepository, never()).recordAttemptFailure(any(), any(), anyString());
        verify(outboxRepository).markDeadLettered(eq(m.getId()), any(Instant.class), anyString());
    }

    /**
     * 한 메시지가 격리되어도 같은 batch 의 다른 메시지는 정상 발행되어야 한다 — head-of-line
     * blocking 방지 검증.
     */
    @SuppressWarnings("unchecked")
    @Test
    void publishPending_quarantinedMessageDoesNotBlockOthers() {
        OutboxMessage poison = msg("job-poison");
        poison.setAttemptCount(2);                                // 다음 실패 = 3회 (max=3 도달)
        OutboxMessage healthy = msg("job-healthy");
        when(outboxRepository.findUnpublished(any(PageRequest.class))).thenReturn(List.of(poison, healthy));
        // poison topic 은 실패, healthy 는 성공. 둘 다 같은 topic 이므로 send 호출 순서로 구분.
        CompletableFuture<SendResult<String, String>> failFuture = CompletableFuture.failedFuture(
                new ExecutionException("permanent", new RuntimeException("serializer error")));
        CompletableFuture<SendResult<String, String>> okFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), eq(poison.getAggregateId()), eq(poison.getPayload())))
                .thenReturn(failFuture);
        when(kafkaTemplate.send(anyString(), eq(healthy.getAggregateId()), eq(healthy.getPayload())))
                .thenReturn(okFuture);

        relay.publishPending();

        verify(outboxRepository).markDeadLettered(eq(poison.getId()), any(Instant.class), anyString());
        verify(outboxRepository).markPublished(eq(healthy.getId()), any(Instant.class));
    }
}
