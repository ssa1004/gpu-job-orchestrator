package com.example.gwp.orchestrator.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobDependencyRepository extends JpaRepository<JobDependency, UUID> {

    /** 한 child 가 기다리는 parent 들 — promote 가능한지 검사용. */
    List<JobDependency> findByChildJobId(UUID childJobId);

    /** 한 parent 가 끝났을 때 영향받는 child 들 — cascade trigger. */
    List<JobDependency> findByParentJobId(UUID parentJobId);
}
