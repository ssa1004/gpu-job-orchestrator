package com.example.gwp.orchestrator.domain;

/**
 * GPU Job 우선순위. 디스패처/스케줄러가 큐에서 꺼내는 순서를 결정.
 * 동일 우선순위 안에서는 created_at 오름차순.
 */
public enum JobPriority {
    LOW(0),
    NORMAL(50),
    HIGH(100);

    private final int weight;

    JobPriority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public boolean higherThan(JobPriority other) {
        return this.weight > other.weight;
    }
}
