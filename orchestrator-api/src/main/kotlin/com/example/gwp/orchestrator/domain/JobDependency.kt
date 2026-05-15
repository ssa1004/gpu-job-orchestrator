package com.example.gwp.orchestrator.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * "이 child Job 은 저 parent Job 이 끝난 후에만 시작한다" 의 영속 표현.
 *
 * Many-to-many 관계라 별도 테이블. `(child_job_id, parent_job_id)` unique 로 중복 차단.
 *
 * **왜 이 구조**:
 * - 한 child 가 여러 parent 를 가질 수 있음 (예: A,B,C 세 학습이 모두 끝난 후 D 시작 = 분산 학습 결합)
 * - 한 parent 가 여러 child 를 가질 수 있음 (예: 데이터 전처리 잡 1개 → 학습 잡 N개 fan-out)
 * - 그래프 cycle 은 *제출 시점* 에 검증 — 한번 영속되면 사이클 절대 못 만듦
 *
 * 비유: Airflow / Argo Workflows / Kubeflow 의 task dependency. 그들은 DAG 정의를
 * YAML / Python 에 두지만, 우리는 "잡 단위로 점진적으로 chain" 가능 (런타임에 새 의존 추가).
 *
 * 영속화 후 변경되지 않는 immutable edge — 모든 필드 `val`. Java 호출자
 * (`d.getChildJobId()` / `d.getParentJobId()` 등) 무변경.
 */
@Entity
@Table(
    name = "job_dependencies",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_job_dep_child_parent",
            columnNames = ["child_job_id", "parent_job_id"],
        ),
    ],
    indexes = [
        // child 의 모든 parent 조회 — "이 잡이 기다리는 parent 들"
        Index(name = "idx_job_dep_child", columnList = "child_job_id"),
        // parent 의 모든 child 조회 — "이 parent 가 끝나면 promote 검사할 child 들"
        Index(name = "idx_job_dep_parent", columnList = "parent_job_id"),
    ],
)
class JobDependency(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID,

    @Column(name = "child_job_id", nullable = false)
    val childJobId: UUID,

    @Column(name = "parent_job_id", nullable = false)
    val parentJobId: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {

    companion object {
        @JvmStatic
        fun edge(childJobId: UUID, parentJobId: UUID, createdAt: Instant): JobDependency {
            require(childJobId != parentJobId) { "self-dependency not allowed: $childJobId" }
            return JobDependency(UUID.randomUUID(), childJobId, parentJobId, createdAt)
        }
    }
}
