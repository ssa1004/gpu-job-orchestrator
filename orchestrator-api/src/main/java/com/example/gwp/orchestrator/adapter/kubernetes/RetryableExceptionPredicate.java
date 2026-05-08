package com.example.gwp.orchestrator.adapter.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClientException;

import java.util.function.Predicate;

/**
 * 어떤 예외가 retry 가치가 있는지 결정. Resilience4j 가 retry 시도 직전 호출.
 *
 * <h3>retry 가치 있음</h3>
 * <ul>
 *   <li><b>5xx server error</b> — leader election 진행 중 (503), backend overload (502 / 504),
 *       internal error (500). transient — 다시 보내면 통과 가능.</li>
 *   <li><b>429 throttling</b> — K8s API server 가 client 호출 빈도 제한. exponential
 *       backoff 와 jitter 가 자연스럽게 풀어준다.</li>
 *   <li><b>0 / network 단절</b> — fabric8 가 code=0 으로 응답 — connect timeout / connection
 *       reset / TLS 핸드셰이크 실패. 다음 시도에서 통과 가능.</li>
 * </ul>
 *
 * <h3>retry 안 함</h3>
 * <ul>
 *   <li><b>4xx (429 제외)</b> — 400 (bad spec), 401 (auth 만료 — retry 도 똑같이 fail),
 *       403 (RBAC 거부), 404 (없음), 409 (충돌 — 같은 이름 중복), 422 (validation 실패).
 *       retry 해도 같은 결과 — 시간만 낭비.</li>
 * </ul>
 *
 * <p>이 predicate 는 application.yml 에 FQCN 으로 주입되어 Resilience4j 가 자체 인스턴스화.
 * Spring 빈이 아니라 *기본 생성자* 가 필수 — 그래서 record / no-arg constructor.</p>
 *
 * <p>왜 별 클래스인가 — application.yml 에 람다를 넣을 수 없고, retry-exceptions 만으로는
 * 'KubernetesClientException 중 4xx 는 제외' 같은 *부분집합* 표현이 불가능. 이 predicate
 * 가 retry-exceptions 의 후처리 필터 역할.</p>
 */
public class RetryableExceptionPredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof KubernetesClientException k8sEx) {
            return isRetryable(k8sEx.getCode());
        }
        // KubernetesClientException 외의 retry-exceptions (Kafka RetriableException 등) 는
        // Resilience4j 가 이미 한 번 걸러서 이 predicate 가 호출됐다는 건 *그 클래스* 라는 뜻.
        // 별도 status code 가 없는 transient 오류 — 그대로 retry.
        return true;
    }

    /**
     * HTTP status code 기반 retry 결정.
     *
     * <ul>
     *   <li>0 — fabric8 가 connection 단절 / unknown 으로 코딩한 sentinel. retry.</li>
     *   <li>5xx — server-side. retry.</li>
     *   <li>429 — throttling. retry (with jitter).</li>
     *   <li>그 외 4xx — client-side, 영구 실패. no retry.</li>
     * </ul>
     */
    static boolean isRetryable(int code) {
        if (code == 0) return true;
        if (code == 429) return true;
        if (code >= 500) return true;
        // 4xx (429 제외) — bad request / auth / not found / conflict 등 영구 실패.
        return false;
    }
}
