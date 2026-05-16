package com.example.gwp.orchestrator.leader

/**
 * 다중 인스턴스 환경에서 *지금 이 인스턴스가 리더인지* 알려주는 단일 추상화.
 *
 * 스케줄러 (`OutboxRelay` / `PreemptionScheduler` / `DependencyScanScheduler`)
 * 들이 매 tick 마다 [isLeader] 를 묻고, `true` 일 때만 실제 작업을 수행한다.
 * 비-리더는 즉시 return — 락 경쟁 / DB row lock 같은 비용을 매 tick 마다 치를 필요가 없다.
 *
 * ### 구현체
 * - [KubernetesLeaseLeaderElector] — K8s `coordination.k8s.io/Lease` 기반.
 *   fabric8 의 `LeaderElector` 가 별도 스레드에서 lease 갱신 / 감시. K8s native
 *   라 외부 의존성 0.
 * - [AlwaysLeaderElector] — 단일 인스턴스 dev / 단위 테스트용. 항상 true.
 * - [ShedLockLeaderElector] — 기존 ShedLock 동작을 보존하기 위한 어댑터.
 *   `@SchedulerLock` 자체가 메서드 단위 mutual exclusion 을 보장하므로
 *   [isLeader] 는 항상 true 를 돌려준다 (하위 호환).
 *
 * ### 왜 별도 인터페이스
 * K8s 가 없는 환경 (로컬 dev / 테스트 / 단일 PC) 에서도 같은 코드가 돌아야 한다 —
 * fabric8 LeaderElector 를 직접 import 하면 K8s API server 없이는 부팅조차 불가능.
 * 이 인터페이스로 추상화하면 `@Profile` / `@ConditionalOnProperty` 만으로
 * 환경별 구현 교체 가능.
 *
 * Java 호출자 (`leaderElector.isLeader()`) 그대로 동작 — Kotlin `is*` prefix 의 `val`
 * 은 boolean accessor 컨벤션 (`isLeader()`) 으로 노출된다.
 */
interface LeaderElector {

    /**
     * 지금 이 인스턴스가 리더인가. 매 호출이 가벼워야 함 (스케줄러 tick 마다 호출됨) —
     * 내부적으로 cached volatile flag 만 읽도록 구현.
     */
    val isLeader: Boolean
}
