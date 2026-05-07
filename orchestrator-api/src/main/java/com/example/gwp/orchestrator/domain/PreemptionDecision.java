package com.example.gwp.orchestrator.domain;

import java.util.List;
import java.util.Objects;

/**
 * Preemption 평가 결과 — 한 QUEUED 잡 (preemptor) 을 위해 어떤 RUNNING 잡들 (victims) 을
 * 죽일지 결정.
 *
 * <p>{@code victims} 가 비어 있으면 "preempt 할 필요 없음" — 이 경우 호출자는 그냥 일반
 * dispatch 시도. 비어 있지 않으면 victim 들을 markPreempted 한 후 GPU 가 비면 preemptor 를
 * dispatch.</p>
 *
 * <p>victim 선정 책임은 {@link PreemptionEvaluator} — 본 record 는 결과만 담는 단순 DTO.</p>
 */
public record PreemptionDecision(Job preemptor, List<Job> victims) {

    public PreemptionDecision {
        Objects.requireNonNull(preemptor);
        Objects.requireNonNull(victims);
        victims = List.copyOf(victims);
    }

    public static PreemptionDecision noop(Job preemptor) {
        return new PreemptionDecision(preemptor, List.of());
    }

    public boolean shouldPreempt() {
        return !victims.isEmpty();
    }

    public int totalGpuFreed() {
        return victims.stream().mapToInt(Job::getGpuCount).sum();
    }
}
