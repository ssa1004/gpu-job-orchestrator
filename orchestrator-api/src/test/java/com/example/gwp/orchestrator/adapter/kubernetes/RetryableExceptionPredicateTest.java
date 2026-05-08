package com.example.gwp.orchestrator.adapter.kubernetes;

import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * status code 별 retry 분류 검증.
 *
 * <p>요구사항:</p>
 * <ul>
 *   <li>5xx, 429, 0 (network) → retry</li>
 *   <li>4xx (429 제외) → no retry</li>
 *   <li>비-K8s 예외 (RetriableException 등) → 그대로 통과 (retry 가치 인정)</li>
 * </ul>
 */
class RetryableExceptionPredicateTest {

    private final RetryableExceptionPredicate predicate = new RetryableExceptionPredicate();

    @Test
    void retries_5xx() {
        assertThat(predicate.test(k8sExceptionWithCode(500))).isTrue();
        assertThat(predicate.test(k8sExceptionWithCode(502))).isTrue();
        assertThat(predicate.test(k8sExceptionWithCode(503))).isTrue();
        assertThat(predicate.test(k8sExceptionWithCode(504))).isTrue();
    }

    @Test
    void retries_429_throttling() {
        assertThat(predicate.test(k8sExceptionWithCode(429))).isTrue();
    }

    @Test
    void retries_zero_networkError() {
        // fabric8 가 connection 단절 / unknown 상태일 때 code=0 으로 코딩. retry 가치 있음.
        assertThat(predicate.test(k8sExceptionWithCode(0))).isTrue();
    }

    @Test
    void doesNotRetry_4xx_clientErrors() {
        assertThat(predicate.test(k8sExceptionWithCode(400))).isFalse(); // bad spec
        assertThat(predicate.test(k8sExceptionWithCode(401))).isFalse(); // auth
        assertThat(predicate.test(k8sExceptionWithCode(403))).isFalse(); // RBAC 거부
        assertThat(predicate.test(k8sExceptionWithCode(404))).isFalse(); // not found
        assertThat(predicate.test(k8sExceptionWithCode(409))).isFalse(); // conflict
        assertThat(predicate.test(k8sExceptionWithCode(422))).isFalse(); // validation 실패
    }

    @Test
    void retries_nonK8sException_byClass() {
        // application.yml 의 retry-exceptions 가 이미 통과시킨 예외 — predicate 도 retry.
        assertThat(predicate.test(new RuntimeException("transient"))).isTrue();
    }

    @Test
    void isRetryableHelper_directly() {
        assertThat(RetryableExceptionPredicate.isRetryable(0)).isTrue();
        assertThat(RetryableExceptionPredicate.isRetryable(429)).isTrue();
        assertThat(RetryableExceptionPredicate.isRetryable(500)).isTrue();
        assertThat(RetryableExceptionPredicate.isRetryable(599)).isTrue();
        assertThat(RetryableExceptionPredicate.isRetryable(400)).isFalse();
        assertThat(RetryableExceptionPredicate.isRetryable(404)).isFalse();
        // 200 / 304 같은 success / redirect 는 정상 응답이라 예외로 안 옴 — 안전 default false.
        assertThat(RetryableExceptionPredicate.isRetryable(200)).isFalse();
    }

    private static KubernetesClientException k8sExceptionWithCode(int code) {
        var status = new StatusBuilder()
                .withCode(code)
                .withMessage("synthetic " + code)
                .build();
        return new KubernetesClientException(status);
    }
}
