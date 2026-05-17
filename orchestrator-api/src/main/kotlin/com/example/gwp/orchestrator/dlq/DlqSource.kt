package com.example.gwp.orchestrator.dlq

/**
 * DLQ 메시지의 출처 (gpu-job-orchestrator 도메인의 saga 단계 단위).
 *
 * 한 DLQ 콘솔이 출처가 다른 메시지를 같이 노출하지만 *operation* 의 의미는 source 마다
 * 다르다 — replay 가 OutboxRelay polling 큐로 되돌려 보내는 것인지, 콜백 endpoint 를
 * 인공적으로 재호출하는 것인지, DAG eval 을 재실행하는 것인지가 모두 다르다. 그래서
 * 한 번에 한 source 만 bulk 조작하도록 강제 (notification ADR-0015 / billing ADR-0033 /
 * market ADR-0028 과 같은 패턴 — bulk 의 `source` enum 필수).
 *
 * - [JOB_DISPATCH] — KubernetesJobDispatcher 호출 실패. Pod 가 안 떠서 잡이
 *   QUEUED 에서 멈춤. K8s API 가 회복되면 replay 로 재dispatch.
 * - [CALLBACK] — worker → orchestrator 콜백이 4xx/5xx 로 거절되어 워커가 끝까지
 *   못 마무리한 경우. 워커의 retry queue 를 거쳐 DLQ 로 떨어진 메시지.
 *   replay 시 도메인 멱등성 (이미 종료된 잡 콜백은 ignored, RUNNING → RUNNING 도 no-op).
 * - [OUTBOX] — OutboxRelay 가 max-attempts (`gwp.outbox.relay.max-attempts`) 도달
 *   해서 `dead_lettered_at` 으로 격리한 row. replay = dead_lettered_at 을 NULL 로
 *   복구해 다음 polling tick 이 재발행.
 * - [PREEMPTION] — PreemptionService 의 victim 선정 / cancel 호출 실패. 같은
 *   잡에 대한 다음 preempt round 에서 자동으로 재시도되지만, stuck 상태가 길어지면
 *   콘솔에서 강제 retry.
 * - [DAG_EVAL] — parent 콜백 후 child 의 WAITING_DEPS → QUEUED 전이를 트리거
 *   못 한 경우 (DependencyResolutionService 의 onParentTerminal / scan 실패).
 *   replay 시 parent 상태 재검증 후 cascade.
 */
enum class DlqSource {
    JOB_DISPATCH,
    CALLBACK,
    OUTBOX,
    PREEMPTION,
    DAG_EVAL,
}
