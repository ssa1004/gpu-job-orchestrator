package com.example.gwp.orchestrator.api.dto

import com.example.gwp.orchestrator.domain.Job
import com.example.gwp.orchestrator.domain.JobPriority
import com.example.gwp.orchestrator.domain.JobStatus
import com.example.gwp.orchestrator.domain.PreemptionPolicy
import java.time.Instant
import java.util.UUID

@JvmRecord
data class JobResponse(
    val id: UUID,
    val owner: String,
    val image: String,
    val gpuCount: Int,
    val status: JobStatus,
    val priority: JobPriority,
    val preemptionPolicy: PreemptionPolicy,
    val inputUri: String,
    val resultUri: String?,
    val traceId: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    // PREEMPTED 인 잡에서만 채워짐 — 운영 화면 / 사용자 알림 본문에 사용
    val preemptedAt: Instant?,
    val preemptedByJobId: UUID?,
    val preemptedReason: String?,
) {
    companion object {
        @JvmStatic
        fun from(job: Job): JobResponse = JobResponse(
            job.id,
            job.owner,
            job.image,
            job.gpuCount,
            job.status,
            job.priority,
            job.preemptionPolicy,
            job.inputUri,
            job.resultUri,
            job.traceId,
            job.errorMessage,
            job.createdAt,
            job.startedAt,
            job.finishedAt,
            job.preemptedAt,
            job.preemptedByJobId,
            job.preemptedReason,
        )
    }
}
