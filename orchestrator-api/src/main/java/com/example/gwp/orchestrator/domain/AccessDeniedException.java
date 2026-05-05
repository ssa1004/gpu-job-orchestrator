package com.example.gwp.orchestrator.domain;

import java.util.UUID;

public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(UUID jobId, String requester) {
        super("access denied to job=" + jobId + " for requester=" + requester);
    }
}
