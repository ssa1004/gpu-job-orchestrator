package com.example.gwp.orchestrator.application;

import com.example.gwp.orchestrator.domain.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * owner 또는 admin 만 Job 에 접근 가능하도록 강제하는 ownership 게이트.
 *
 * <p>설계: ownership 검증과 도메인 호출을 한 곳으로 모음. Controller 는 인증 정보(owner, isAdmin)만
 * 전달하고, 권한 검사 + delegate 는 여기서 수행. {@link JobQueryService} / {@link JobLifecycleService}
 * 자체는 권한을 모름 — 내부 호출(예: 시스템 작업, 콜백)은 권한 검사를 우회할 수 있도록 분리.</p>
 *
 * <p>읽기는 {@link JobQueryService#get} 으로 위임 → cache 적용. (self-invocation 우회 방지)</p>
 */
@Service
@RequiredArgsConstructor
public class JobAccessControl {

    private final JobQueryService jobQueryService;
    private final JobLifecycleService jobLifecycleService;

    public Job getOwned(UUID id, String requester, boolean isAdmin) {
        Job job = jobQueryService.get(id);   // cache hit 가능
        ensureOwnership(job, requester, isAdmin);
        return job;
    }

    public Job cancelOwned(UUID id, String requester, boolean isAdmin) {
        Job job = jobQueryService.get(id);
        ensureOwnership(job, requester, isAdmin);
        return jobLifecycleService.cancel(id);
    }

    public String resultUrlOwned(UUID id, String requester, boolean isAdmin) {
        Job job = jobQueryService.get(id);
        ensureOwnership(job, requester, isAdmin);
        return jobQueryService.resultUrl(id);
    }

    private static void ensureOwnership(Job job, String requester, boolean isAdmin) {
        if (isAdmin) return;
        if (!job.getOwner().equals(requester)) {
            throw new AccessDeniedException(job.getId(), requester);
        }
    }
}
