package com.example.gwp.orchestrator.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 테넌트(owner)별 동시 실행 제한. 제출 흐름에서 QuotaService 가 검사한다.
 *
 * <p>설계 노트: 단순 카운트 기반 제한. 토큰 버킷이나 슬라이딩 윈도우는
 * 짧은 burst 까지 잡고 싶을 때 도입. 현재는 GPU Job 이 분 단위로 길어
 * 동시 실행 카운트만으로 충분.</p>
 */
@Entity
@Table(name = "user_quotas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserQuota {

    @Id
    @Column(name = "owner", length = 128)
    private String owner;

    @Column(name = "max_concurrent_jobs", nullable = false)
    private int maxConcurrentJobs;

    @Column(name = "max_gpu_count", nullable = false)
    private int maxGpuCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean accommodates(int currentJobs, int currentGpus, int requestedGpus) {
        if (currentJobs + 1 > maxConcurrentJobs) return false;
        if (currentGpus + requestedGpus > maxGpuCount) return false;
        return true;
    }
}
