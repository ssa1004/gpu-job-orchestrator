package com.example.gwp.orchestrator.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 의존성 그래프 검증 — *제출 시점* cycle detection.
 *
 * <p>Job 제출 시 호출자가 새로 추가될 edge 들 + 기존 edge 들 합쳐서 cycle 검사. 한 번
 * 영속되면 절대 cycle 안 생기게 보장하는 게 이 클래스의 책임.</p>
 *
 * <p>알고리즘: <b>DFS 기반 cycle detection</b>. node 색칠 (WHITE/GRAY/BLACK) 으로 표준 구현.
 * GRAY 인 node 를 다시 만나면 cycle. 시간복잡도 O(V+E) — 한 잡당 보통 수개 의존이라 충분히 빠름.</p>
 */
public final class DependencyGraph {

    private DependencyGraph() {}

    /**
     * 그래프 = {@code child → parents} 인접 리스트. 이걸 *반대 방향* (parent → children) 으로
     * 봐도 동일한 cycle. 보통 호출자가 child→parents 로 들고 있어 그대로 받음.
     *
     * @throws DependencyCycleException 그래프에 cycle 이 있으면. 메시지에 cycle path 포함.
     */
    public static void detectCycle(Map<UUID, Set<UUID>> childToParents) {
        Set<UUID> permanent = new HashSet<>();      // BLACK — 이미 검증 완료
        Set<UUID> temporary = new LinkedHashSet<>();// GRAY  — 현재 DFS 경로 위
        for (UUID node : childToParents.keySet()) {
            if (!permanent.contains(node)) {
                visit(node, childToParents, permanent, temporary);
            }
        }
    }

    private static void visit(UUID node, Map<UUID, Set<UUID>> graph,
                              Set<UUID> permanent, Set<UUID> temporary) {
        if (permanent.contains(node)) return;
        if (temporary.contains(node)) {
            // GRAY 다시 만남 — cycle. 현재 DFS 경로 + 발견된 node 까지가 cycle path.
            List<UUID> path = new ArrayList<>(temporary);
            path.add(node);   // close the loop visually
            throw new DependencyCycleException(path);
        }
        temporary.add(node);
        Set<UUID> parents = graph.getOrDefault(node, Set.of());
        for (UUID p : parents) {
            visit(p, graph, permanent, temporary);
        }
        temporary.remove(node);
        permanent.add(node);
    }
}
