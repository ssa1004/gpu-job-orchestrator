package com.example.gwp.orchestrator.lifecycle;

import com.example.gwp.orchestrator.domain.JobStatus;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link JobLifecycleStateMachine} 의 transition table 을 Mermaid stateDiagram-v2 텍스트로
 * 출력. ADR / docs site 에 *코드와 동기화된 다이어그램* 을 박을 때 사용.
 *
 * <h3>왜 Mermaid 인가</h3>
 * <ul>
 *   <li>GitHub README / docs 가 Mermaid 를 native 렌더 — 별도 도구 / 이미지 파일 없이 코드
 *       블록 안에서 그림이 나타난다.</li>
 *   <li>텍스트라 git diff 가 의미 있는 변경 (transition 추가 / 삭제) 을 그대로 보여줌.</li>
 *   <li>plantUML 은 Java 의존이 추가로 필요, GraphViz/DOT 은 렌더 도구가 운영 외부.</li>
 * </ul>
 *
 * <h3>출력 예</h3>
 * <pre>
 * stateDiagram-v2
 *     QUEUED --> DISPATCHING: DISPATCH
 *     DISPATCHING --> RUNNING: RUN
 *     RUNNING --> SUCCEEDED: SUCCEED
 *     ...
 * </pre>
 */
public final class MermaidStateDiagram {

    private MermaidStateDiagram() {}

    public static String render(JobLifecycleStateMachine machine) {
        StringBuilder sb = new StringBuilder("stateDiagram-v2\n");
        // *terminal 상태* 를 명시적으로 표시 — Mermaid 가 diagram 끝점을 그릴 수 있도록.
        Set<String> terminals = collectTerminalLabels(machine.transitions());
        terminals.forEach(label -> sb.append("    state ").append(label).append("\n"));

        for (Transition t : machine.transitions()) {
            String guarded = t.guard() != null ? " (guard)" : "";
            sb.append("    ")
                    .append(t.source().name())
                    .append(" --> ")
                    .append(t.target().name())
                    .append(": ")
                    .append(t.event().name())
                    .append(guarded)
                    .append("\n");
        }
        return sb.toString();
    }

    /**
     * transition table 의 *target* 중 어떤 source 로도 등장하지 않는 상태 = terminal.
     * 알파벳 순서로 정렬 (Mermaid 의 노드 등장 순서를 안정화).
     */
    private static Set<String> collectTerminalLabels(List<Transition> transitions) {
        Set<JobStatus> sources = new TreeSet<>();
        Set<JobStatus> targets = new TreeSet<>();
        for (Transition t : transitions) {
            sources.add(t.source());
            targets.add(t.target());
        }
        Set<String> terminals = new TreeSet<>();
        for (JobStatus s : targets) {
            if (!sources.contains(s)) terminals.add(s.name());
        }
        return terminals;
    }
}
