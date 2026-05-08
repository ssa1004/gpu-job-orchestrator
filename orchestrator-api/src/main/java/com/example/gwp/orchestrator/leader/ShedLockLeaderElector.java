package com.example.gwp.orchestrator.leader;

/**
 * ShedLock 기반 환경의 어댑터 — {@code @SchedulerLock} 자체가 메서드 단위 mutual
 * exclusion 을 보장하므로 별도 leader 개념이 필요 없다. 항상 {@code true} 를 돌려주고
 * 실제 직렬화는 ShedLock 의 DB 행 락이 담당.
 *
 * <p>이 어댑터의 목적은 <i>호출 측 (스케줄러) 코드 단일화</i> — 같은
 * {@code if (!leaderElector.isLeader()) return;} 패턴을 K8s / ShedLock 환경 모두에서
 * 쓸 수 있게 한다. ShedLock 환경에서는 매 tick 진입을 허용하고, ShedLock 이 한 인스턴스만
 * 통과시킨다.</p>
 *
 * <p>한 코드베이스에 두 메커니즘이 살아있어 어색해 보이지만, 이는 점진적 마이그레이션
 * 전략이다 — 운영 (prod) 은 K8s Lease, 로컬 dev / H2 환경은 ShedLock (K8s 없이도 부팅
 * 가능). ADR-0017 참고.</p>
 */
public final class ShedLockLeaderElector implements LeaderElector {

    @Override
    public boolean isLeader() {
        return true;
    }
}
