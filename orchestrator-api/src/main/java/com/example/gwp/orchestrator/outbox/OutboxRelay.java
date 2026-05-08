package com.example.gwp.orchestrator.outbox;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import com.example.gwp.orchestrator.leader.LeaderElector;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox → Kafka relay. 1초마다 outbox 테이블을 polling 해서 미발행 메시지를 Kafka 로 흘린다.
 *
 * <h3>한 tick 의 흐름</h3>
 * <ol>
 *   <li>미발행 batch 를 짧은 read-only 트랜잭션에서 SELECT.</li>
 *   <li>각 메시지를 Kafka 로 동기 send (트랜잭션 밖).</li>
 *   <li>성공 건은 publishedAt UPDATE, 실패 건은 attempt 카운터 +1.</li>
 *   <li>attempt 가 max-attempts 도달 시 dead_lettered_at 으로 격리 (DLQ).</li>
 * </ol>
 *
 * <h3>왜 이렇게 쪼개나</h3>
 * <ul>
 *   <li><b>at-least-once 발행</b>. send 성공 후 markPublished commit 직전에 크래시 나면
 *       다음 polling 에서 같은 메시지를 또 발행한다. 컨슈머는 멱등성 가정 (이벤트 id 로
 *       dedup — 중복 제거).</li>
 *   <li><b>트랜잭션을 짧게 끊는 이유</b>. 예전에는 batch 전체를 {@code REQUIRES_NEW}
 *       한 트랜잭션으로 묶었더니 batch_size × send_timeout (예: 100 × 5s = 500초) 동안
 *       DB connection 을 점유할 수 있었음. 지금은 SELECT / UPDATE 만 짧은 트랜잭션,
 *       Kafka send 는 트랜잭션 밖 → connection 이 broker 응답 시간 동안 묶이지 않는다.</li>
 *   <li><b>동기 send</b>. {@code whenComplete} 같은 비동기 콜백은 별도 스레드에서
 *       실행되어 markPublished 시점에 트랜잭션 / 영속성 컨텍스트가 없다 → 안전하지 않음.
 *       {@code future.get(timeout)} 으로 동기 대기 후 별도 트랜잭션에서 markPublished.</li>
 *   <li><b>multi-instance 안전성 — ShedLock</b>. 여러 인스턴스가 같은 시각에 polling
 *       하면 같은 메시지를 두 번 발행할 수 있다. {@code @SchedulerLock} 으로 한 번에 한
 *       인스턴스만 메서드 진입을 보장 (DB 행 락 기반 — net.javacrumbs.shedlock).
 *       대안은 PG SKIP LOCKED (다른 트랜잭션이 잠근 row 를 건너뛰는 PG 기능) 인데,
 *       구현이 복잡하고 H2 dev 와 호환성 문제가 있어 ShedLock 채택.</li>
 *   <li><b>poison pill / DLQ</b>. 영구적으로 발행 실패하는 메시지 1건이 polling 큐의
 *       맨 앞을 막는 head-of-line blocking 을 막기 위해 attempt 카운터를 둔다.
 *       max-attempts 초과 시 dead_lettered_at 으로 격리 → 다음 polling 에서 skip.
 *       운영자가 페이로드를 보고 수동 재발행 또는 폐기.</li>
 *   <li><b>circuit breaker</b>. broker 가 죽으면 같은 tick 의 send 호출 하나하나가
 *       send_timeout 만큼 hang 한다. Resilience4j circuit 이 OPEN 으로 전이되면 후속
 *       호출이 즉시 fast-fail → tick 이 빠르게 끝나고 broker 회복 시 자동 복구.</li>
 * </ul>
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "gwp.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {

    /**
     * W3C trace context Kafka 헤더 이름. RFC 9.5.1. 표준 spec 이라 lower-case 가 컨벤션 —
     * Spring Cloud Stream / OpenTelemetry instrumentation / Confluent client 모두 동일.
     */
    static final String TRACEPARENT_HEADER = "traceparent";

    /** W3C baggage 헤더 (RFC 9.5.3). traceparent 와 같은 컨벤션 — 표준 lower-case. */
    static final String BAGGAGE_HEADER = "baggage";

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final GwpProperties properties;
    /** SELECT / UPDATE 마다 새 트랜잭션을 짧게 끊어 쓰기 위한 helper.
     *  {@code @Transactional} 을 self-invocation 으로 호출할 때 프록시가 빠져 트랜잭션이
     *  적용되지 않는 문제를 피하려고 명시적으로 사용. */
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    /**
     * Kafka broker 호출용 circuit breaker. broker 가 죽거나 응답 불능일 때 OPEN 으로 전이
     * → 같은 tick 의 나머지 send 호출은 즉시 fast-fail 로 변환되어 attempt 카운터만 한 번씩
     * 증가시킨다. broker 가 회복되면 HALF_OPEN → CLOSED 로 자동 복구. null 이면 (테스트
     * 등) 회로 없이 직접 send.
     */
    private final CircuitBreaker kafkaCircuit;
    /**
     * 다중 인스턴스 환경에서 *지금 이 인스턴스가 리더일 때만* 폴링하기 위한 게이트.
     * K8s Lease 모드면 Pod 1개만 true, 나머지는 false. ShedLock 모드면 항상 true (그 다음
     * 줄의 {@code @SchedulerLock} 가 직렬화 담당).
     */
    private final LeaderElector leaderElector;

    public OutboxRelay(OutboxRepository outboxRepository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       Clock clock,
                       GwpProperties properties,
                       PlatformTransactionManager txManager,
                       CircuitBreaker kafkaCircuit,
                       LeaderElector leaderElector) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.properties = properties;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(txManager);
        this.kafkaCircuit = kafkaCircuit;
        this.leaderElector = leaderElector;
    }

    /**
     * 매 tick 의 진입점. 트랜잭션 자체는 걸지 않고, SELECT / UPDATE 만 짧은 트랜잭션
     * 으로 감싼다. Kafka send 는 트랜잭션 밖에서 진행되어 DB connection 을 점유하지 않는다.
     */
    @Scheduled(fixedDelayString = "${gwp.outbox.relay.poll-interval-ms:1000}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT1M", lockAtLeastFor = "PT1S")
    public void publishPending() {
        // Leader election 게이트 — 비-리더 인스턴스는 매 tick 즉시 return.
        // K8s Lease 모드: Pod 한 개만 통과. ShedLock 모드: 모두 통과 후 ShedLock 이 직렬화.
        if (!leaderElector.isLeader()) return;

        var relay = properties.outbox().relay();
        List<OutboxMessage> batch = loadBatch(relay.batchSize());
        if (batch.isEmpty()) return;

        int published = 0;
        int deadLettered = 0;
        for (OutboxMessage msg : batch) {
            String failure = publishOne(msg);
            if (failure == null) {
                markPublishedTx(msg.getId());
                published++;
            } else {
                // 이 attempt 까지 합쳐 max 도달 시 격리, 아니면 실패 카운터만 올린다.
                int attemptsAfter = msg.getAttemptCount() + 1;
                if (attemptsAfter >= relay.maxAttempts()) {
                    markDeadLetteredTx(msg.getId(), failure);
                    deadLettered++;
                    log.error("outbox message dead-lettered id={} attempts={} reason={}",
                            msg.getId(), attemptsAfter, truncate(failure));
                } else {
                    recordAttemptFailureTx(msg.getId(), failure);
                }
            }
        }
        if (published > 0) {
            log.debug("outbox relay published {}/{}", published, batch.size());
        }
        if (deadLettered > 0) {
            log.warn("outbox relay dead-lettered {} message(s) in this tick", deadLettered);
        }
    }

    /** 미발행 batch 를 짧은 read-only 트랜잭션에서 가져온다. */
    List<OutboxMessage> loadBatch(int batchSize) {
        return readTx.execute(status ->
                outboxRepository.findUnpublished(PageRequest.of(0, batchSize)));
    }

    /** 한 메시지의 publishedAt 을 짧은 트랜잭션으로 UPDATE. send 성공 직후 호출. */
    void markPublishedTx(UUID id) {
        writeTx.executeWithoutResult(status ->
                outboxRepository.markPublished(id, clock.instant()));
    }

    /** 발행 실패 1회 — attempt 카운터 / lastError 를 짧은 트랜잭션으로 갱신. */
    void recordAttemptFailureTx(UUID id, String reason) {
        writeTx.executeWithoutResult(status ->
                outboxRepository.recordAttemptFailure(id, clock.instant(), truncate(reason)));
    }

    /** 임계 attempt 도달 — DLQ 로 격리. 이후 polling 에서 skip. */
    void markDeadLetteredTx(UUID id, String reason) {
        writeTx.executeWithoutResult(status ->
                outboxRepository.markDeadLettered(id, clock.instant(), truncate(reason)));
    }

    /**
     * 한 메시지를 발행 시도. 성공이면 null, 실패면 사유 문자열을 반환한다.
     *
     * <p>Kafka broker 호출은 circuit breaker 안에서 실행 — broker 가 죽었을 때 같은 tick
     * 의 후속 호출이 즉시 fast-fail 로 떨어져 hot-loop 가 된다. circuit OPEN 상태에서는
     * {@link CallNotPermittedException} 을 잡아서 일반 실패와 같은 형태로 반환 (DLQ 까지의
     * 정상 retry 로직 그대로 유지).</p>
     */
    private String publishOne(OutboxMessage msg) {
        var relay = properties.outbox().relay();
        String topic = relay.topicPrefix() + msg.getAggregateType().toLowerCase()
                + "." + msg.getEventType().toLowerCase();
        try {
            return invokeKafkaSendWithBreaker(msg, topic, relay.sendTimeoutMs());
        } catch (CallNotPermittedException e) {
            // OPEN — fast-fail. broker 가 회복되면 HALF_OPEN → CLOSED 로 자동 복구.
            log.warn("kafka circuit OPEN — skipping id={} topic={}", msg.getId(), topic);
            return "kafka circuit OPEN";
        }
    }

    /**
     * 실제 send + 결과 변환을 circuit breaker 안에서 실행. checked exception 들을
     * unchecked 로 wrap 해서 Resilience4j 의 callable 인터페이스에 맞춘다.
     *
     * <p><b>traceparent 헤더 복원</b>: outbox row 가 INSERT 된 시점 (T0) 의 W3C trace
     * context 를 그대로 Kafka {@code traceparent} 헤더에 박는다. consumer (worker /
     * callback) 가 이 헤더를 OTel propagator 로 추출 → 같은 trace 안에서 child span
     * 시작. row 의 traceparent 가 null 이면 (이전 row / 비-tracing 환경) 헤더 없이 send
     * 으로 backward-compatible 동작.</p>
     */
    private String invokeKafkaSendWithBreaker(OutboxMessage msg, String topic, long timeoutMs) {
        Runnable send = () -> {
            try {
                ProducerRecord<String, String> record = buildRecord(topic, msg);
                kafkaTemplate.send(record)
                        .get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new KafkaSendException("timeout after " + timeoutMs + "ms", e);
            } catch (ExecutionException e) {
                String reason = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                throw new KafkaSendException(reason != null ? reason : "ExecutionException", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KafkaSendException("interrupted", e);
            }
        };
        try {
            if (kafkaCircuit != null) {
                kafkaCircuit.executeRunnable(send);
            } else {
                send.run();
            }
            return null;
        } catch (KafkaSendException e) {
            log.warn("kafka publish failed id={} topic={} reason={}",
                    msg.getId(), topic, e.getMessage());
            return e.getMessage();
        }
    }

    /**
     * outbox row → Kafka {@link ProducerRecord} 변환. traceparent / baggage 가 row 에
     * 박혀 있으면 같은 이름 헤더로 복원. partition / timestamp 는 producer / broker 에
     * 위임 (null).
     *
     * <p>traceparent 와 baggage 는 W3C 가 정의한 *세트* — 함께 전파되어야 consumer 측에서
     * trace 와 도메인 컨텍스트가 같이 살아난다. 둘 다 nullable 이라 비활성 환경에서도 안전.</p>
     */
    static ProducerRecord<String, String> buildRecord(String topic, OutboxMessage msg) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic,
                null,                // partition — producer 가 key hash 로 결정
                null,                // timestamp — broker 가 채움
                msg.getAggregateId(),
                msg.getPayload()
        );
        String traceparent = msg.getTraceparent();
        if (traceparent != null && !traceparent.isBlank()) {
            record.headers().add(new RecordHeader(
                    TRACEPARENT_HEADER,
                    traceparent.getBytes(StandardCharsets.UTF_8)));
        }
        String baggage = msg.getBaggage();
        if (baggage != null && !baggage.isBlank()) {
            record.headers().add(new RecordHeader(
                    BAGGAGE_HEADER,
                    baggage.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    /** Kafka send 실패를 circuit breaker 가 카운트하도록 unchecked exception 으로 wrap. */
    private static final class KafkaSendException extends RuntimeException {
        KafkaSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** last_error 컬럼 길이 (2048) 를 넘지 않도록 자른다. */
    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 2048 ? s : s.substring(0, 2048);
    }
}
