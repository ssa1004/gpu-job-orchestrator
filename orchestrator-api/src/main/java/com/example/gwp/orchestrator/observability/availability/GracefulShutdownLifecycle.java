package com.example.gwp.orchestrator.observability.availability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SIGTERM 수신 시 *가장 먼저* readiness 를 unready 로 빼서 service mesh 에 전파시키는
 * lifecycle bean. Spring Boot 의 graceful shutdown ({@code server.shutdown=graceful})
 * 보다 *앞 단계* 에서 동작해야 의미가 있다 — 새 트래픽이 오는 동안 in-flight 처리만
 * 기다리면, *그 사이* 에 도달하는 새 요청까지 모두 응답을 보내야 한다.
 *
 * <h3>graceful shutdown 의 단계 순서</h3>
 * <ol>
 *   <li><b>SIGTERM 수신</b> — K8s kubelet 이 보냄. 동시에 K8s 가 endpoint 를 빼는 *쪽도*
 *       시작하지만, service mesh / kube-proxy iptables 갱신 등에 네트워크 지연이 있다.</li>
 *   <li><b>이 lifecycle.stop() 진입</b> — readiness=REFUSING_TRAFFIC 으로 publish.
 *       /actuator/health/readiness 가 OUT_OF_SERVICE 응답.</li>
 *   <li><b>preStop sleep 5초</b> — service mesh / endpoint 갱신이 propagate 되기까지
 *       대기. 이 시간 동안 들어온 새 요청은 도착해도 아직 받음 (Spring 이 거절하지 않음).
 *       사용자 입장 차이 없음 — 로드밸런서 입장에서 이 Pod 는 아직 '있는 것'.</li>
 *   <li><b>Spring graceful shutdown</b> — 새 요청 거절 시작. in-flight 요청만 처리하고
 *       응답. {@code spring.lifecycle.timeout-per-shutdown-phase} 만큼 대기.</li>
 *   <li><b>스케줄러 / leader release / Kafka consumer commit</b> — 다른 SmartLifecycle
 *       bean 들 + @PreDestroy 들이 차례로 호출.</li>
 *   <li><b>JVM exit</b>. K8s 의 terminationGracePeriodSeconds (30s) 안에 끝나야 함.</li>
 * </ol>
 *
 * <p>이 lifecycle 의 phase 가 *낮을수록* (음수) 일찍 stop() 호출됨 (높을수록 일찍 start
 * 호출). {@link Integer#MIN_VALUE + 100} 으로 거의 가장 먼저 stop 되도록 설정 — readiness
 * 를 빼는 게 다른 cleanup 보다 *항상 먼저* 일어나야 한다.</p>
 *
 * <h3>왜 SmartLifecycle 이고 @PreDestroy 가 아닌가</h3>
 *
 * <p>@PreDestroy 는 ApplicationContext close 의 *마지막* 단계 — 이미 web container 가
 * 멈춘 후 호출. readiness 를 그때 빼봐야 의미 X (이미 들어올 트래픽 X). SmartLifecycle.stop()
 * 은 ApplicationContext close 의 *시작* 단계라 web container 가 살아있는 동안 readiness
 * publish 가 가능하고, /actuator/health/readiness 응답에 즉시 반영된다.</p>
 *
 * <h3>왜 K8s preStop hook 만으론 부족한가</h3>
 *
 * <p>preStop hook 의 sleep 만 있으면, 그 동안 *Spring 자체는* 여전히 ready 라고 답한다.
 * service mesh / kube-proxy 가 endpoint 갱신 중이라도 health-check 결과는 ready
 * 가 정답이라 이 Pod 가 endpoint 에서 빠지지 않을 수 있다. SmartLifecycle 로 readiness
 * 를 *Spring 안에서* 명시적으로 빼야 일관된 신호.</p>
 */
@Component
@Slf4j
public class GracefulShutdownLifecycle implements SmartLifecycle {

    /** 가장 먼저 stop() 호출되도록 — readiness 를 가장 빨리 빼야 한다. */
    private static final int PHASE = Integer.MIN_VALUE + 100;

    private final ApplicationEventPublisher eventPublisher;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GracefulShutdownLifecycle(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void start() {
        running.set(true);
        log.debug("graceful shutdown lifecycle armed (phase={})", PHASE);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.warn("SIGTERM/stop — initiating graceful shutdown sequence");
        eventPublisher.publishEvent(new ApplicationReadinessCoordinator.GracefulShutdownInitiated());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
