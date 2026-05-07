package com.example.gwp.orchestrator.domain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyGraphTest {

    @Test
    void emptyGraph_noCycle() {
        assertThatCode(() -> DependencyGraph.detectCycle(Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void linearChain_noCycle() {
        // C → B → A
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        Map<UUID, Set<UUID>> g = new HashMap<>();
        g.put(b, Set.of(a));
        g.put(c, Set.of(b));
        assertThatCode(() -> DependencyGraph.detectCycle(g)).doesNotThrowAnyException();
    }

    @Test
    void diamond_noCycle() {
        // D → B → A
        // D → C → A
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        Map<UUID, Set<UUID>> g = new HashMap<>();
        g.put(b, Set.of(a));
        g.put(c, Set.of(a));
        g.put(d, Set.of(b, c));
        assertThatCode(() -> DependencyGraph.detectCycle(g)).doesNotThrowAnyException();
    }

    @Test
    void selfLoop_isCycle() {
        UUID a = UUID.randomUUID();
        Map<UUID, Set<UUID>> g = new HashMap<>();
        g.put(a, Set.of(a));
        assertThatThrownBy(() -> DependencyGraph.detectCycle(g))
                .isInstanceOf(DependencyCycleException.class);
    }

    @Test
    void simpleCycle_detected() {
        // A → B → A
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<UUID, Set<UUID>> g = new HashMap<>();
        g.put(a, Set.of(b));
        g.put(b, Set.of(a));
        assertThatThrownBy(() -> DependencyGraph.detectCycle(g))
                .isInstanceOf(DependencyCycleException.class);
    }

    @Test
    void longerCycle_detected() {
        // A → B → C → A
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        Map<UUID, Set<UUID>> g = new HashMap<>();
        g.put(a, Set.of(b));
        g.put(b, Set.of(c));
        g.put(c, Set.of(a));
        assertThatThrownBy(() -> DependencyGraph.detectCycle(g))
                .isInstanceOf(DependencyCycleException.class)
                .extracting(e -> ((DependencyCycleException) e).cyclePath())
                .satisfies(path -> {
                    // path 길이는 3+1 (close)
                    assertThatCode(() -> { if (((java.util.List<?>) path).size() < 3)
                            throw new AssertionError(); }).doesNotThrowAnyException();
                });
    }

    @Test
    void independentSubgraphs_noCycle() {
        // (A, B 독립) (C → D 독립) — 둘 다 cycle 없음
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID c = UUID.randomUUID(), d = UUID.randomUUID();
        Map<UUID, Set<UUID>> g = new HashMap<>();
        g.put(a, Set.of());
        g.put(b, Set.of());
        g.put(d, Set.of(c));
        assertThatCode(() -> DependencyGraph.detectCycle(g)).doesNotThrowAnyException();
    }
}
