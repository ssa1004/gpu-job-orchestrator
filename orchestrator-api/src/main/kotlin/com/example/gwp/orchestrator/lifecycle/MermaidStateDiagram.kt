package com.example.gwp.orchestrator.lifecycle

import com.example.gwp.orchestrator.domain.JobStatus
import java.util.TreeSet

/**
 * [JobLifecycleStateMachine] 의 transition table 을 Mermaid stateDiagram-v2 텍스트로
 * 출력. ADR / docs site 에 *코드와 동기화된 다이어그램* 을 박을 때 사용.
 *
 * ### 왜 Mermaid 인가
 * - GitHub README / docs 가 Mermaid 를 native 렌더 — 별도 도구 / 이미지 파일 없이 코드
 *   블록 안에서 그림이 나타난다.
 * - 텍스트라 git diff 가 의미 있는 변경 (transition 추가 / 삭제) 을 그대로 보여줌.
 * - plantUML 은 Java 의존이 추가로 필요, GraphViz/DOT 은 렌더 도구가 운영 외부.
 *
 * ### 출력 예
 * ```
 * stateDiagram-v2
 *     QUEUED --> DISPATCHING: DISPATCH
 *     DISPATCHING --> RUNNING: RUN
 *     RUNNING --> SUCCEEDED: SUCCEED
 *     ...
 * ```
 */
object MermaidStateDiagram {

    @JvmStatic
    fun render(machine: JobLifecycleStateMachine): String {
        val sb = StringBuilder("stateDiagram-v2\n")
        // *terminal 상태* 를 명시적으로 표시 — Mermaid 가 diagram 끝점을 그릴 수 있도록.
        val terminals = collectTerminalLabels(machine.transitions())
        terminals.forEach { label -> sb.append("    state ").append(label).append("\n") }

        for (t in machine.transitions()) {
            val guarded = if (t.guard != null) " (guard)" else ""
            sb.append("    ")
                .append(t.source.name)
                .append(" --> ")
                .append(t.target.name)
                .append(": ")
                .append(t.event.name)
                .append(guarded)
                .append("\n")
        }
        return sb.toString()
    }

    /**
     * transition table 의 *target* 중 어떤 source 로도 등장하지 않는 상태 = terminal.
     * 알파벳 순서로 정렬 (Mermaid 의 노드 등장 순서를 안정화).
     */
    private fun collectTerminalLabels(transitions: List<Transition>): Set<String> {
        val sources = TreeSet<JobStatus>()
        val targets = TreeSet<JobStatus>()
        for (t in transitions) {
            sources.add(t.source)
            targets.add(t.target)
        }
        val terminals = TreeSet<String>()
        for (s in targets) {
            if (s !in sources) terminals.add(s.name)
        }
        return terminals
    }
}
