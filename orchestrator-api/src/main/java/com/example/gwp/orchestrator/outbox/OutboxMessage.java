package com.example.gwp.orchestrator.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox 패턴: 도메인 트랜잭션 안에서 함께 INSERT 되어 이벤트 발행을 트랜잭션 안전하게 만든다.
 * OutboxRelay 가 published_at IS NULL 인 행을 polling 으로 Kafka 로 흘림.
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxMessage {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 128)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;
}
