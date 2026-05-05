package com.example.gwp.orchestrator.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * 도메인 서비스에서 호출. 호출 트랜잭션 안에서 outbox 테이블에 INSERT.
 *
 * <p>typed {@link JobEvent} 만 받음 — Map&lt;String, Object&gt; 같은 untyped payload 는 의도적으로
 * 받지 않아 컨슈머와의 contract 안정성을 강제.</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public void write(JobEvent event) {
        try {
            OutboxMessage msg = OutboxMessage.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("Job")
                    .aggregateId(event.aggregateId())
                    .eventType(event.type())
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(clock.instant())
                    .build();
            outboxRepository.save(msg);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload for " + event.type(), e);
        }
    }
}
