package com.example.gwp.orchestrator.leader

/**
 * 단일 인스턴스 환경 (로컬 dev / 단위 테스트) 용 fallback. 매 호출에 `true`.
 *
 * 다중 인스턴스 환경에서 이 구현을 쓰면 모든 인스턴스가 동시에 스케줄러를 돌려
 * 중복 실행이 발생한다 — 운영 (prod) 에서는 [KubernetesLeaseLeaderElector]
 * 또는 [ShedLockLeaderElector] 로 교체.
 */
class AlwaysLeaderElector : LeaderElector {

    override val isLeader: Boolean
        get() = true
}
