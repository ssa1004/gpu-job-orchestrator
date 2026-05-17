package com.example.gwp.orchestrator.dlq

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration

/**
 * Redis Lua 기반 token bucket. scope 별로 분리된 키 — read / write / bulk 가 따로
 * 카운트되어 read 가 폭발해도 write 가 살아남는다. atomic INCR + EXPIRE 로 race-free.
 *
 * 키 포맷: `admin:dlq:<scope>:<actor>`. actor 는 IP 또는 admin sub claim (콘솔에서
 * 어떤 운영자가 한도를 초과하고 있는지 추적 가능).
 *
 * 운영 wiring 시점 (DlqAdminConfig) 에서만 활성. dev / 단위 테스트는 [NoopAdminRateLimiter].
 */
class RedisAdminRateLimiter(
    private val redis: StringRedisTemplate,
    private val limitPerMinute: Int,
) : AdminRateLimiter {

    /**
     * KEYS[1] = bucket key, ARGV[1] = limit, ARGV[2] = ttl seconds.
     * INCR, 첫 INCR 시 EXPIRE 부여, 응답값이 limit 을 초과하면 0 (deny), 아니면 1 (allow).
     */
    private val script = DefaultRedisScript(
        """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
          redis.call('EXPIRE', KEYS[1], ARGV[2])
        end
        if current > tonumber(ARGV[1]) then
          return 0
        end
        return 1
        """.trimIndent(),
        Long::class.java,
    )

    override fun allow(key: String, scope: AdminRateLimiter.Scope): Boolean {
        val bucket = "admin:dlq:${scope.name.lowercase()}:$key"
        return try {
            val res = redis.execute(
                script,
                listOf(bucket),
                limitPerMinute.toString(),
                BUCKET_TTL.seconds.toString(),
            )
            res == 1L
        } catch (e: RuntimeException) {
            // Redis 자체가 죽었을 때 admin 콘솔까지 차단되면 dispatch / DLQ 운영이 막힌다 —
            // fail-open. SLO 알림이 Redis 장애를 별 채널로 잡아 운영자가 인지하도록.
            log.warn("admin rate limiter unavailable, fail-open key={} scope={}", key, scope, e)
            true
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisAdminRateLimiter::class.java)
        private val BUCKET_TTL = Duration.ofMinutes(1)
    }
}

/** 항상 통과 — dev / 단위 테스트 / Redis 미연결 환경. */
class NoopAdminRateLimiter : AdminRateLimiter {
    override fun allow(key: String, scope: AdminRateLimiter.Scope): Boolean = true
}
