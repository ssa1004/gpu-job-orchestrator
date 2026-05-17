package com.example.gwp.orchestrator.dlq

/**
 * DLQ 메시지의 영속화 / 조회 추상화 (port out, hexagonal).
 *
 * - InMemory 구현: 단위 테스트 / 로컬 dev — 인-메모리 LinkedHashMap.
 * - Kafka 구현: 운영 — Kafka admin topic (`gwp.dlq.*`) consumer 가 in-memory cache
 *   에 적재 + outbox 테이블의 dead_lettered row 를 OUTBOX source 로 같이 노출.
 *
 * 구현이 *kafka admin 토픽* 이든 *DB 폴링* 이든 콘솔 백엔드는 같은 port 만 본다.
 * 인프라 교체가 컨트롤러 / service 코드 변경 없이 가능.
 *
 * `replay` / `discard` 는 *멱등* — 이미 replay 된 메시지를 다시 replay 해도 IGNORED
 * 결과로 끝나야 한다 (idempotency key + 도메인 멱등성의 합산).
 */
interface DlqMessageStore {

    fun list(filter: DlqEntryFilter): DlqListPage

    fun findById(id: String): DlqMessage?

    /**
     * 단건 replay — 메시지를 원래 publisher / endpoint 로 다시 흘린다.
     * 반환: SUCCESS = 정상 replay, IGNORED = 이미 처리된 / not-found (멱등),
     * FAILED = 도메인 거절 / I/O 오류.
     */
    fun replay(id: String, idempotencyKey: String, actor: String): ReplayOutcome

    /**
     * 단건 discard — 메시지를 콘솔 / 발행 큐에서 제거. soft delete + retention
     * 으로 audit / cost ledger 무결성 유지 (hard DELETE 차단).
     * 반환: SUCCESS = 정상 discard, IGNORED = 이미 discard 된 / not-found.
     */
    fun discard(id: String, reason: String, actor: String): DiscardOutcome

    /**
     * source 별 stats. bucket 폭은 ISO-8601 duration string (예: `PT1H`).
     */
    fun stats(filter: DlqEntryFilter, bucket: String): DlqStats

    enum class ReplayOutcome { SUCCESS, IGNORED, FAILED }

    enum class DiscardOutcome { SUCCESS, IGNORED }
}
