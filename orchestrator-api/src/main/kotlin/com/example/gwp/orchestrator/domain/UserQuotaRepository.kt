package com.example.gwp.orchestrator.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface UserQuotaRepository : JpaRepository<UserQuota, String> {
    fun findByOwner(owner: String): Optional<UserQuota>
}
