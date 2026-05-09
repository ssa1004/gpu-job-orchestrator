package com.example.gwp.orchestrator.contract;

import java.util.List;
import java.util.Map;

/**
 * 한 consumer 가 지금 사용 중인 필드 / 값을 그대로 적어 둔 입력. consumer-driven contract
 * test 의 fixture 가 된다.
 *
 * <h3>왜 expectation 을 consumer 가 적나</h3>
 * <ul>
 *   <li>Producer 는 자신이 *발행하는* 모든 필드를 안다. 하지만 consumer 가 그중 *어느 부분에
 *       의존하는지* 는 모른다 — 그래서 임의 필드를 마음대로 빼면 consumer 가 깨진다.</li>
 *   <li>consumer 가 "나는 이 필드들을 본다" 를 명시하면, producer 가 그 필드를 의도치 않게
 *       빼는 변경을 컴파일 후 단위 테스트에서 잡을 수 있다 — 운영 출시 전에.</li>
 *   <li>full Pact 도구는 broker / verifier / fixture 인프라가 무겁다. 우리는 같은 모노레포라
 *       expectations.json 한 파일로 충분 (in-repo CDC).</li>
 * </ul>
 *
 * <h3>지원 matcher</h3>
 * <ul>
 *   <li>{@link #requiredFields()} — 이 필드들이 producer schema 의 properties 에 *반드시
 *       존재* 해야 한다. 빠지면 fail.</li>
 *   <li>{@link #enumValues()} — 이 필드의 schema 가 enum 타입이면, consumer 가 알고 있는
 *       값들이 모두 producer enum 안에 있어야 한다. (즉 producer 가 enum 값을 마음대로
 *       빼면 fail.)</li>
 * </ul>
 *
 * <p>ADR-0020 참고.</p>
 *
 * @param consumerName 자유 식별자 (예: "worker", "billing-listener").
 * @param eventType   {@link EventCatalog} 의 eventType 과 매칭 (JobSubmitted 등).
 * @param requiredFields consumer 가 사용하는 필드 이름들.
 * @param enumValues  field 별 — consumer 가 "이 enum 값이 producer 에 있어야 한다" 고 주장하는 값들.
 *                    빈 map 이면 enum 검증 안 함.
 */
public record ConsumerExpectation(
        String consumerName,
        String eventType,
        List<String> requiredFields,
        Map<String, List<String>> enumValues
) {

    public ConsumerExpectation {
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType blank");
        }
        requiredFields = List.copyOf(requiredFields);
        enumValues = Map.copyOf(enumValues);
    }
}
