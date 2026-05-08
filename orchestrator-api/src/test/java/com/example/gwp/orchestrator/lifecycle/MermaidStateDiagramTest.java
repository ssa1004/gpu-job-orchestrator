package com.example.gwp.orchestrator.lifecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mermaid 출력에 모든 transition 이 들어가는지 + terminal 표기가 정상인지 검증. docs 에
 * 박은 다이어그램이 *코드와 일치* 함을 회귀 방지.
 */
class MermaidStateDiagramTest {

    private final JobLifecycleStateMachine sm = JobLifecycleStateMachineFactory.build();

    @Test
    void render_includesEveryTransition() {
        String mermaid = MermaidStateDiagram.render(sm);

        // header 가 stateDiagram-v2.
        assertThat(mermaid).startsWith("stateDiagram-v2");

        // 핵심 transition 들이 들어가야 한다.
        assertThat(mermaid).contains("QUEUED --> DISPATCHING: DISPATCH");
        assertThat(mermaid).contains("DISPATCHING --> RUNNING: RUN");
        assertThat(mermaid).contains("RUNNING --> SUCCEEDED: SUCCEED");
        assertThat(mermaid).contains("WAITING_DEPS --> QUEUED: DEPENDENCIES_RESOLVED");
        assertThat(mermaid).contains("WAITING_DEPS --> CANCELLED: DEPENDENCIES_BROKEN");

        // PREEMPT 는 가드가 있으므로 (guard) 마커가 박힌다.
        assertThat(mermaid).contains("RUNNING --> PREEMPTED: PREEMPT (guard)");
    }

    @Test
    void render_marksTerminalStates() {
        String mermaid = MermaidStateDiagram.render(sm);

        // terminal: target 으로 등장하지만 source 로는 등장하지 않는 상태 — SUCCEEDED / FAILED / CANCELLED / PREEMPTED.
        assertThat(mermaid).contains("state SUCCEEDED");
        assertThat(mermaid).contains("state FAILED");
        assertThat(mermaid).contains("state CANCELLED");
        assertThat(mermaid).contains("state PREEMPTED");
    }
}
