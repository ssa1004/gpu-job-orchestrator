package com.example.gwp.orchestrator.config

import com.example.gwp.orchestrator.dlq.AdminRateLimiter
import com.example.gwp.orchestrator.dlq.DlqAuditLog
import com.example.gwp.orchestrator.dlq.DlqBulkJobRepository
import com.example.gwp.orchestrator.dlq.DlqMessageStore
import com.example.gwp.orchestrator.dlq.InMemoryDlqBulkJobRepository
import com.example.gwp.orchestrator.dlq.InMemoryDlqMessageStore
import com.example.gwp.orchestrator.dlq.KafkaDlqMessageStore
import com.example.gwp.orchestrator.dlq.NoopAdminRateLimiter
import com.example.gwp.orchestrator.dlq.RedisAdminRateLimiter
import com.example.gwp.orchestrator.dlq.Slf4jDlqAuditLog
import com.example.gwp.orchestrator.outbox.OutboxRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * DLQ admin 콘솔 백엔드의 bean wiring.
 *
 * 기본 (dev / 단위 테스트):
 *  - [InMemoryDlqMessageStore], [NoopAdminRateLimiter], [InMemoryDlqBulkJobRepository],
 *    [Slf4jDlqAuditLog].
 *
 * `gwp.dlq.admin.use-kafka-store=true` + outboxRepository 존재 시:
 *  - [KafkaDlqMessageStore] — outbox dead-letter row 를 OUTBOX source 로 노출.
 *
 * `gwp.dlq.admin.rate-limiter=redis` + StringRedisTemplate 존재 시:
 *  - [RedisAdminRateLimiter] — atomic Lua + scope 별 분리 카운터.
 *
 * 단일 모듈 + Kotlin only — bean wiring 도 Kotlin 으로 유지.
 */
@Configuration
class DlqAdminConfig {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = ["gwp.dlq.admin.use-kafka-store"], havingValue = "false", matchIfMissing = true)
    open fun inMemoryDlqMessageStore(): DlqMessageStore = InMemoryDlqMessageStore()

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = ["gwp.dlq.admin.use-kafka-store"], havingValue = "true")
    open fun kafkaDlqMessageStore(
        outboxRepositoryProvider: ObjectProvider<OutboxRepository>,
        clock: Clock,
    ): DlqMessageStore {
        val outboxRepository = outboxRepositoryProvider.getIfAvailable()
            ?: return InMemoryDlqMessageStore()
        return KafkaDlqMessageStore(outboxRepository, clock)
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(StringRedisTemplate::class)
    @ConditionalOnProperty(name = ["gwp.dlq.admin.rate-limiter"], havingValue = "redis")
    open fun redisAdminRateLimiter(
        redisProvider: ObjectProvider<StringRedisTemplate>,
    ): AdminRateLimiter {
        val redis = redisProvider.getIfAvailable() ?: return NoopAdminRateLimiter()
        return RedisAdminRateLimiter(redis, RATE_LIMIT_PER_MINUTE)
    }

    @Bean
    @ConditionalOnMissingBean(AdminRateLimiter::class)
    open fun noopAdminRateLimiter(): AdminRateLimiter = NoopAdminRateLimiter()

    @Bean
    @ConditionalOnMissingBean
    open fun dlqBulkJobRepository(): DlqBulkJobRepository = InMemoryDlqBulkJobRepository()

    @Bean
    @ConditionalOnMissingBean
    open fun dlqAuditLog(): DlqAuditLog = Slf4jDlqAuditLog()

    /**
     * bulk 작업용 별도 executor. 한 콘솔 요청이 비동기로 떨어지면 이 풀에서 처리. controller
     * 의 응답 latency 가 bulk 의 크기와 무관해진다.
     */
    @Bean("dlqBulkExecutor")
    @ConditionalOnMissingBean(name = ["dlqBulkExecutor"])
    open fun dlqBulkExecutor(): Executor = Executors.newFixedThreadPool(
        BULK_EXECUTOR_POOL,
        object : ThreadFactory {
            private val counter = AtomicLong(0)
            override fun newThread(r: Runnable): Thread =
                Thread(r, "dlq-bulk-${counter.incrementAndGet()}").apply { isDaemon = true }
        },
    )

    companion object {
        // application.yml 의 gwp.dlq.admin.rate-limit-per-minute 와 호응. yml 누락 시 60.
        private const val RATE_LIMIT_PER_MINUTE = 60

        // bulk 동시 처리 슬롯. 운영 콘솔 동시 사용자 가정 ~2, 안전 여유 두 배.
        private const val BULK_EXECUTOR_POOL = 4
    }
}
