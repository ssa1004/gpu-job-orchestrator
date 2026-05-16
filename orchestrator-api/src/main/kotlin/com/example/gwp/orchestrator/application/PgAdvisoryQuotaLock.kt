package com.example.gwp.orchestrator.application

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory

/**
 * Postgres 전용 — owner 단위 `pg_advisory_xact_lock` 으로 quota 검사 동시성을
 * 직렬화한다.
 *
 * 호출자 트랜잭션 안에서 실행되어야 하며, lock 은 트랜잭션 commit / rollback 시 자동
 * release. owner 문자열을 해시해 64bit key 로 변환, 같은 owner 끼리만 직렬화 → 다른
 * owner 의 제출은 영향 없음.
 *
 * 왜 row lock (`SELECT ... FOR UPDATE`) 이 아닌 advisory lock?
 * UserQuota row 는 신규 owner 의 첫 제출 시점에 아직 없을 수 있어 잠글 대상이 없다.
 * Advisory lock 은 row 존재 여부와 무관하게 임의 key 로 잠금을 잡을 수 있어 신규
 * owner 도 안전.
 *
 * 해시 충돌 (서로 다른 owner 가 같은 64bit key 로 매핑) 시 잠시 직렬화 되지만 정확성에는
 * 영향 없음 — 잠금이 풀린 후 정상 진행.
 *
 * Java 호출자 (QuotaLockConfig 의 `new PgAdvisoryQuotaLock()`) 무변경 — Kotlin
 * `class` 가 기본 no-arg 생성자를 합성.
 */
class PgAdvisoryQuotaLock : QuotaLock {

    @PersistenceContext
    private lateinit var em: EntityManager

    override fun acquireForOwner(owner: String) {
        val key = hashOwner(owner)
        em.createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
            .setParameter(1, key)
            .singleResult
        if (log.isDebugEnabled) {
            log.debug("acquired advisory lock owner_key={}", key)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PgAdvisoryQuotaLock::class.java)

        /**
         * owner 문자열을 64bit signed long 키로 변환. [String.hashCode] 는 32bit 라
         * 충돌이 잦을 수 있어 64bit FNV-1a 변형으로 분산성 향상.
         * key 의 정확한 값은 의미가 없고, 같은 owner 가 항상 같은 key 로 매핑 되기만
         * 하면 된다.
         */
        // FNV offset basis (64bit) — 0xcbf29ce484222325 (unsigned). Kotlin 의 Long 은
        // signed 라 hex literal 그대로는 범위 초과 → ULong 으로 풀고 toLong() 으로 캐스팅.
        private val FNV_OFFSET_BASIS: Long = 0xcbf29ce484222325uL.toLong()
        private const val FNV_PRIME: Long = 0x100000001b3L

        @JvmStatic
        fun hashOwner(owner: String?): Long {
            if (owner == null) return 0L
            var h = FNV_OFFSET_BASIS
            for (i in 0 until owner.length) {
                h = h xor owner[i].code.toLong()
                h *= FNV_PRIME
            }
            return h
        }
    }
}
