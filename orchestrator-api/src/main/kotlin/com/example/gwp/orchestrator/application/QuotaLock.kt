package com.example.gwp.orchestrator.application

/**
 * 쿼터 검사 동시성 가드. 같은 owner 가 동시에 두 잡을 제출할 때
 * read-modify-write 사이에 over-commit 이 일어나는 race 를 막기 위한
 * 인터페이스. [QuotaService.enforceForSubmission] 가 트랜잭션 안에서 호출하며,
 * 호출자 트랜잭션이 끝날 때까지 같은 owner 의 다른 quota 검사 트랜잭션이 대기한다.
 *
 * 구현체:
 * - [PgAdvisoryQuotaLock] (운영 / Postgres) — owner 문자열을 해시한 64bit key 로
 *   `pg_advisory_xact_lock` 호출. 트랜잭션 commit / rollback 시 자동 release.
 * - [NoopQuotaLock] (H2 / default) — 테스트 / 단일 인스턴스 dev 환경에서는 실제 잠금
 *   불필요. 단위 테스트가 이 구현으로 동작.
 *
 * Advisory lock 을 선택한 이유: row lock (`SELECT ... FOR UPDATE`) 은 row 가 *이미
 * 존재* 해야 동작 — 신규 owner (UserQuota row 가 아직 없는) 의 첫 제출은 row 가 없어
 * 잠글 대상이 없다. Advisory lock 은 임의의 64bit key 로 잠금을 잡을 수 있어 row
 * 존재 여부와 무관하게 동작.
 *
 * Java 호출자 (`quotaLock.acquireForOwner(owner)`) 무변경 — Kotlin `interface` 가
 * 그대로 Java `interface` 로 컴파일.
 */
interface QuotaLock {

    /**
     * 호출 트랜잭션이 끝날 때까지 owner 단위 advisory lock 을 잡는다.
     * 같은 owner 의 다른 트랜잭션은 lock 이 풀릴 때까지 대기한다 (PG).
     */
    fun acquireForOwner(owner: String)
}
