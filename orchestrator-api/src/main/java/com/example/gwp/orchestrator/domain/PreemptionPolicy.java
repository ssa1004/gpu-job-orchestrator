package com.example.gwp.orchestrator.domain;

/**
 * 이 Job 이 더 높은 우선순위 Job 에게 GPU 를 양보할 의사가 있는지.
 *
 * <p>예시 시나리오: H100 GPU 8장이 있는데 7장이 LOW 우선순위 학습 잡들에게 점유된 상태.
 * 사용자가 HIGH 우선순위 inference 잡을 제출 — GPU 부족. PREEMPTABLE 인 LOW 잡 1개를
 * 죽이고 자리 만들어 inference 잡을 dispatch.</p>
 *
 * <ul>
 *   <li>{@link #PREEMPTABLE} — 시스템이 필요 시 죽일 수 있음. 보통 *재시도 가능한* 작업
 *       (학습 / 배치 처리 / 시뮬레이션). 진행 분이 손실되어도 재실행하면 됨. 기본값.</li>
 *   <li>{@link #NEVER} — 절대 preempt 안 됨. 보통 *진행 중에 죽이면 손실이 큰* 작업
 *       (DB migration / 결제 처리 / 실시간 inference SLA 보장 등).
 *       NEVER 잡들이 GPU 점유 중이면 더 높은 우선순위 잡도 줄 서서 기다림.</li>
 * </ul>
 *
 * <p>Slurm 의 {@code --no-requeue} / K8s + Kueue 의 PriorityClass {@code preemptionPolicy: Never}
 * 와 같은 의미.</p>
 */
public enum PreemptionPolicy {
    PREEMPTABLE,
    NEVER
}
