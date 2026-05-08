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

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * 발행 시도 횟수. 매 실패마다 1 증가. 임계치 초과 시 {@link #deadLetteredAt} 으로 격리.
     */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** 마지막 실패 사유 (운영 진단용). */
    @Column(name = "last_error", length = 2048)
    private String lastError;

    /**
     * DLQ 격리 시각 — null 이면 살아있는 메시지, NOT NULL 이면 더 이상 발행하지 않음.
     * 격리된 메시지는 운영자가 페이로드를 보고 수동 재발행 또는 폐기한다.
     */
    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;
}
