package com.example.gwp.orchestrator.outbox;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox → Kafka relay. 짧은 주기(1s) 로 polling 해서 미발행 메시지를 Kafka 로 흘림.
 *
 * <p>설계 노트:</p>
 * <ul>
 *   <li><b>at-least-once</b> 발행. send 성공 후 markPublished 가 commit 되기 전 크래시 시
 *       다음 polling 에서 같은 메시지를 다시 발행. 컨슈머는 멱등성 가정 (event id 기반 dedup).</li>
 *   <li><b>동기 send + 트랜잭션 내 markPublished</b>. 콜백 기반(whenComplete)으로 markPublished 하면
 *       콜백이 트랜잭션 종료 후 비동기 실행되어 JPA 컨텍스트 만료 → 안전하지 않다 (이전 버그).
 *       지금은 {@code future.get(timeout)} 으로 동기 대기 후 같은 트랜잭션에서 markPublished 호출.</li>
 *   <li><b>단일 인스턴스 가정</b>. 여러 인스턴스 동시 polling 시 race → ShedLock 또는 PG SKIP LOCKED 필요.
 *       이 데모에서는 단일 leader 가 운영 모델.</li>
 *   <li><b>Poison pill 처리 미구현</b>. 영구 실패 메시지가 polling 큐를 막을 수 있음. Batch 백로그.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "gwp.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final GwpProperties properties;

    @Scheduled(fixedDelayString = "${gwp.outbox.relay.poll-interval-ms:1000}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishPending() {
        var relay = properties.outbox().relay();
        List<OutboxMessage> batch = outboxRepository.findUnpublished(PageRequest.of(0, relay.batchSize()));
        if (batch.isEmpty()) return;

        int published = 0;
        for (OutboxMessage msg : batch) {
            if (publishOne(msg)) {
                outboxRepository.markPublished(msg.getId(), clock.instant());
                published++;
            }
        }
        if (published > 0) {
            log.debug("outbox relay published {}/{}", published, batch.size());
        }
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
