package com.example.gwp.orchestrator.api.dto

import com.example.gwp.orchestrator.domain.JobPriority
import com.example.gwp.orchestrator.domain.PreemptionHistoryEntry
import java.time.Instant
import java.util.UUID

@JvmRecord
data class PreemptionHistoryResponse(val items: List<Entry>) {

    @JvmRecord
    data class Entry(
        val id: UUID,
        val victimJobId: UUID,
        val victimOwner: String,
        val victimPriority: JobPriority,
        val victimGpuCount: Int,
        val preemptorJobId: UUID,
        val preemptorOwner: String,
        val preemptorPriority: JobPriority,
        val preemptedAt: Instant,
        val reason: String,
    ) {
        companion object {
            @JvmStatic
            fun from(h: PreemptionHistoryEntry): Entry = Entry(
                h.id,
                h.victimJobId,
                h.victimOwner,
                h.victimPriority,
                h.victimGpuCount,
                h.preemptorJobId,
                h.preemptorOwner,
                h.preemptorPriority,
                h.preemptedAt,
                h.reason,
            )
        }
    }

    companion object {
        @JvmStatic
        fun from(entries: List<PreemptionHistoryEntry>): PreemptionHistoryResponse =
            PreemptionHistoryResponse(entries.map { Entry.from(it) })
    }
}
