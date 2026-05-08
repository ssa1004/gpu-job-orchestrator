package com.example.gwp.orchestrator.adapter.kubernetes;

import com.example.gwp.orchestrator.domain.Job;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link JobDispatcher} 의 circuit breaker 데코레이터. K8s API server 가 장애 / 응답 불능
 * 일 때 fast-fail 로 응답해서 hot-loop / 스레드 hang 을 막는다.
 *
 * <p><b>왜 데코레이터?</b> 실제 디스패처 ({@link KubernetesJobDispatcher}) 는 fabric8
 * client 를 직접 사용하는 라이브러리 호출이라 AOP 가 자연스럽게 안 붙는다.
 * 명시적 wrapper 로 break 의도가 코드에 드러나도록 한다.</p>
 *
 * <p><b>동작</b>:</p>
 * <ul>
 *   <li>OPEN — 일정 비율 이상 실패 시 차단. 그 시간 동안 호출은 즉시
 *       {@link CallNotPermittedException} → {@link JobDispatchException} 로 변환.</li>
 *   <li>HALF_OPEN — wait 시간 후 일부 호출 허용. 성공률 기준으로 CLOSED 또는 OPEN 결정.</li>
 *   <li>CLOSED — 정상.</li>
 * </ul>
 *
 * <p>임계값은 application.yml 의 {@code resilience4j.circuitbreaker.instances.k8s.*}
 * 에 둔다.</p>
 *
 * <p><b>실패 카운트 정책</b>: {@link JobDispatchException} (K8s API 실패) 는 카운트.
 * 도메인 검증 ({@code invalid gpuCount} 같은 클라이언트 잘못) 은 카운트 안 함 — backend
 * 가 살아 있어도 발생하므로 회로 차단 신호로 부적절. 이 데코레이터에서는 모든 예외를
 * 전달 (Resilience4j 의 ignore-exceptions 설정으로 분류).</p>
 */
@RequiredArgsConstructor
@Slf4j
public class ResilientJobDispatcher implements JobDispatcher {

    private final JobDispatcher delegate;
    private final CircuitBreaker circuitBreaker;

    @Override
    public String dispatch(Job job) {
        try {
            return circuitBreaker.executeSupplier(() -> delegate.dispatch(job));
        } catch (CallNotPermittedException e) {
            // OPEN — fast-fail. 운영자에게는 K8s API 회로가 차단됐다는 신호로 충분.
            log.warn("k8s circuit OPEN — refusing dispatch for job={}", job.getId());
            throw new JobDispatchException("k8s circuit breaker OPEN", e);
        }
    }

    @Override
    public void cancel(String k8sJobName) {
        try {
            circuitBreaker.executeRunnable(() -> delegate.cancel(k8sJobName));
        } catch (CallNotPermittedException e) {
            log.warn("k8s circuit OPEN — refusing cancel for name={}", k8sJobName);
            throw new JobDispatchException("k8s circuit breaker OPEN", e);
        }
    }
}
