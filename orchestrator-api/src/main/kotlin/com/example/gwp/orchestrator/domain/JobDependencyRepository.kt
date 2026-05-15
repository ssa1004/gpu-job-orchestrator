package com.example.gwp.orchestrator.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface JobDependencyRepository : JpaRepository<JobDependency, UUID> {

    /** 한 child 가 기다리는 parent 들 — promote 가능한지 검사용. */
    fun findByChildJobId(childJobId: UUID): List<JobDependency>

    /**
     * 여러 child 의 parent edge 를 한 번의 IN 쿼리로 batch 로딩. cycle 검사에서 BFS frontier
     * 한 레벨 (수십~수백 노드) 의 부모를 한 SELECT 로 끌어와 N+1 round-trip 을 방지한다.
     * 빈 collection 이면 빈 리스트.
     */
    fun findByChildJobIdIn(childJobIds: Collection<UUID>): List<JobDependency>

    /** 한 parent 가 끝났을 때 영향받는 child 들 — cascade trigger. */
    fun findByParentJobId(parentJobId: UUID): List<JobDependency>
}
