package com.example.gwp.orchestrator.lifecycle;

import com.example.gwp.orchestrator.domain.JobStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Job 라이프사이클의 *외부 검증 + 기록 + 시각화* 를 담당하는 transition table.
 *
 * <h3>왜 이 클래스가 필요한가</h3>
 * <ul>
 *   <li>도메인 ({@link com.example.gwp.orchestrator.domain.Job}) 의 mark* 메서드는 *기계적
 *       전이* 를 적용하지만, *허용되는 모든 전이의 catalog* 는 메서드들 사이에 흩어져 있다 —
 *       어떤 도메인 메서드가 어떤 source 에서 호출 가능한지 한눈에 안 보인다.</li>
 *   <li>이 상태 머신이 그 catalog 를 *데이터* 로 표현 — 운영자 / 신규 개발자가 한 곳에서
 *       라이프사이클 전체를 본다. 다이어그램 export, 자동 검증, audit log 모두 같은 데이터에서.</li>
 *   <li>새 transition 추가 시 (예: timeout retry, manual requeue) {@link Transition} 한 줄
 *       추가만으로 끝 — 도메인 메서드의 if-else 가지 늘어나는 것보다 깔끔.</li>
 * </ul>
 *
 * <h3>도메인 메서드와의 관계</h3>
 * <p>이 상태 머신은 *도메인 메서드를 대체하지 않는다*. lifecycle 서비스는 여전히
 * {@code Job.markRunning(clock)} 같은 도메인 호출로 실제 상태를 바꾼다. 이 상태 머신은
 * 그 호출 직전에 {@link #fire} 로 *전이가 정의되어 있는지* 만 검증하고, audit log /
 * 메트릭 / 시각화 정보를 기록한다.</p>
 *
 * <h3>왜 Spring StateMachine 라이브러리를 안 쓰는가 — ADR-0022 참고</h3>
 */
public class JobLifecycleStateMachine {

    /** {@code (source, event) → 후보 transition 들} 의 색인. order 를 보존하기 위해 LinkedHashMap. */
    private final Map<Key, List<Transition>> table;
    private final List<Transition> all;

    public JobLifecycleStateMachine(List<Transition> transitions) {
        this.all = List.copyOf(transitions);
        Map<Key, List<Transition>> built = new LinkedHashMap<>();
        for (Transition t : this.all) {
            built.computeIfAbsent(new Key(t.source(), t.event()), k -> new ArrayList<>()).add(t);
        }
        // 외부 mutation 차단.
        Map<Key, List<Transition>> immutable = new LinkedHashMap<>();
        built.forEach((k, v) -> immutable.put(k, Collections.unmodifiableList(v)));
        this.table = Collections.unmodifiableMap(immutable);
    }

    /**
     * 주어진 source 상태에서 event 가 발생했을 때 어디로 가야 하는지 결정. 후보 transition
     * 의 guard 를 *순서대로* 평가 → 첫 통과의 target 반환. 후보가 없거나 모든 guard 가 false 면
     * {@link IllegalJobLifecycleTransitionException}.
     *
     * <p>호출자는 이 결과를 받아서 도메인 메서드를 호출한다 — 이 메서드 자체는 도메인 객체를
     * 변경하지 않는다 (read-only).</p>
     *
     * @param context guard / action 에 흘릴 도메인 컨텍스트 (보통 {@code Job} 인스턴스). null 가능.
     * @return 결정된 transition (가드 통과 후보 1개)
     */
    public Transition fire(JobStatus source, JobLifecycleEvent event, Object context) {
        List<Transition> candidates = table.get(new Key(source, event));
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalJobLifecycleTransitionException(source, event);
        }
        for (Transition t : candidates) {
            Transition.Guard g = t.guard();
            if (g == null || g.test(source, event, context)) {
                if (t.action() != null) {
                    t.action().execute(source, event, t.target(), context);
                }
                return t;
            }
        }
        throw new IllegalJobLifecycleTransitionException(source, event,
                "모든 후보 transition 의 guard 가 false");
    }

    /** 주어진 source 상태에서 발생 가능한 event 목록 (검증 / UI / 다이어그램 렌더용). */
    public List<JobLifecycleEvent> allowedEvents(JobStatus source) {
        return table.keySet().stream()
                .filter(k -> k.source.equals(source))
                .map(Key::event)
                .distinct()
                .toList();
    }

    /** 모든 transition (immutable view) — DOT / Mermaid export 용. */
    public List<Transition> transitions() {
        return all;
    }

    private record Key(JobStatus source, JobLifecycleEvent event) {}
}
