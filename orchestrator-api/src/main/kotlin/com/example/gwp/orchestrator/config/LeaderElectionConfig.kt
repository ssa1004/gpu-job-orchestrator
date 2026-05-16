package com.example.gwp.orchestrator.config

import com.example.gwp.orchestrator.config.properties.GwpProperties
import com.example.gwp.orchestrator.leader.AlwaysLeaderElector
import com.example.gwp.orchestrator.leader.KubernetesLeaseLeaderElector
import com.example.gwp.orchestrator.leader.LeaderElector
import com.example.gwp.orchestrator.leader.ShedLockLeaderElector
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration

/**
 * Leader election 빈 wiring — `gwp.leader.mode` 값에 따라 구현체를 골라 등록.
 *
 * ### 모드
 * - `lease` — K8s Lease 기반 ([KubernetesLeaseLeaderElector]). 운영 (prod).
 *   [KubernetesClient] 빈이 함께 있어야 함 (`gwp.kubernetes.enabled=true`).
 *   클라이언트가 없으면 startup 실패 — dev 환경이라면 mode 를 shedlock 로 두자.
 * - `shedlock` — 기존 ShedLock 동작 그대로 ([ShedLockLeaderElector]).
 *   매 tick 진입 허용, ShedLock 의 DB 행 락이 직렬화. 단일 인스턴스 dev 는 이 모드 추천.
 * - 그 외 / 누락 — [AlwaysLeaderElector] (test 용 fallback). 매 tick true.
 *
 * K8s Lease 모드를 켜려면 추가로 RBAC 가 필요. `orchestrator-api/k8s/` 에 있는
 * Role / RoleBinding 매니페스트 참고 — `coordination.k8s.io/leases` 리소스에
 * `get/list/watch/create/update` 권한.
 */
@Configuration
class LeaderElectionConfig {

    /**
     * 모드별 구현 선택. `KubernetesClient` 는 [ObjectProvider] 로 받아 lease
     * 모드가 아닐 때 client 가 없어도 부팅 가능. lease 모드에서 client 가 없으면 명시적 에러.
     */
    @Bean
    @ConditionalOnMissingBean(LeaderElector::class)
    open fun leaderElector(
        properties: GwpProperties,
        kubernetesClient: ObjectProvider<KubernetesClient>,
    ): LeaderElector {
        val leader = properties.leader
        if ("lease".equals(leader.mode, ignoreCase = true)) {
            val client = kubernetesClient.ifAvailable
                ?: throw IllegalStateException(
                    "gwp.leader.mode=lease 인데 KubernetesClient 빈이 없다 — " +
                        "gwp.kubernetes.enabled=true 로 켜거나 mode 를 shedlock 으로 바꾸자",
                )
            var identity = leader.identity
            if (identity.isNullOrBlank()) {
                identity = resolveHostname()
            }
            log.info(
                "leader-election mode=lease — using K8s Lease (ns={}, lease={}, identity={})",
                leader.namespace, leader.leaseName, identity,
            )
            return KubernetesLeaseLeaderElector(
                client,
                leader.namespace,
                leader.leaseName,
                identity,
                Duration.ofSeconds(leader.leaseDurationSeconds),
                Duration.ofSeconds(leader.renewDeadlineSeconds),
                Duration.ofSeconds(leader.retryPeriodSeconds),
            )
        }
        if ("shedlock".equals(leader.mode, ignoreCase = true)) {
            log.info("leader-election mode=shedlock — relying on @SchedulerLock for mutual exclusion")
            return ShedLockLeaderElector()
        }
        log.warn(
            "leader-election mode={} — fallback AlwaysLeaderElector (every tick is leader). " +
                "Use 'lease' or 'shedlock' in multi-instance environments.",
            leader.mode,
        )
        return AlwaysLeaderElector()
    }

    companion object {

        private val log = LoggerFactory.getLogger(LeaderElectionConfig::class.java)

        private fun resolveHostname(): String =
            try {
                InetAddress.getLocalHost().hostName
            } catch (_: UnknownHostException) {
                "unknown-host"
            }
    }
}
