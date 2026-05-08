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

    /**
     * W3C trace context — outbox row INSERT 시점 (T0) 의 trace 를 박제하여 publish
     * 시점 (T1) 에 Kafka 헤더로 복원. RFC 9.5.1 포맷 (version-traceId-spanId-flags, 55자).
     *
     * <p>이게 없으면 outbox 의 polling 스레드가 새 trace 를 만들어 — consumer (worker /
     * callback) 가 *원래 잡 제출 흐름* 과 분리된 trace 를 보게 되어 distributed trace
     * 가 끊긴다. ADR-0018 참고.</p>
     */
    @Column(name = "traceparent", length = 64)
    private String traceparent;

    /**
     * W3C baggage — INSERT 시점의 owner / cost-center / priority 등을 그대로 박제.
     * RFC 9.5.3 포맷 ({@code key1=val1,key2=val2}). traceparent 와 함께 Kafka 헤더로 복원.
     *
     * <p>traceparent 가 *어디서 왔는지* 만 알린다면, baggage 는 *지금 흐름이 누구의 것인지*
     * 라는 도메인 컨텍스트를 같이 옮긴다. consumer 측 metric / log / trace 의 라벨이 자동으로
     * owner 별로 split 가능. ADR-0021 참고.</p>
     *
     * <p>길이 한도: baggage 는 hop 마다 헤더로 직렬화되므로 cap 이 필수. 화이트리스트
     * (JobBaggage.ALLOWED) 외 키는 OutboxWriter 에서 drop, 값 길이도 entry 마다 cap.</p>
     */
    @Column(name = "baggage", length = 1024)
    private String baggage;
}
