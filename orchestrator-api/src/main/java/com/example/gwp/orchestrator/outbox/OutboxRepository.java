package com.example.gwp.orchestrator.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    @Query("SELECT m FROM OutboxMessage m WHERE m.publishedAt IS NULL ORDER BY m.createdAt ASC")
    List<OutboxMessage> findUnpublished(Pageable pageable);

    @Query("UPDATE OutboxMessage m SET m.publishedAt = :now WHERE m.id = :id")
    @org.springframework.data.jpa.repository.Modifying
    void markPublished(@Param("id") UUID id, @Param("now") Instant now);
}
