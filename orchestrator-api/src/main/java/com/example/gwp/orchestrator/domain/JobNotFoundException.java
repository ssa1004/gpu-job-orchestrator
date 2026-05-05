package com.example.gwp.orchestrator.domain;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(UUID id) {
        super("job not found: " + id);
    }
}
