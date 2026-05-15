package com.example.gwp.orchestrator.domain

import java.util.LinkedHashSet
import java.util.UUID

/**
 * 의존성 그래프 검증 — *제출 시점* cycle detection.
 *
 * Job 제출 시 호출자가 "새로 추가될 edge + 기존 edge" 를 합쳐 검사한다. 한 번 DB 에
 * 영속된 그래프에는 절대 cycle 이 생기지 않게 막는 게 이 클래스의 책임.
 *
 * ### 알고리즘 — DFS 3색 (WHITE / GRAY / BLACK) cycle detection
 * - WHITE — 아직 방문 안 함 (코드의 초기 상태).
 * - GRAY  — 현재 DFS 경로 위에 있는 노드 (`temporary`).
 * - BLACK — 이미 검증 완료된 노드 (`permanent`).
 *
 * DFS 도중 GRAY 인 노드를 다시 만나면 = 현재 경로가 자기 자신으로 돌아옴 = cycle.
 * 한 노드를 끝까지 탐색하고 빠져나오면 BLACK 으로 마킹 → 다른 경로에서 같은 노드를 만나도
 * 재방문 안 함. 시간복잡도 O(V+E). 한 잡당 부모가 보통 수 개라 충분히 빠르다.
 *
 * Java 호출자 (`DependencyGraph.detectCycle(...)`) 그대로 동작 — `@JvmStatic` 으로
 * static 메서드 합성.
 */
object DependencyGraph {

    /**
     * 그래프 = `child → parents` 인접 리스트. 이걸 *반대 방향* (parent → children) 으로
     * 봐도 동일한 cycle. 보통 호출자가 child→parents 로 들고 있어 그대로 받음.
     *
     * @throws DependencyCycleException 그래프에 cycle 이 있으면. 메시지에 cycle path 포함.
     */
    @JvmStatic
    fun detectCycle(childToParents: Map<UUID, Set<UUID>>) {
        val permanent: MutableSet<UUID> = HashSet() // BLACK — 이미 검증 완료
        val temporary: MutableSet<UUID> = LinkedHashSet() // GRAY — 현재 DFS 경로 위
        for (node in childToParents.keys) {
            if (!permanent.contains(node)) {
                visit(node, childToParents, permanent, temporary)
            }
        }
    }

    private fun visit(
        node: UUID,
        graph: Map<UUID, Set<UUID>>,
        permanent: MutableSet<UUID>,
        temporary: MutableSet<UUID>,
    ) {
        if (permanent.contains(node)) return
        if (temporary.contains(node)) {
            // GRAY 다시 만남 — cycle. 현재 DFS 경로 + 발견된 node 까지가 cycle path.
            val path: MutableList<UUID> = ArrayList(temporary)
            path.add(node) // close the loop visually
            throw DependencyCycleException(path)
        }
        temporary.add(node)
        val parents: Set<UUID> = graph[node] ?: emptySet()
        for (p in parents) {
            visit(p, graph, permanent, temporary)
        }
        temporary.remove(node)
        permanent.add(node)
    }
}
