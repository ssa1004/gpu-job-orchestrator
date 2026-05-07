package com.example.gwp.orchestrator.domain;

import java.util.Set;

/**
 * Job 라이프사이클 상태.
 *
 * <p><b>{@link #PREEMPTED} 와 {@link #CANCELLED} 의 구분</b>:
 * <ul>
 *   <li>{@code CANCELLED} — *사용자/운영자가 명시적으로* 죽임. 사용자 의도 (잘못된 입력 / 예산 초과 등).</li>
 *   <li>{@code PREEMPTED} — *시스템이 능동적으로* 죽임 (더 높은 우선순위 잡에게 GPU 양보).
 *       이 잡 자체에는 잘못 없음. 운영 통계 / 빌링 / 사용자 알림에서 두 status 는 다르게 다뤄야 해서 분리.</li>
 * </ul>
 */
public enum JobStatus {
    QUEUED,
    DISPATCHING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PREEMPTED;

    private static final Set<JobStatus> ACTIVE = Set.of(QUEUED, DISPATCHING, RUNNING);
    private static final Set<JobStatus> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELLED, PREEMPTED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public static Set<JobStatus> activeStatuses() {
        return ACTIVE;
    }
}
