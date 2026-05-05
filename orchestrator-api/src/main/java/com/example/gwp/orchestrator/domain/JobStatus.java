package com.example.gwp.orchestrator.domain;

import java.util.Set;

public enum JobStatus {
    QUEUED,
    DISPATCHING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    private static final Set<JobStatus> ACTIVE = Set.of(QUEUED, DISPATCHING, RUNNING);
    private static final Set<JobStatus> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELLED);

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
