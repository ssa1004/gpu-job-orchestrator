package com.example.gwp.orchestrator.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserQuotaRepository extends JpaRepository<UserQuota, String> {
    Optional<UserQuota> findByOwner(String owner);
}
