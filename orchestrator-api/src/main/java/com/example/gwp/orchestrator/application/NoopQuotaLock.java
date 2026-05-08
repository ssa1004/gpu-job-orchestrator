package com.example.gwp.orchestrator.application;

/**
 * H2 / 단일 인스턴스 dev 환경용 no-op 구현. 실제 잠금 없이 즉시 반환.
 *
 * <p>테스트는 모두 H2 / 인메모리 — 동시 INSERT 가 일어날 일이 없어
 * race window 자체가 없다. 운영 (Postgres) 에서는 {@code PgAdvisoryQuotaLock}
 * 이 자동 활성화 (db-driver = postgres 일 때).</p>
 */
public final class NoopQuotaLock implements QuotaLock {

    @Override
    public void acquireForOwner(String owner) {
        // 의도적으로 비어 있음 — H2 환경 / 단위 테스트.
    }
}
