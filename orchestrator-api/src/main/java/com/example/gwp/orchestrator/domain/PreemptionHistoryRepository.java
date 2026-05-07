package com.example.gwp.orchestrator.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PreemptionHistoryRepository extends JpaRepository<PreemptionHistoryEntry, UUID> {

    /** 한 victim 잡의 preempt 이력 — 보통 1건 (한 번 죽으면 끝). */
    List<PreemptionHistoryEntry> findByVictimJobIdOrderByPreemptedAtDesc(UUID victimJobId);

    /** 한 preemptor 잡이 죽인 victim 들. 한 preemptor 가 여러 victim 죽일 수 있음. */
    List<PreemptionHistoryEntry> findByPreemptorJobIdOrderByPreemptedAtDesc(UUID preemptorJobId);

    /** 최근 preemption 이벤트 — 운영 timeline / 분석용. */
    List<PreemptionHistoryEntry> findAllByOrderByPreemptedAtDesc(Pageable pageable);
}
