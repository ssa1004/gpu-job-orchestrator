package com.example.gwp.orchestrator.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * 발행 대기 중인 메시지 — 아직 published_at 이 없고 DLQ 격리도 안 된 것.
     * createdAt ASC 로 정렬해 ordering 보장 (partition key = aggregateId 단위).
     */
    @Query("""
            SELECT m FROM OutboxMessage m
             WHERE m.publishedAt IS NULL
               AND m.deadLetteredAt IS NULL
             ORDER BY m.createdAt ASC
            """)
    List<OutboxMessage> findUnpublished(Pageable pageable);

    @Modifying
    @Query("UPDATE OutboxMessage m SET m.publishedAt = :now WHERE m.id = :id")
    void markPublished(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * 발행 실패 1회 기록 — attempt 증가 + 사유 저장. 다음 tick 에서 다시 시도.
     */
    @Modifying
    @Query("""
            UPDATE OutboxMessage m
               SET m.attemptCount   = m.attemptCount + 1,
                   m.lastAttemptAt  = :now,
                   m.lastError      = :reason
             WHERE m.id = :id
            """)
    void recordAttemptFailure(@Param("id") UUID id,
                              @Param("now") Instant now,
                              @Param("reason") String reason);

    /**
     * DLQ 격리 — attempt 증가 + 사유 저장 + dead_lettered_at SET. 이후 polling 에서 skip.
     */
    @Modifying
    @Query("""
            UPDATE OutboxMessage m
               SET m.attemptCount    = m.attemptCount + 1,
                   m.lastAttemptAt   = :now,
                   m.lastError       = :reason,
                   m.deadLetteredAt  = :now
             WHERE m.id = :id
            """)
    void markDeadLettered(@Param("id") UUID id,
                          @Param("now") Instant now,
                          @Param("reason") String reason);
}
