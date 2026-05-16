package com.example.gwp.orchestrator.outbox

import com.example.gwp.orchestrator.config.properties.GwpProperties
import com.example.gwp.orchestrator.leader.LeaderElector
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Outbox → Kafka relay. 1초마다 outbox 테이블을 polling 해서 미발행 메시지를 Kafka 로 흘린다.
 *
 * ### 한 tick 의 흐름
 * 1. 미발행 batch 를 짧은 read-only 트랜잭션에서 SELECT.
 * 2. 각 메시지를 Kafka 로 동기 send (트랜잭션 밖).
 * 3. 성공 건은 publishedAt UPDATE, 실패 건은 attempt 카운터 +1.
 * 4. attempt 가 max-attempts 도달 시 dead_lettered_at 으로 격리 (DLQ).
 *
 * ### 왜 이렇게 쪼개나
 * - **at-least-once 발행**. send 성공 후 markPublished commit 직전에 크래시 나면
 *   다음 polling 에서 같은 메시지를 또 발행한다. 컨슈머는 멱등성 가정 (이벤트 id 로
 *   dedup — 중복 제거).
 * - **트랜잭션을 짧게 끊는 이유**. 예전에는 batch 전체를 `REQUIRES_NEW`
 *   한 트랜잭션으로 묶었더니 batch_size × send_timeout (예: 100 × 5s = 500초) 동안
 *   DB connection 을 점유할 수 있었음. 지금은 SELECT / UPDATE 만 짧은 트랜잭션,
 *   Kafka send 는 트랜잭션 밖 → connection 이 broker 응답 시간 동안 묶이지 않는다.
 * - **동기 send**. `whenComplete` 같은 비동기 콜백은 별도 스레드에서
 *   실행되어 markPublished 시점에 트랜잭션 / 영속성 컨텍스트가 없다 → 안전하지 않음.
 *   `future.get(timeout)` 으로 동기 대기 후 별도 트랜잭션에서 markPublished.
 * - **multi-instance 안전성 — ShedLock**. 여러 인스턴스가 같은 시각에 polling
 *   하면 같은 메시지를 두 번 발행할 수 있다. `@SchedulerLock` 으로 한 번에 한
 *   인스턴스만 메서드 진입을 보장 (DB 행 락 기반 — net.javacrumbs.shedlock).
 *   대안은 PG SKIP LOCKED (다른 트랜잭션이 잠근 row 를 건너뛰는 PG 기능) 인데,
 *   구현이 복잡하고 H2 dev 와 호환성 문제가 있어 ShedLock 채택.
 * - **poison pill / DLQ**. 영구적으로 발행 실패하는 메시지 1건이 polling 큐의
 *   맨 앞을 막는 head-of-line blocking 을 막기 위해 attempt 카운터를 둔다.
 *   max-attempts 초과 시 dead_lettered_at 으로 격리 → 다음 polling 에서 skip.
 *   운영자가 페이로드를 보고 수동 재발행 또는 폐기.
 * - **circuit breaker**. broker 가 죽으면 같은 tick 의 send 호출 하나하나가
 *   send_timeout 만큼 hang 한다. Resilience4j circuit 이 OPEN 으로 전이되면 후속
 *   호출이 즉시 fast-fail → tick 이 빠르게 끝나고 broker 회복 시 자동 복구.
 *
 * Java 호출자 (`new OutboxRelay(...)` / `OutboxRelay.buildRecord(...)`) 그대로 동작 —
 * Kotlin primary constructor 가 같은 positional 시그니처, companion `buildRecord` 는
 * `@JvmStatic` 으로 static 시그니처 보존. `plugin.spring` 이 `@Component` 자동 open.
 */
@Component
@ConditionalOnProperty(name = ["gwp.outbox.relay.enabled"], havingValue = "true")
class OutboxRelay(
    private val outboxRepository: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val clock: Clock,
    private val properties: GwpProperties,
    txManager: PlatformTransactionManager,
    /**
     * Kafka broker 호출용 circuit breaker. broker 가 죽거나 응답 불능일 때 OPEN 으로 전이
     * → 같은 tick 의 나머지 send 호출은 즉시 fast-fail 로 변환되어 attempt 카운터만 한 번씩
     * 증가시킨다. broker 가 회복되면 HALF_OPEN → CLOSED 로 자동 복구. null 이면 (테스트
     * 등) 회로 없이 직접 send.
     */
    private val kafkaCircuit: CircuitBreaker?,
    /**
     * 다중 인스턴스 환경에서 *지금 이 인스턴스가 리더일 때만* 폴링하기 위한 게이트.
     * K8s Lease 모드면 Pod 1개만 true, 나머지는 false. ShedLock 모드면 항상 true (그 다음
     * 줄의 `@SchedulerLock` 가 직렬화 담당).
     */
    private val leaderElector: LeaderElector,
) {

    /** SELECT / UPDATE 마다 새 트랜잭션을 짧게 끊어 쓰기 위한 helper.
     *  `@Transactional` 을 self-invocation 으로 호출할 때 프록시가 빠져 트랜잭션이
     *  적용되지 않는 문제를 피하려고 명시적으로 사용. */
    private val readTx: TransactionTemplate = TransactionTemplate(txManager).apply { isReadOnly = true }
    private val writeTx: TransactionTemplate = TransactionTemplate(txManager)

    /**
     * 매 tick 의 진입점. 트랜잭션 자체는 걸지 않고, SELECT / UPDATE 만 짧은 트랜잭션
     * 으로 감싼다. Kafka send 는 트랜잭션 밖에서 진행되어 DB connection 을 점유하지 않는다.
     */
    @Scheduled(fixedDelayString = "\${gwp.outbox.relay.poll-interval-ms:1000}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT1M", lockAtLeastFor = "PT1S")
    open fun publishPending() {
        // Leader election 게이트 — 비-리더 인스턴스는 매 tick 즉시 return.
        // K8s Lease 모드: Pod 한 개만 통과. ShedLock 모드: 모두 통과 후 ShedLock 이 직렬화.
        if (!leaderElector.isLeader) return

        val relay = properties.outbox().relay()
        val batch = loadBatch(relay.batchSize())
        if (batch.isEmpty()) return

        var published = 0
        var deadLettered = 0
        for (msg in batch) {
            val failure = publishOne(msg)
            if (failure == null) {
                markPublishedTx(msg.id!!)
                published++
            } else {
                // 이 attempt 까지 합쳐 max 도달 시 격리, 아니면 실패 카운터만 올린다.
                val attemptsAfter = msg.attemptCount + 1
                if (attemptsAfter >= relay.maxAttempts()) {
                    markDeadLetteredTx(msg.id!!, failure)
                    deadLettered++
                    log.error(
                        "outbox message dead-lettered id={} attempts={} reason={}",
                        msg.id, attemptsAfter, truncate(failure),
                    )
                } else {
                    recordAttemptFailureTx(msg.id!!, failure)
                }
            }
        }
        if (published > 0) {
            log.debug("outbox relay published {}/{}", published, batch.size)
        }
        if (deadLettered > 0) {
            log.warn("outbox relay dead-lettered {} message(s) in this tick", deadLettered)
        }
    }

    /** 미발행 batch 를 짧은 read-only 트랜잭션에서 가져온다. */
    fun loadBatch(batchSize: Int): List<OutboxMessage> =
        readTx.execute { _ ->
            outboxRepository.findUnpublished(PageRequest.of(0, batchSize))
        } ?: emptyList()

    /** 한 메시지의 publishedAt 을 짧은 트랜잭션으로 UPDATE. send 성공 직후 호출. */
    fun markPublishedTx(id: UUID) {
        writeTx.executeWithoutResult { _ ->
            outboxRepository.markPublished(id, clock.instant())
        }
    }

    /** 발행 실패 1회 — attempt 카운터 / lastError 를 짧은 트랜잭션으로 갱신. */
    fun recordAttemptFailureTx(id: UUID, reason: String) {
        writeTx.executeWithoutResult { _ ->
            outboxRepository.recordAttemptFailure(id, clock.instant(), truncate(reason))
        }
    }

    /** 임계 attempt 도달 — DLQ 로 격리. 이후 polling 에서 skip. */
    fun markDeadLetteredTx(id: UUID, reason: String) {
        writeTx.executeWithoutResult { _ ->
            outboxRepository.markDeadLettered(id, clock.instant(), truncate(reason))
        }
    }

    /**
     * 한 메시지를 발행 시도. 성공이면 null, 실패면 사유 문자열을 반환한다.
     *
     * Kafka broker 호출은 circuit breaker 안에서 실행 — broker 가 죽었을 때 같은 tick
     * 의 후속 호출이 즉시 fast-fail 로 떨어져 hot-loop 가 된다. circuit OPEN 상태에서는
     * [CallNotPermittedException] 을 잡아서 일반 실패와 같은 형태로 반환 (DLQ 까지의
     * 정상 retry 로직 그대로 유지).
     */
    private fun publishOne(msg: OutboxMessage): String? {
        val relay = properties.outbox().relay()
        val topic = relay.topicPrefix() + msg.aggregateType!!.lowercase() +
            "." + msg.eventType!!.lowercase()
        return try {
            invokeKafkaSendWithBreaker(msg, topic, relay.sendTimeoutMs())
        } catch (e: CallNotPermittedException) {
            // OPEN — fast-fail. broker 가 회복되면 HALF_OPEN → CLOSED 로 자동 복구.
            log.warn("kafka circuit OPEN — skipping id={} topic={}", msg.id, topic)
            "kafka circuit OPEN"
        }
    }

    /**
     * 실제 send + 결과 변환을 circuit breaker 안에서 실행. checked exception 들을
     * unchecked 로 wrap 해서 Resilience4j 의 callable 인터페이스에 맞춘다.
     *
     * **traceparent 헤더 복원**: outbox row 가 INSERT 된 시점 (T0) 의 W3C trace
     * context 를 그대로 Kafka `traceparent` 헤더에 박는다. consumer (worker /
     * callback) 가 이 헤더를 OTel propagator 로 추출 → 같은 trace 안에서 child span
     * 시작. row 의 traceparent 가 null 이면 (이전 row / 비-tracing 환경) 헤더 없이 send
     * 으로 backward-compatible 동작.
     */
    private fun invokeKafkaSendWithBreaker(msg: OutboxMessage, topic: String, timeoutMs: Long): String? {
        val send = Runnable {
            try {
                val record = buildRecord(topic, msg)
                kafkaTemplate.send(record).get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw KafkaSendException("timeout after ${timeoutMs}ms", e)
            } catch (e: ExecutionException) {
                val reason = e.cause?.message ?: e.message
                throw KafkaSendException(reason ?: "ExecutionException", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw KafkaSendException("interrupted", e)
            }
        }
        return try {
            if (kafkaCircuit != null) {
                kafkaCircuit.executeRunnable(send)
            } else {
                send.run()
            }
            null
        } catch (e: KafkaSendException) {
            log.warn(
                "kafka publish failed id={} topic={} reason={}",
                msg.id, topic, e.message,
            )
            e.message
        }
    }

    /** Kafka send 실패를 circuit breaker 가 카운트하도록 unchecked exception 으로 wrap. */
    private class KafkaSendException(message: String, cause: Throwable) : RuntimeException(message, cause)

    companion object {

        private val log = LoggerFactory.getLogger(OutboxRelay::class.java)

        /**
         * W3C trace context Kafka 헤더 이름. RFC 9.5.1. 표준 spec 이라 lower-case 가 컨벤션 —
         * Spring Cloud Stream / OpenTelemetry instrumentation / Confluent client 모두 동일.
         */
        const val TRACEPARENT_HEADER = "traceparent"

        /** W3C baggage 헤더 (RFC 9.5.3). traceparent 와 같은 컨벤션 — 표준 lower-case. */
        const val BAGGAGE_HEADER = "baggage"

        /**
         * outbox row → Kafka [ProducerRecord] 변환. traceparent / baggage 가 row 에
         * 박혀 있으면 같은 이름 헤더로 복원. partition / timestamp 는 producer / broker 에
         * 위임 (null).
         *
         * traceparent 와 baggage 는 W3C 가 정의한 *세트* — 함께 전파되어야 consumer 측에서
         * trace 와 도메인 컨텍스트가 같이 살아난다. 둘 다 nullable 이라 비활성 환경에서도 안전.
         */
        @JvmStatic
        fun buildRecord(topic: String, msg: OutboxMessage): ProducerRecord<String, String> {
            val record = ProducerRecord<String, String>(
                topic,
                null,                // partition — producer 가 key hash 로 결정
                null,                // timestamp — broker 가 채움
                msg.aggregateId,
                msg.payload,
            )
            val traceparent = msg.traceparent
            if (!traceparent.isNullOrBlank()) {
                record.headers().add(
                    RecordHeader(
                        TRACEPARENT_HEADER,
                        traceparent.toByteArray(StandardCharsets.UTF_8),
                    ),
                )
            }
            val baggage = msg.baggage
            if (!baggage.isNullOrBlank()) {
                record.headers().add(
                    RecordHeader(
                        BAGGAGE_HEADER,
                        baggage.toByteArray(StandardCharsets.UTF_8),
                    ),
                )
            }
            return record
        }

        /** last_error 컬럼 길이 (2048) 를 넘지 않도록 자른다. */
        @JvmStatic
        fun truncate(s: String?): String? {
            if (s == null) return null
            return if (s.length <= 2048) s else s.substring(0, 2048)
        }
    }
}
