package com.example.gwp.orchestrator.domain

import java.util.UUID

class AccessDeniedException(jobId: UUID?, requester: String) :
    RuntimeException("access denied to job=$jobId for requester=$requester")
