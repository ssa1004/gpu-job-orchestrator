package com.example.gwp.orchestrator.outbox;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import com.example.gwp.orchestrator.leader.AlwaysLeaderElector;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
                new GwpProperties.Quota(10, 16, false),
                new GwpProperties.Leader("shedlock", "gwp", "gwp-orchestrator-leader", "test", 15, 10, 2)
        );
        KafkaTemplate<String, String> kt = kafkaTemplate;
        // TransactionTemplate 이 PlatformTransactionManager.getTransaction() 을 호출하므로
        // mock 이 빈 status 를 돌려주도록 설정. commit / rollback 도 호출되지만 noop.
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // 단위 테스트는 circuit breaker 없이 실행 — null 이면 직접 send 로 fallback.
        // leader elector 는 단일 인스턴스 가정 (always leader) — leader 게이트 자체는
        // OutboxRelayLeaderGateTest 에서 별도 검증.
        return new OutboxRelay(outboxRepository, kt, CLOCK, props, txManager, null,
                new AlwaysLeaderElector());
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
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("gwp.job.jobsubmitted");
        assertThat(record.key()).isEqualTo("job-1");
        assertThat(record.value()).isEqualTo(m.getPayload());
        verify(outboxRepository).markPublished(eq(m.getId()), any(Instant.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishPending_doesNotMarkPublished_whenSendFails() {
        OutboxMessage m = msg();
        when(outboxRepository.findUnpublished(any(PageRequest.class))).thenReturn(List.of(m));
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.failedFuture(
                new ExecutionException("broker down", new RuntimeException("connection refused")));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        relay.publishPending();

        verify(outboxRepository, never()).markPublished(any(), any());
        // 첫 실패는 attempt 카운터만 올리고 DLQ 격리 안 함
        verify(outboxRepository).recordAttemptFailure(eq(m.getId()), any(Instant.class), any());
        verify(outboxRepository, never()).markDeadLettered(any(), any(), any());
    }

    @Test
    void publishPending_noOpWhenEmpty() {
        when(outboxRepository.findUnpublished(any(PageRequest.class))).thenReturn(List.of());

        relay.publishPending();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
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
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        relay.publishPending();

        verify(outboxRepository, never()).markPublished(any(), any());
        verify(outboxRepository, never()).recordAttemptFailure(any(), any(), any());
        verify(outboxRepository).markDeadLettered(eq(m.getId()), any(Instant.class), any());
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
        CompletableFuture<SendResult<String, String>> failFuture = CompletableFuture.failedFuture(
                new ExecutionException("permanent", new RuntimeException("serializer error")));
        CompletableFuture<SendResult<String, String>> okFuture = CompletableFuture.completedFuture(null);
        // record key 로 poison / healthy 구분.
        when(kafkaTemplate.send(argThat((ProducerRecord<String, String> r) ->
                r != null && poison.getAggregateId().equals(r.key())))).thenReturn(failFuture);
        when(kafkaTemplate.send(argThat((ProducerRecord<String, String> r) ->
                r != null && healthy.getAggregateId().equals(r.key())))).thenReturn(okFuture);

        relay.publishPending();

        verify(outboxRepository).markDeadLettered(eq(poison.getId()), any(Instant.class), any());
        verify(outboxRepository).markPublished(eq(healthy.getId()), any(Instant.class));
    }

    /**
     * <b>traceparent 헤더 복원</b> (ADR-0018): outbox row 에 박혀 있던 W3C trace context
     * 가 Kafka {@code traceparent} 헤더로 그대로 흘러야 한다 — consumer 가 같은 trace 안에서
     * child span 을 만들 수 있게.
     */
    @SuppressWarnings("unchecked")
    @Test
    void publishPending_propagatesTraceparentHeaderFromRow() {
        OutboxMessage m = msg();
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        m.setTraceparent(traceparent);
        when(outboxRepository.findUnpublished(any(PageRequest.class))).thenReturn(List.of(m));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        var header = captor.getValue().headers().lastHeader("traceparent");
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo(traceparent);
    }

    /** traceparent 가 row 에 없으면 (이전 row / 비-tracing 환경) 헤더 없이 그대로 send. */
    @SuppressWarnings("unchecked")
    @Test
    void publishPending_omitsHeader_whenTraceparentIsNull() {
        OutboxMessage m = msg();
        // traceparent 는 default null
        when(outboxRepository.findUnpublished(any(PageRequest.class))).thenReturn(List.of(m));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().headers().lastHeader("traceparent")).isNull();
    }
}
