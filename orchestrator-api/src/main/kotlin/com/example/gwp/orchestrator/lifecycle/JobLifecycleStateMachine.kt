package com.example.gwp.orchestrator.lifecycle

import com.example.gwp.orchestrator.domain.JobStatus
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Job 라이프사이클의 *외부 검증 + 기록 + 시각화* 를 담당하는 transition table.
 *
 * ### 왜 이 클래스가 필요한가
 * - 도메인 ([com.example.gwp.orchestrator.domain.Job]) 의 mark* 메서드는 *기계적
 *   전이* 를 적용하지만, *허용되는 모든 전이의 catalog* 는 메서드들 사이에 흩어져 있다 —
 *   어떤 도메인 메서드가 어떤 source 에서 호출 가능한지 한눈에 안 보인다.
 * - 이 상태 머신이 그 catalog 를 *데이터* 로 표현 — 운영자 / 신규 개발자가 한 곳에서
 *   라이프사이클 전체를 본다. 다이어그램 export, 자동 검증, audit log 모두 같은 데이터에서.
 * - 새 transition 추가 시 (예: timeout retry, manual requeue) [Transition] 한 줄
 *   추가만으로 끝 — 도메인 메서드의 if-else 가지 늘어나는 것보다 깔끔.
 *
 * ### 도메인 메서드와의 관계
 * 이 상태 머신은 *도메인 메서드를 대체하지 않는다*. lifecycle 서비스는 여전히
 * `Job.markRunning(clock)` 같은 도메인 호출로 실제 상태를 바꾼다. 이 상태 머신은
 * 그 호출 직전에 [fire] 로 *전이가 정의되어 있는지* 만 검증하고, audit log /
 * 메트릭 / 시각화 정보를 기록한다.
 *
 * ### 왜 Spring StateMachine 라이브러리를 안 쓰는가 — ADR-0022 참고
 *
 * Java 호출자 (`machine.fire(source, event, ctx)` 등) 그대로 동작 — primary constructor 가
 * 같은 시그니처. `plugin.spring` 이 `@Bean` factory 가 반환하는 타입을 별도 처리하지는 않지만
 * 본 클래스는 단순 POJO 라 open 불필요.
 */
class JobLifecycleStateMachine(transitions: List<Transition>) {

    private val all: List<Transition> = java.util.List.copyOf(transitions)

    /** `(source, event) → 후보 transition 들` 의 색인. order 를 보존하기 위해 LinkedHashMap. */
    private val table: Map<Key, List<Transition>>

    init {
        val built = LinkedHashMap<Key, MutableList<Transition>>()
        for (t in all) {
            built.computeIfAbsent(Key(t.source, t.event)) { ArrayList() }.add(t)
        }
        // 외부 mutation 차단.
        val immutable = LinkedHashMap<Key, List<Transition>>()
        built.forEach { (k, v) -> immutable[k] = Collections.unmodifiableList(v) }
        this.table = Collections.unmodifiableMap(immutable)
    }

    /**
     * 주어진 source 상태에서 event 가 발생했을 때 어디로 가야 하는지 결정. 후보 transition
     * 의 guard 를 *순서대로* 평가 → 첫 통과의 target 반환. 후보가 없거나 모든 guard 가 false 면
     * [IllegalJobLifecycleTransitionException].
     *
     * 호출자는 이 결과를 받아서 도메인 메서드를 호출한다 — 이 메서드 자체는 도메인 객체를
     * 변경하지 않는다 (read-only).
     *
     * @param context guard / action 에 흘릴 도메인 컨텍스트 (보통 `Job` 인스턴스). null 가능.
     * @return 결정된 transition (가드 통과 후보 1개)
     */
    fun fire(source: JobStatus, event: JobLifecycleEvent, context: Any?): Transition {
        val candidates = table[Key(source, event)]
        if (candidates.isNullOrEmpty()) {
            throw IllegalJobLifecycleTransitionException(source, event)
        }
        for (t in candidates) {
            val g = t.guard
            if (g == null || g.test(source, event, context)) {
                t.action?.execute(source, event, t.target, context)
                return t
            }
        }
        throw IllegalJobLifecycleTransitionException(
            source, event, "모든 후보 transition 의 guard 가 false",
        )
    }

    /** 주어진 source 상태에서 발생 가능한 event 목록 (검증 / UI / 다이어그램 렌더용). */
    fun allowedEvents(source: JobStatus): List<JobLifecycleEvent> =
        table.keys.asSequence()
            .filter { it.source == source }
            .map { it.event }
            .distinct()
            .toList()

    /** 모든 transition (immutable view) — DOT / Mermaid export 용. */
    fun transitions(): List<Transition> = all

    private data class Key(val source: JobStatus, val event: JobLifecycleEvent)
}
