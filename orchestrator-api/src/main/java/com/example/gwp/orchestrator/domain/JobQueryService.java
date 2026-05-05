package com.example.gwp.orchestrator.domain;

import com.example.gwp.orchestrator.adapter.storage.PresignedUrlProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Job 조회 책임. 상태 변경 없음.
 *
 * <p>{@link #get(UUID)} 는 Redis cache-aside 적용 — 호출자가 다른 Spring Bean 이면 AOP proxy 가
 * 캐시 lookup. 같은 클래스 내 호출은 self-invocation 으로 우회되므로, 외부 컴포넌트
 * (JobAccessControl 등) 에서 호출할 것.</p>
 */
@Service
@RequiredArgsConstructor
public class JobQueryService {

    private final JobRepository jobRepository;
    private final PresignedUrlProvider presignedUrlProvider;

    @Cacheable(cacheNames = "jobs", key = "#id")
    @Transactional(readOnly = true)
    public Job get(UUID id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Job> list(String owner, JobStatus status, Pageable pageable) {
        return status == null
                ? jobRepository.findByOwner(owner, pageable)
                : jobRepository.findByOwnerAndStatus(owner, status, pageable);
    }

    /**
     * 결과 다운로드용 Presigned URL. SUCCEEDED 가 아니거나 resultUri 가 없으면 IllegalState.
     */
    @Transactional(readOnly = true)
    public String resultUrl(UUID id) {
        Job job = get(id);
        if (job.getStatus() != JobStatus.SUCCEEDED || job.getResultUri() == null) {
            throw new IllegalStateException("job result not available, status=" + job.getStatus());
        }
        return presignedUrlProvider.presignedGet(job.getResultUri());
    }
}
