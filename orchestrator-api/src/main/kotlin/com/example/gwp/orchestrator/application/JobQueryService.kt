package com.example.gwp.orchestrator.application

import com.example.gwp.orchestrator.adapter.storage.PresignedUrlProvider
import com.example.gwp.orchestrator.domain.Job
import com.example.gwp.orchestrator.domain.JobNotFoundException
import com.example.gwp.orchestrator.domain.JobRepository
import com.example.gwp.orchestrator.domain.JobStatus
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Job 조회 책임. 상태 변경 없음.
 *
 * [get] 은 Redis cache-aside (캐시에 없으면 DB 조회 후 채워 넣는 패턴) 적용 — 호출자가
 * 다른 Spring Bean 이면 AOP proxy 가 캐시 lookup. 같은 클래스 내 호출은 self-invocation
 * (this 호출) 으로 프록시를 우회하므로 외부 컴포넌트 (JobAccessControl 등) 에서 호출할 것.
 *
 * Java 호출자 (Controller / JobAccessControl / Test) 무변경 — Kotlin primary
 * constructor 가 같은 positional 시그니처. `plugin.spring` 이 `@Service` 자동 open,
 * `@Cacheable` / `@Transactional` 적용을 위해 메서드도 자동 open.
 */
@Service
class JobQueryService(
    private val jobRepository: JobRepository,
    private val presignedUrlProvider: PresignedUrlProvider,
) {

    @Cacheable(cacheNames = ["jobs"], key = "#id")
    @Transactional(readOnly = true)
    fun get(id: UUID): Job =
        jobRepository.findById(id).orElseThrow { JobNotFoundException(id) }

    @Transactional(readOnly = true)
    fun list(owner: String, status: JobStatus?, pageable: Pageable): Page<Job> =
        if (status == null) jobRepository.findByOwner(owner, pageable)
        else jobRepository.findByOwnerAndStatus(owner, status, pageable)

    /**
     * 결과 다운로드용 Presigned URL (일정 시간만 유효한 사전 서명된 다운로드 링크 — S3 /
     * MinIO 가 발급). SUCCEEDED 가 아니거나 resultUri 가 없으면 IllegalState.
     */
    @Transactional(readOnly = true)
    fun resultUrl(id: UUID): String {
        val job = get(id)
        val uri = job.resultUri
        if (job.status != JobStatus.SUCCEEDED || uri == null) {
            throw IllegalStateException("job result not available, status=" + job.status)
        }
        return presignedUrlProvider.presignedGet(uri)
    }
}
