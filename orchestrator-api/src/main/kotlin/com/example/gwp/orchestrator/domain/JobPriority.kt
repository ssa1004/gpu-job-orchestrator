package com.example.gwp.orchestrator.domain

/**
 * GPU Job 우선순위. 디스패처/스케줄러가 큐에서 꺼내는 순서를 결정.
 * 동일 우선순위 안에서는 created_at 오름차순.
 */
enum class JobPriority(private val weight: Int) {
    LOW(0),
    NORMAL(50),
    HIGH(100),
    ;

    fun weight(): Int = weight

    fun higherThan(other: JobPriority): Boolean = this.weight > other.weight
}
