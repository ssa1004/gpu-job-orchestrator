package com.example.gwp.orchestrator.domain

import java.util.UUID

/**
 * 의존성 그래프에 cycle 이 존재 — 영영 시작할 수 없는 잡.
 *
 * 예: `A → B → C → A`. 셋 모두 다른 누군가가 끝나길 기다리므로 *deadlock*.
 * 제출 시점에 미리 검증해 거절 (영속화하지 않음).
 *
 * Java 호출자가 `e.cyclePath()` (record-style accessor) 로 접근하므로
 * `@get:JvmName("cyclePath")` 으로 시그니처를 보존한다.
 */
class DependencyCycleException(cyclePath: List<UUID>) :
    RuntimeException("dependency cycle detected: $cyclePath") {

    @get:JvmName("cyclePath")
    val cyclePath: List<UUID> = java.util.List.copyOf(cyclePath)
}
