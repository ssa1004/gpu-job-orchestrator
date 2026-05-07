package com.example.gwp.orchestrator.api.dto;

import com.example.gwp.orchestrator.domain.JobPriority;
import com.example.gwp.orchestrator.domain.PreemptionHistoryEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PreemptionHistoryResponse(List<Entry> items) {

    public record Entry(
            UUID id,
            UUID victimJobId,
            String victimOwner,
            JobPriority victimPriority,
            int victimGpuCount,
            UUID preemptorJobId,
            String preemptorOwner,
            JobPriority preemptorPriority,
            Instant preemptedAt,
            String reason
    ) {
        public static Entry from(PreemptionHistoryEntry h) {
            return new Entry(
                    h.getId(),
                    h.getVictimJobId(),
                    h.getVictimOwner(),
                    h.getVictimPriority(),
                    h.getVictimGpuCount(),
                    h.getPreemptorJobId(),
                    h.getPreemptorOwner(),
                    h.getPreemptorPriority(),
                    h.getPreemptedAt(),
                    h.getReason()
            );
        }
    }

    public static PreemptionHistoryResponse from(List<PreemptionHistoryEntry> entries) {
        return new PreemptionHistoryResponse(entries.stream().map(Entry::from).toList());
    }
}
