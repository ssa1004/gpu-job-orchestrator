package com.example.gwp.orchestrator.lifecycle;

import com.example.gwp.orchestrator.domain.JobStatus;

/**
 * 허용되지 않은 transition 이 시도된 경우. (1) source 상태에서 그 event 가 정의되지 않음,
 * 또는 (2) 모든 후보 transition 의 guard 가 false.
 *
 * <p>도메인 메서드의 {@link com.example.gwp.orchestrator.domain.IllegalJobTransitionException}
 * 과 별개 — 이건 *workflow 어휘* 의 위반, 그건 *기계적 적용* 의 위반. 둘 다 같이 살아 있으면
 * 도메인 무결성과 workflow 정합성이 *이중 방어선* 으로 보호된다.</p>
 */
public class IllegalJobLifecycleTransitionException extends RuntimeException {
    public IllegalJobLifecycleTransitionException(JobStatus source, JobLifecycleEvent event) {
        super("허용되지 않은 transition: source=" + source + " event=" + event);
    }

    public IllegalJobLifecycleTransitionException(JobStatus source, JobLifecycleEvent event,
                                                  String reason) {
        super("허용되지 않은 transition: source=" + source + " event=" + event + " (" + reason + ")");
    }
}
