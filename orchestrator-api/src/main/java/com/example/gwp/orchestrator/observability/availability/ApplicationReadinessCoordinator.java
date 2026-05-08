package com.example.gwp.orchestrator.observability.availability;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 readiness 를 외부 의존성 상태에 따라 toggle. K8s readinessProbe 가
 * {@code /actuator/health/readiness} 를 호출했을 때 OUT_OF_SERVICE 가 응답되면
 * Service endpoint 에서 빠져 트래픽이 들어오지 않는다.
 *
 * <h3>왜 readiness 와 liveness 를 분리하나</h3>
 *
 * <p>K8s 의 두 probe 는 *목적이 다르다*. 같은 endpoint 로 합치면 운영 사고:</p>
 * <ul>
 *   <li><b>liveness</b> 가 fail → K8s 가 Pod 를 *재시작*. JVM 내부가 deadlock /
 *       OOM 위협 같은 회복 불능 상태일 때만 fail 해야 한다. 외부 의존성 (DB / Kafka /
 *       K8s API) 의 일시 장애로 liveness 가 fail 하면, 모든 Pod 가 동시에 재시작 되며
 *       cold start cascade — 회복 중인 backend 에 추가 부하가 한 번 더 몰린다.</li>
 *   <li><b>readiness</b> 가 fail → K8s 가 Service endpoint 에서 Pod 를 *제외*.
 *       프로세스는 살아있고, 의존성이 회복되면 자동으로 다시 endpoint 에 추가된다.
 *       *외부 의존성* 에 따른 가용성 신호가 여기 들어가야 한다.</li>
 * </ul>
 *
 * <h3>이 시스템의 readiness 정책</h3>
 *
 * <p>{@code unready} 트리거:</p>
 * <ul>
 *   <li>Kafka circuit breaker 가 OPEN — broker 가 영구 down 으로 판단된 상태.
 *       이 동안 들어온 잡 제출은 outbox 에 적재되지만 발행이 안 됨.
 *       readiness 빼서 트래픽 차단 → 호출자에게 즉시 503 → 클라이언트가 backoff retry.</li>
 *   <li>K8s API circuit breaker 가 OPEN — dispatch 자체가 불가능. Pod 가 트래픽을
 *       받아봐야 dispatch 가 fast-fail 로 떨어진다. unready 로 트래픽 빼는 게 깔끔.</li>
 * </ul>
 *
 * <p>{@code unready} *안* 트리거:</p>
 * <ul>
 *   <li>DB connection 일시 단절 — connection pool 이 retry 로 회복. 잠시 끊긴 정도로
 *       readiness 를 빼면, 배포 시 모든 Pod 가 *동시에* DB 첫 connection 을 잡는 순간 잠깐
 *       unready → 트래픽 안 들어옴 → 회복 → 다시 ready 의 churn (잦은 전환).
 *       Hibernate / HikariCP 가 transient 오류는 자체 retry 로 흡수.</li>
 *   <li>Redis 일시 끊김 — Redis 는 cache 용. cache miss 로 떨어져도 path 가 살아있다.</li>
 * </ul>
 *
 * <h3>liveness 정책 (이 클래스가 toggle 하지 않음)</h3>
 *
 * <p>liveness 는 *프로세스가 살아있는가* 만 답한다. Spring actuator 의 기본 LivenessStateHealthIndicator
 * 는 ApplicationContext 가 살아있고 startup 이 완료됐는지만 본다. 외부 의존성 / DB / Kafka 는
 * 일부러 안 본다. liveness 가 fail 하는 시나리오는 한정적:</p>
 * <ul>
 *   <li>JVM 이 deadlock 으로 응답 못 함 (HTTP thread 풀이 다 잠겨 actuator 도 응답 없음)</li>
 *   <li>OOM 후 좀비 상태</li>
 * </ul>
 *
 * <p>이 둘은 K8s 가 timeout 으로 자동 감지 → restart. ApplicationContext refresh 실패도
 * Spring 이 LivenessState.BROKEN 으로 자동 publish.</p>
 *
 * <h3>구현 메커니즘</h3>
 *
 * <p>Resilience4j 의 CircuitBreaker.EventPublisher 가 OPEN/CLOSED 전이를 이벤트로
 * publish. 이 클래스가 EventListener 로 구독해 {@link AvailabilityChangeEvent#publish}
 * 로 readiness state 를 바꾼다. Spring actuator 의 ReadinessStateHealthIndicator 가
 * 그 state 를 읽어 health 응답을 만든다 — 자동 wiring.</p>
 *
 * <h3>왜 Spring Cloud Kubernetes Health Indicator 같은 라이브러리 안 쓰나</h3>
 *
 * <p>Spring Cloud Kubernetes 가 K8sHealthIndicator 를 제공하지만, 우리는 fabric8
 * client 를 *직접* 사용 (ADR-0002). 의존성 한 줄 더 끌어오는 것보다 already 있는
 * Resilience4j 의 회로 상태를 readiness 신호로 재사용 — 이미 *호출 실패* 라는 1차
 * 데이터가 회로에 다 모여 있다. 회로 OPEN 자체가 *충분한 unready 신호*.</p>
 */
@Component
@Slf4j
public class ApplicationReadinessCoordinator {

    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationAvailability availability;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    public ApplicationReadinessCoordinator(ApplicationEventPublisher eventPublisher,
                                           ApplicationAvailability availability,
                                           CircuitBreakerRegistry circuitBreakerRegistry) {
        this.eventPublisher = eventPublisher;
        this.availability = availability;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * Resilience4j 회로 상태 listener 등록. 이미 회로가 OPEN 인 상태로 시작하는 경우는
     * 없으므로 (registry 가 starting 시 모두 CLOSED 로 init) 별도 초기 동기화 불필요.
     * 그러나 listener 부착 자체는 startup 직후 한 번 해야 한다.
     */
    @PostConstruct
    public void wireCircuitListeners() {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(this::wire);
        // 새 회로가 동적으로 추가되어도 follow — 보통 정적이지만 안전하게.
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(event -> wire((CircuitBreaker) event.getAddedEntry()));
        log.info("readiness coordinator armed — listening to {} circuit(s)",
                circuitBreakerRegistry.getAllCircuitBreakers().size());
    }

    private void wire(CircuitBreaker breaker) {
        breaker.getEventPublisher().onStateTransition(this::onTransition);
    }

    /**
     * OPEN / FORCED_OPEN 전이 시 unready, CLOSED / DISABLED 전이 시 ready 후보.
     * HALF_OPEN 은 회복 시도 중 — 아직 unready 유지 (READY 로 돌리는 결정은 CLOSED 시점).
     *
     * <p>여러 회로 중 *하나라도* OPEN 이면 unready. 모두 CLOSED 가 되어야 ready 로 복귀.
     * 이 정책이 보수적인 이유 — readiness 는 *모든 critical 의존성이 살아있을 때만 ready*.</p>
     */
    void onTransition(CircuitBreakerOnStateTransitionEvent event) {
        CircuitBreaker.State newState = event.getStateTransition().getToState();
        log.info("circuit '{}' transitioned {} → {}", event.getCircuitBreakerName(),
                event.getStateTransition().getFromState(), newState);
        recomputeReadiness();
    }

    /**
     * 모든 회로 상태를 재계산해 readiness 를 다시 publish. 회로가 추가되거나 사라져도
     * 가장 최신 상태 기준으로 ready/unready 결정.
     */
    void recomputeReadiness() {
        boolean anyOpen = circuitBreakerRegistry.getAllCircuitBreakers().stream()
                .anyMatch(cb -> cb.getState() == CircuitBreaker.State.OPEN
                        || cb.getState() == CircuitBreaker.State.FORCED_OPEN
                        || cb.getState() == CircuitBreaker.State.HALF_OPEN);

        ReadinessState target = anyOpen
                ? ReadinessState.REFUSING_TRAFFIC
                : ReadinessState.ACCEPTING_TRAFFIC;

        ReadinessState current = (ReadinessState) availability.getReadinessState();
        if (current != target) {
            log.warn("readiness change {} → {} (anyCircuitOpen={})", current, target, anyOpen);
            AvailabilityChangeEvent.publish(eventPublisher, this, target);
        }
    }

    /**
     * 외부에서 startup 이 끝나거나 명시적으로 ready 를 표시할 때 호출. 도메인 코드가
     * 직접 readiness 를 조작할 일은 거의 없지만, 운영 도구 / 통합 테스트가 사용.
     */
    public void markReady() {
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
    }

    /**
     * 명시적 unready — graceful shutdown 시작 시점 등에서 호출. preStop hook 의
     * sleep 직전에 unready 를 publish 해 service mesh 에 propagate 시킬 수 있다.
     */
    public void markUnready() {
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
    }

    /**
     * graceful shutdown 시작 시점에 외부 신호로 호출 — readiness 는 unready 로 빼되
     * liveness 는 그대로 유지 (프로세스는 in-flight 처리 위해 살아있어야 한다).
     */
    @EventListener
    public void onGracefulShutdownInitiated(GracefulShutdownInitiated event) {
        log.info("graceful shutdown initiated — flipping readiness to REFUSING_TRAFFIC");
        markUnready();
    }

    /** {@code GracefulShutdownLifecycle} 이 SmartLifecycle.stop() 진입 시 publish. */
    public record GracefulShutdownInitiated() {}

    /** liveness 가 BROKEN 으로 외부에서 publish 됐을 때를 대비한 guard — 로그만 남긴다.
     *  liveness 를 직접 toggle 하는 path 는 두지 않는다 (기본값: Spring 이 자동 관리). */
    @EventListener
    public void onLivenessChange(AvailabilityChangeEvent<LivenessState> event) {
        if (event.getState() == LivenessState.BROKEN) {
            log.error("liveness BROKEN — K8s liveness probe will fail and Pod will restart");
        }
    }
}
