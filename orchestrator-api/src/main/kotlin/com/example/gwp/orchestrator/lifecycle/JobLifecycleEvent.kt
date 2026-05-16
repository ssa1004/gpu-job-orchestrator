package com.example.gwp.orchestrator.lifecycle

/**
 * 잡 라이프사이클의 *트리거* 어휘 — *이런 사건이 발생했다* 는 의도. 도메인 메서드 이름과
 * 1:1 대응되지만 별도 어휘를 두는 이유는:
 *
 * - 도메인 메서드 (예: `Job.markRunning`) 는 *상태 변경의 기계적 적용*. 호출자
 *   (Service / Worker callback / Scheduler) 마다 다른 책임에서 부르는데 *왜 호출했는지*
 *   라는 의도가 메서드 이름에 가려진다.
 * - 이 enum 은 *비즈니스 사건* 의 어휘 — "Submit", "Dispatch", "Run", "Succeed", ...
 *   audit log / state machine diagram / 운영 모니터링이 같은 어휘로 통일된다.
 * - 새로운 transition 추가 시 (예: timeout retry) enum 한 줄 + transition table 한 줄로
 *   끝나도록 — 도메인 메서드는 안 건드림.
 */
enum class JobLifecycleEvent {

    /** 사용자가 신규 잡을 제출. parent 없음 → QUEUED 직행. */
    SUBMIT,

    /** 사용자가 신규 잡을 제출. parent 있음 → WAITING_DEPS 로 시작. */
    SUBMIT_WITH_DEPS,

    /** parent 가 모두 SUCCEEDED — 의존성 해소. WAITING_DEPS → QUEUED. */
    DEPENDENCIES_RESOLVED,

    /** 부모 잡이 FAILED / CANCELLED / PREEMPTED — child 도 cascade cancel. */
    DEPENDENCIES_BROKEN,

    /** 디스패처가 K8s Job 생성에 성공. QUEUED → DISPATCHING. */
    DISPATCH,

    /** 워커가 실행 시작 콜백. DISPATCHING / QUEUED → RUNNING. */
    RUN,

    /** 워커가 정상 종료 콜백. * → SUCCEEDED. */
    SUCCEED,

    /** 워커가 실패 종료 콜백 또는 디스패치 자체 실패. * → FAILED. */
    FAIL,

    /** 사용자가 명시적으로 취소. * → CANCELLED. */
    CANCEL,

    /** 시스템이 더 높은 우선순위 잡에게 GPU 양보. PREEMPTABLE 인 active job → PREEMPTED. */
    PREEMPT,
}
