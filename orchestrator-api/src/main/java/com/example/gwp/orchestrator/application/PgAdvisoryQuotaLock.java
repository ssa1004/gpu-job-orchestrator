package com.example.gwp.orchestrator.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Postgres 전용 — owner 단위 {@code pg_advisory_xact_lock} 으로 quota 검사
 * 동시성을 직렬화한다.
 *
 * <p>호출자 트랜잭션 안에서 실행되어야 하며, lock 은 트랜잭션 commit /
 * rollback 시 자동 release. owner 문자열을 해시해 64bit key 로 변환,
 * 같은 owner 끼리만 직렬화 → 다른 owner 의 제출은 영향 없음.</p>
 *
 * <p>왜 row lock ({@code SELECT ... FOR UPDATE}) 이 아닌 advisory lock?
 * UserQuota row 는 신규 owner 의 첫 제출 시점에 아직 없을 수 있어
 * 잠글 대상이 없다. Advisory lock 은 row 존재 여부와 무관하게 임의 key
 * 로 잠금을 잡을 수 있어 신규 owner 도 안전.</p>
 *
 * <p>해시 충돌 (서로 다른 owner 가 같은 64bit key 로 매핑) 시 잠시 직렬화
 * 되지만 정확성에는 영향 없음 — 잠금이 풀린 후 정상 진행.</p>
 */
@Slf4j
public class PgAdvisoryQuotaLock implements QuotaLock {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void acquireForOwner(String owner) {
        long key = hashOwner(owner);
        em.createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
                .setParameter(1, key)
                .getSingleResult();
        if (log.isDebugEnabled()) {
            log.debug("acquired advisory lock owner_key={}", key);
        }
    }

    /**
     * owner 문자열을 64bit signed long 키로 변환. {@link String#hashCode()} 는
     * 32bit 라 충돌이 잦을 수 있어 64bit FNV-1a 변형으로 분산성 향상.
     * key 의 정확한 값은 의미가 없고, 같은 owner 가 항상 같은 key 로 매핑
     * 되기만 하면 된다.
     */
    static long hashOwner(String owner) {
        if (owner == null) return 0L;
        long h = 0xcbf29ce484222325L;             // FNV offset basis (64bit)
        for (int i = 0; i < owner.length(); i++) {
            h ^= owner.charAt(i);
            h *= 0x100000001b3L;                   // FNV prime (64bit)
        }
        return h;
    }
}
