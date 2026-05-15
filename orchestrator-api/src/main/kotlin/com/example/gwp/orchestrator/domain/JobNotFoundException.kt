package com.example.gwp.orchestrator.domain

import java.util.UUID

class JobNotFoundException(id: UUID) : RuntimeException("job not found: $id")
