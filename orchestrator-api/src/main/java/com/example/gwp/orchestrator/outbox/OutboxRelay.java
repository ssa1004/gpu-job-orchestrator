package com.example.gwp.orchestrator.outbox;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox → Kafka relay. 짧은 주기(1s) 로 polling 해서 outbox 테이블의 미발행 메시지를
 * Kafka 로 흘림.
 *
 * <p>설계 노트:</p>
 * <ul>
 *   <li><b>at-least-once</b> (최소 한 번은 보장하지만 두 번 갈 수도 있는) 발행. send 성공
 *       후 markPublished 가 commit 되기 전 크래시 시 다음 polling 에서 같은 메시지를
 *       다시 발행. 컨슈머는 멱등성 가정 (이벤트 id 기반 dedup — 중복 제거).</li>
 *   <li><b>트랜잭션 분리 — 짧게 끊어 쓰기</b>. 예전엔 {@code REQUIRES_NEW} 로 batch 전체를
 *       한 트랜잭션 안에서 처리해 batch_size × send_timeout (예: 100 × 5s = 500s) 동안
 *       DB connection 을 점유할 수 있었음. 지금은 (1) 미발행 batch 만 짧게 SELECT →
 *       (2) Kafka 동기 send (트랜잭션 밖) → (3) 성공 건만 짧게 UPDATE 로 분리.
 *       DB connection 이 Kafka 발행 시간 동안 묶이지 않는다.</li>
 *   <li><b>동기 send + 콜백 기반 비동기 금지</b>. {@code whenComplete} 콜백은 별도 스레드에서
 *       실행되어 markPublished 시점에 트랜잭션 / 영속성 컨텍스트가 없어 안전하지 않다.
 *       {@code future.get(timeout)} 으로 동기 대기 후 별도 트랜잭션에서 markPublished.</li>
 *   <li><b>단일 인스턴스 가정</b>. 여러 인스턴스가 동시에 polling 하면 같은 메시지를 두
 *       번 발행할 수 있음 → ShedLock (DB 행 락 등으로 한 번에 한 인스턴스만 스케줄러를
 *       돌리도록 보장하는 라이브러리) 또는 PG SKIP LOCKED (다른 트랜잭션이 잠근 row 는
 *       건너뛰는 PG 기능) 필요. 이 데모에서는 단일 leader 가 운영 모델.</li>
 *   <li><b>poison pill 처리 미구현</b>. poison pill = 영구적으로 발행 실패하는 메시지 1건.
 *       이 메시지가 polling 큐를 막아 뒤 메시지까지 발행 못 하게 될 수 있음. 별도 백로그.</li>
 * </ul>
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "gwp.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final GwpProperties properties;
    /** SELECT / UPDATE 마다 새 트랜잭션을 짧게 끊어 쓰기 위한 helper.
     *  {@code @Transactional} 을 self-invocation 으로 호출할 때 프록시가 빠져 트랜잭션이
     *  적용되지 않는 문제를 피하려고 명시적으로 사용. */
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public OutboxRelay(OutboxRepository outboxRepository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       Clock clock,
                       GwpProperties properties,
                       PlatformTransactionManager txManager) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.properties = properties;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(txManager);
    }

    /**
     * 매 tick 의 진입점. 트랜잭션 자체는 걸지 않고, SELECT / UPDATE 만 짧은 트랜잭션
     * 으로 감싼다. Kafka send 는 트랜잭션 밖에서 진행되어 DB connection 을 점유하지 않는다.
     */
    @Scheduled(fixedDelayString = "${gwp.outbox.relay.poll-interval-ms:1000}")
    public void publishPending() {
        var relay = properties.outbox().relay();
        List<OutboxMessage> batch = loadBatch(relay.batchSize());
        if (batch.isEmpty()) return;

        int published = 0;
        for (OutboxMessage msg : batch) {
            if (publishOne(msg)) {
                markPublishedTx(msg.getId());
                published++;
            }
        }
        if (published > 0) {
            log.debug("outbox relay published {}/{}", published, batch.size());
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

    private boolean publishOne(OutboxMessage msg) {
        var relay = properties.outbox().relay();
        String topic = relay.topicPrefix() + msg.getAggregateType().toLowerCase()
                + "." + msg.getEventType().toLowerCase();
        try {
            kafkaTemplate.send(topic, msg.getAggregateId(), msg.getPayload())
                    .get(relay.sendTimeoutMs(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            log.warn("kafka publish timeout id={} topic={} timeoutMs={}",
                    msg.getId(), topic, relay.sendTimeoutMs());
            return false;
        } catch (ExecutionException e) {
            log.warn("kafka publish failed id={} topic={} reason={}",
                    msg.getId(), topic, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("kafka publish interrupted id={}", msg.getId());
            return false;
        }
    }
}
