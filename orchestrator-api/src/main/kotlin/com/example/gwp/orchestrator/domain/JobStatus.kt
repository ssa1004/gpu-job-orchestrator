package com.example.gwp.orchestrator.domain

/**
 * Job 라이프사이클 상태.
 *
 * **[PREEMPTED] 와 [CANCELLED] 의 구분**:
 * - `CANCELLED` — *사용자/운영자가 명시적으로* 죽임. 사용자 의도 (잘못된 입력 / 예산 초과 등).
 * - `PREEMPTED` — *시스템이 능동적으로* 죽임 (더 높은 우선순위 잡에게 GPU 양보).
 *   이 잡 자체에는 잘못 없음. 운영 통계 / 빌링 / 사용자 알림에서 두 status 는 다르게 다뤄야 해서 분리.
 */
enum class JobStatus {
    /**
     * 의존하는 부모 잡 (parent) 들이 아직 SUCCEEDED 가 아니라 *대기 중*. parent 가 모두
     * SUCCEEDED 되는 순간 [QUEUED] 로 promote. parent 중 하나라도 FAILED/CANCELLED 면
     * cascade 로 이 잡도 CANCELLED.
     */
    WAITING_DEPS,
    QUEUED,
    DISPATCHING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PREEMPTED,
    ;

    fun isTerminal(): Boolean = TERMINAL.contains(this)

    fun isActive(): Boolean = ACTIVE.contains(this)

    companion object {
        /**
         * "아직 살아 있는" 상태들. WAITING_DEPS 도 active — 자원 할당은 안 됐지만 라이프사이클상 진행 중.
         */
        private val ACTIVE: Set<JobStatus> = setOf(WAITING_DEPS, QUEUED, DISPATCHING, RUNNING)
        private val TERMINAL: Set<JobStatus> = setOf(SUCCEEDED, FAILED, CANCELLED, PREEMPTED)

        @JvmStatic
        fun activeStatuses(): Set<JobStatus> = ACTIVE
    }
}
