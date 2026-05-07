package com.example.gwp.orchestrator.domain;

import java.util.List;
import java.util.UUID;

/**
 * 의존성 그래프에 cycle 이 존재 — 영영 시작할 수 없는 잡.
 *
 * <p>예: {@code A → B → C → A}. 셋 모두 다른 누군가가 끝나길 기다리므로 *deadlock*.
 * 제출 시점에 미리 검증해 거절 (영속화하지 않음).</p>
 */
public class DependencyCycleException extends RuntimeException {

    private final List<UUID> cyclePath;

    public DependencyCycleException(List<UUID> cyclePath) {
        super("dependency cycle detected: " + cyclePath);
        this.cyclePath = List.copyOf(cyclePath);
    }

    public List<UUID> cyclePath() { return cyclePath; }
}
