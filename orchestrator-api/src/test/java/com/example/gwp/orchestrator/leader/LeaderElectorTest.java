package com.example.gwp.orchestrator.leader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 두 fallback 구현 ({@link AlwaysLeaderElector}, {@link ShedLockLeaderElector}) 의
 * contract 검증. {@code KubernetesLeaseLeaderElector} 는 K8s API server / fabric8
 * watch 가 필요하므로 별도 통합 테스트 (envtest / kind cluster) 에서 검증.
 */
class LeaderElectorTest {

    @Test
    void alwaysLeader_alwaysReturnsTrue() {
        LeaderElector elector = new AlwaysLeaderElector();
        assertThat(elector.isLeader()).isTrue();
        // 멱등 — 여러 번 호출해도 같은 결과.
        assertThat(elector.isLeader()).isTrue();
    }

    @Test
    void shedLockLeader_alwaysReturnsTrue() {
        // ShedLock 환경에서는 매 tick 진입 후 @SchedulerLock 이 직렬화 — leader 게이트는
        // pass-through 역할만 한다.
        LeaderElector elector = new ShedLockLeaderElector();
        assertThat(elector.isLeader()).isTrue();
    }
}
