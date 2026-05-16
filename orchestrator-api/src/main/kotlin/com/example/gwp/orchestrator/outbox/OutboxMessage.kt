package com.example.gwp.orchestrator.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Outbox 패턴: 도메인 트랜잭션 안에서 함께 INSERT 되어 이벤트 발행을 트랜잭션 안전하게 만든다.
 * OutboxRelay 가 published_at IS NULL 인 행을 polling 으로 Kafka 로 흘림.
 *
 * Java 호출자 (`OutboxMessage.builder().id(...).build()` Lombok-스타일) 그대로 동작 —
 * 직접 hand-written [Builder] 가 같은 fluent API 노출. setter / getter 도 같은 이름
 * (`getId()` / `setId(...)`) — Kotlin 컴파일러가 `var xxx` 에 합성. `kotlin-jpa` 가
 * JPA 가 요구하는 no-arg 생성자를 합성한다.
 */
@Entity
@Table(name = "outbox")
class OutboxMessage(
    @Id
    @Column(name = "id")
    var id: UUID? = null,

    @Column(name = "aggregate_type", nullable = false, length = 128)
    var aggregateType: String? = null,

    @Column(name = "aggregate_id", nullable = false, length = 128)
    var aggregateId: String? = null,

    @Column(name = "event_type", nullable = false, length = 128)
    var eventType: String? = null,

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    var payload: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    /**
     * 발행 시도 횟수. 매 실패마다 1 증가. 임계치 초과 시 [deadLetteredAt] 으로 격리.
     */
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "last_attempt_at")
    var lastAttemptAt: Instant? = null,

    /** 마지막 실패 사유 (운영 진단용). */
    @Column(name = "last_error", length = 2048)
    var lastError: String? = null,

    /**
     * DLQ 격리 시각 — null 이면 살아있는 메시지, NOT NULL 이면 더 이상 발행하지 않음.
     * 격리된 메시지는 운영자가 페이로드를 보고 수동 재발행 또는 폐기한다.
     */
    @Column(name = "dead_lettered_at")
    var deadLetteredAt: Instant? = null,

    /**
     * W3C trace context — outbox row INSERT 시점 (T0) 의 trace 를 그대로 보관해서 publish
     * 시점 (T1) 에 Kafka 헤더로 복원. RFC 9.5.1 포맷 (version-traceId-spanId-flags, 55자).
     *
     * 이 값이 없으면 outbox polling 스레드가 새 trace 를 만들어, consumer (worker /
     * callback) 가 원래 잡 제출 흐름과 분리된 trace 를 보게 된다. distributed trace 가
     * 끊긴다. ADR-0018 참고.
     */
    @Column(name = "traceparent", length = 64)
    var traceparent: String? = null,

    /**
     * W3C baggage — INSERT 시점의 owner / cost-center / priority 등을 그대로 보관.
     * RFC 9.5.3 포맷 (`key1=val1,key2=val2`). traceparent 와 함께 Kafka 헤더로 복원.
     *
     * traceparent 가 어디서 왔는지를 알린다면, baggage 는 지금 흐름이 누구의 것인지에
     * 해당하는 도메인 컨텍스트를 같이 옮긴다. consumer 측 metric / log / trace 의 라벨이
     * 자동으로 owner 별로 split 가능. ADR-0021 참고.
     *
     * 길이 한도: baggage 는 hop 마다 헤더로 직렬화되므로 cap 이 필수. 화이트리스트
     * (JobBaggage.ALLOWED) 외 키는 OutboxWriter 에서 drop, 값 길이도 entry 마다 cap.
     */
    @Column(name = "baggage", length = 1024)
    var baggage: String? = null,
) {

    /**
     * Lombok `@Builder` 호환 fluent builder. Java 호출자: `OutboxMessage.builder().id(id)
     * .aggregateType(...).build()` 그대로 동작.
     */
    class Builder internal constructor() {
        private var id: UUID? = null
        private var aggregateType: String? = null
        private var aggregateId: String? = null
        private var eventType: String? = null
        private var payload: String? = null
        private var createdAt: Instant? = null
        private var publishedAt: Instant? = null
        private var attemptCount: Int = 0
        private var lastAttemptAt: Instant? = null
        private var lastError: String? = null
        private var deadLetteredAt: Instant? = null
        private var traceparent: String? = null
        private var baggage: String? = null

        fun id(id: UUID?) = apply { this.id = id }
        fun aggregateType(aggregateType: String?) = apply { this.aggregateType = aggregateType }
        fun aggregateId(aggregateId: String?) = apply { this.aggregateId = aggregateId }
        fun eventType(eventType: String?) = apply { this.eventType = eventType }
        fun payload(payload: String?) = apply { this.payload = payload }
        fun createdAt(createdAt: Instant?) = apply { this.createdAt = createdAt }
        fun publishedAt(publishedAt: Instant?) = apply { this.publishedAt = publishedAt }
        fun attemptCount(attemptCount: Int) = apply { this.attemptCount = attemptCount }
        fun lastAttemptAt(lastAttemptAt: Instant?) = apply { this.lastAttemptAt = lastAttemptAt }
        fun lastError(lastError: String?) = apply { this.lastError = lastError }
        fun deadLetteredAt(deadLetteredAt: Instant?) = apply { this.deadLetteredAt = deadLetteredAt }
        fun traceparent(traceparent: String?) = apply { this.traceparent = traceparent }
        fun baggage(baggage: String?) = apply { this.baggage = baggage }

        fun build(): OutboxMessage = OutboxMessage(
            id = id,
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            eventType = eventType,
            payload = payload,
            createdAt = createdAt,
            publishedAt = publishedAt,
            attemptCount = attemptCount,
            lastAttemptAt = lastAttemptAt,
            lastError = lastError,
            deadLetteredAt = deadLetteredAt,
            traceparent = traceparent,
            baggage = baggage,
        )
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
