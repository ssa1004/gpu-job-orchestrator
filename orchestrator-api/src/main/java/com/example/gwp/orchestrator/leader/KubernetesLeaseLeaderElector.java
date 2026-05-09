package com.example.gwp.orchestrator.leader;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfigBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * K8s {@code coordination.k8s.io/v1/Lease} 리소스를 사용하는 leader election.
 *
 * <h3>왜 K8s Lease 인가</h3>
 *
 * <p>이 시스템은 이미 K8s 위에 배포되고 fabric8 client / RBAC / namespace 가 다 갖춰져
 * 있다. K8s 자체가 합의된 분산 락 서비스를 무료로 제공한다. etcd 가 backend 라 Raft
 * consensus 기반의 강한 일관성을 보장한다. K8s 컨트롤 플레인의 표준 컴포넌트들도 같은
 * 메커니즘으로 leader 를 선출한다.</p>
 *
 * <p>기존 ShedLock (DB row 락) 대비 장점:</p>
 * <ul>
 *   <li><b>K8s 가 leader liveness 자동 감지</b> — Pod 가 죽으면 (OOM / node failure /
 *       graceful shutdown) lease 갱신이 멈추고 lease 만료 후 즉시 다른 Pod 가 takeover.
 *       ShedLock 은 lock_until 까지 기다려야 함 — DB 가 죽음 자체를 모른다.</li>
 *   <li><b>watch 기반 즉시 전환</b> — fabric8 LeaderElector 가 lease 변화를 watch 로
 *       감시 → 만료 즉시 acquire 시도. polling 지연 없음.</li>
 *   <li><b>외부 의존성 0</b> — 이미 있는 K8s API server / kubelet / etcd 만 사용.
 *       DB 가 down 이어도 leader election 은 살아있다.</li>
 *   <li><b>운영 가시성</b> — {@code kubectl get lease -n gwp gwp-orchestrator-leader}
 *       으로 *지금 누가 리더인지* 한 줄로 확인.</li>
 * </ul>
 *
 * <h3>표준 시간값 (k8s 컨벤션)</h3>
 * <pre>
 *   lease-duration   = 15s   리더가 lease 를 보유한다고 주장하는 유효 기간
 *   renew-deadline   = 10s   현 리더가 이 시간 내에 갱신해야 함 (실패 = 리더십 잃음)
 *   retry-period     =  2s   비-리더가 lease 를 잡으려 시도하는 주기
 * </pre>
 *
 * <p>이 비율 (15 / 10 / 2) 은 client-go 권장값으로 검증된 수치. lease-duration 이 너무
 * 짧으면 (5s) network blip 한 번에 leader 가 바뀌어 churn (잦은 전환), 너무 길면 (60s)
 * takeover 가 느려 SLA 영향. 15s 가 합리적 트레이드오프.</p>
 *
 * <h3>스레드 모델</h3>
 *
 * <p>fabric8 LeaderElector 는 별도 단일 스레드에서 lease 갱신 / 감시. 메인 비즈니스 스레드는
 * {@link #isLeader()} 로 volatile flag 만 읽음 — 매 호출 nanosecond 단위. callback
 * (onStartLeading / onStopLeading) 이 와야만 flag 가 true / false 로 전환된다.</p>
 *
 * <h3>fail-safe</h3>
 *
 * <p>K8s API server 가 일시적으로 끊기면 lease 갱신 실패 → onStopLeading 호출 →
 * {@code leader=false} 로 전환. 그동안 스케줄러는 비-리더로 동작 (즉, no-op). API server
 * 회복되면 다음 retry-period 안에 다시 acquire 시도. 잘못된 leadership claim 이 일어나지
 * 않게 보수적으로 false 를 유지하는 게 핵심 — 두 인스턴스가 동시에 리더라고 믿는 split-brain
 * 을 방지.</p>
 */
@Slf4j
public class KubernetesLeaseLeaderElector implements LeaderElector {

    private final KubernetesClient client;
    private final String namespace;
    private final String leaseName;
    private final String identity;
    private final Duration leaseDuration;
    private final Duration renewDeadline;
    private final Duration retryPeriod;

    /** 리더 여부 — leader callback 스레드에서 set, 비즈니스 스레드에서 read. */
    private final AtomicBoolean leader = new AtomicBoolean(false);

    /** fabric8 LeaderElector 의 long-running task. {@link #stop()} 에서 release 신호로 cancel. */
    private CompletableFuture<?> running;

    /** 단일 스레드 executor — fabric8 가 렌즈 위에서 lease 갱신을 돌릴 곳. */
    private ScheduledExecutorService executor;

    public KubernetesLeaseLeaderElector(KubernetesClient client,
                                        String namespace,
                                        String leaseName,
                                        String identity,
                                        Duration leaseDuration,
                                        Duration renewDeadline,
                                        Duration retryPeriod) {
        this.client = client;
        this.namespace = namespace;
        this.leaseName = leaseName;
        this.identity = identity;
        this.leaseDuration = leaseDuration;
        this.renewDeadline = renewDeadline;
        this.retryPeriod = retryPeriod;
    }

    @PostConstruct
    public void start() {
        // pod 단위 daemon 스레드 — graceful shutdown 시 release 시도와 별개로 JVM exit 차단 방지.
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "k8s-leader-elector-" + leaseName);
            t.setDaemon(true);
            return t;
        });

        LeaseLock lock = new LeaseLock(namespace, leaseName, identity);

        LeaderCallbacks callbacks = new LeaderCallbacks(
                () -> {
                    leader.set(true);
                    log.info("leader-election: this instance ({}) became LEADER for lease={}",
                            identity, leaseName);
                },
                () -> {
                    leader.set(false);
                    log.warn("leader-election: this instance ({}) STOPPED leading lease={}",
                            identity, leaseName);
                },
                newLeader -> log.debug("leader-election: lease={} new leader = {}",
                        leaseName, newLeader)
        );

        LeaderElectionConfig config = new LeaderElectionConfigBuilder()
                .withName(leaseName)
                .withLeaseDuration(leaseDuration)
                .withRenewDeadline(renewDeadline)
                .withRetryPeriod(retryPeriod)
                .withReleaseOnCancel(true)         // graceful shutdown 시 lease 즉시 양보
                .withLock(lock)
                .withLeaderCallbacks(callbacks)
                .build();

        var elector = client.leaderElector().withConfig(config).build();
        // start() 는 비동기 — CompletableFuture 가 종료 시점을 들고 있다가 stop() 에서 cancel.
        // executor 를 직접 LeaderElector 에 넘기지는 않지만 (fabric8 6.x 가 내부 executor
        // 를 자체 관리), 향후 직접 주입 path 가 열리면 여기에 추가.
        this.running = elector.start();
        log.info("leader-election: started for lease={} ns={} identity={} duration={} renew={} retry={}",
                leaseName, namespace, identity, leaseDuration, renewDeadline, retryPeriod);
    }

    @PreDestroy
    public void stop() {
        if (running != null) {
            // releaseOnCancel=true 이므로 cancel → fabric8 가 lease 를 명시적으로 release.
            // 다른 인스턴스가 lease-duration 만큼 기다릴 필요 없이 즉시 takeover 가능.
            running.cancel(true);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        leader.set(false);
        log.info("leader-election: stopped for lease={} identity={}", leaseName, identity);
    }

    @Override
    public boolean isLeader() {
        return leader.get();
    }
}
