package com.example.gwp.orchestrator.observability.baggage;

import java.util.Set;

/**
 * 이 시스템이 trace 전체에 전파하는 baggage 키 집합.
 *
 * <h3>baggage 가 traceId / span tag 와 다른 점</h3>
 * <ul>
 *   <li><b>traceId / spanId</b> — 한 trace 안의 *위치* 를 가리키는 식별자. 자동 전파.
 *       크기 고정 (32hex / 16hex). 의미는 unique id 뿐.</li>
 *   <li><b>span tag</b> — 한 *span 에만* 붙는 key/value. trace 전파 X. 직렬화 / dump 시
 *       그 span 의 attribute.</li>
 *   <li><b>baggage</b> — span 단위가 아니라 *전체 trace 동안 살아 있는* key/value. 자동
 *       전파 (HTTP 헤더 / Kafka 헤더 / etc). consumer 측에서 *지금 흐름의 owner 가 누구인지*
 *       를 알아야 할 때 — log / metric tag 로도 같이 박힌다.</li>
 * </ul>
 *
 * <h3>왜 owner / cost-center / priority 인가</h3>
 * <ul>
 *   <li><b>owner</b> — 가장 자주 쓰는 cross-system 검색 차원. "이 owner 의 요청들이 전부
 *       어디서 느려?" 같은 질문이 trace + log + metric 한 화면에서 풀린다.</li>
 *   <li><b>cost-center</b> — 회계 / 부서 / 팀 라벨. 빌링 / 리포팅이 이걸로 grouping.</li>
 *   <li><b>priority</b> — 잡 priority 가 baggage 에 있으면 worker / dispatcher 의 metric
 *       이 자동으로 priority 로 splittable.</li>
 * </ul>
 *
 * <h3>왜 sensitive 정보를 baggage 에 두지 않나</h3>
 * <p>baggage 는 외부 backend (Kafka broker / 다른 마이크로서비스) 로 *그대로* 전파된다.
 * 토큰 / PII / 비밀 키가 baggage 에 들어가면 모든 hop 에 노출. 그래서 이 클래스가 *허용
 * 키 화이트리스트* 로 제한 — 알 수 없는 키는 인터셉터에서 거절.</p>
 */
public final class JobBaggage {

    /** 잡 / 요청의 사용자. JWT subject 와 동일. */
    public static final String OWNER = "owner";

    /** 부서 / 팀 / 회계 라벨 (예: {@code research-vision}, {@code platform-team}). */
    public static final String COST_CENTER = "cost-center";

    /** 잡 priority — log / metric 라벨로도 노출. */
    public static final String PRIORITY = "priority";

    /**
     * 화이트리스트. {@link BaggageRequestInterceptor} 와 outbox / Kafka header injection 이
     * 이 집합 외의 키를 silent drop 하여 sensitive payload 의 우발적 전파를 막는다.
     */
    public static final Set<String> ALLOWED = Set.of(OWNER, COST_CENTER, PRIORITY);

    /**
     * baggage 한 entry 의 값 길이 상한. baggage 는 매 hop 마다 헤더로 직렬화되어 network
     * overhead 가 증가하므로 작게 cap.
     */
    public static final int MAX_VALUE_LENGTH = 128;

    private JobBaggage() {}

    /**
     * 길이 / 화이트리스트 검증. 부적합하면 false — 호출자 (인터셉터) 가 silent skip.
     */
    public static boolean isAllowed(String key, String value) {
        if (key == null || value == null) return false;
        if (!ALLOWED.contains(key)) return false;
        if (value.isBlank()) return false;
        return value.length() <= MAX_VALUE_LENGTH;
    }
}
