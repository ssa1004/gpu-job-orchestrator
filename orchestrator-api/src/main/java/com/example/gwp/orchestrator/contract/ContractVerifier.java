package com.example.gwp.orchestrator.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Producer schema (catalog) 가 한 consumer 의 expectation 을 만족하는지 검증.
 *
 * <h3>Pact-style 의 핵심 원리</h3>
 * <ul>
 *   <li>verifier 는 *consumer 가 실제로 쓰는 필드* 만 본다 — producer 가 추가로 발행하는
 *       필드는 무시 (open-world). 그래서 producer 의 *backward-compat 한 필드 추가* 는
 *       fail 시키지 않는다.</li>
 *   <li>verifier 는 *없으면 안 되는 것* 만 fail 시킨다 — required 필드 누락, 알려진 enum
 *       값 제거. fail 메시지는 어떤 consumer 가 어떤 필드를 잃었는지 가리켜야 한다.</li>
 * </ul>
 *
 * <p>ADR-0020 참고.</p>
 */
public final class ContractVerifier {

    private ContractVerifier() {}

    /**
     * 한 expectation 을 producer 카탈로그에 대해 검증.
     *
     * @return 위반 메시지 목록. 비어 있으면 통과. 비어 있지 않으면 호출자 (테스트) 가 fail 처리.
     */
    public static List<String> verify(EventSchema producerSchema, ConsumerExpectation expectation) {
        if (!producerSchema.eventType().equals(expectation.eventType())) {
            return List.of("[%s] eventType mismatch: producer=%s consumer=%s".formatted(
                    expectation.consumerName(),
                    producerSchema.eventType(),
                    expectation.eventType()));
        }
        List<String> violations = new ArrayList<>();
        Map<String, Map<String, Object>> producerProps = producerSchema.properties();

        // 1) required field 검증.
        for (String field : expectation.requiredFields()) {
            if (!producerProps.containsKey(field)) {
                violations.add("[%s] %s — consumer 가 필수로 보는 필드 '%s' 가 producer schema 에 없음"
                        .formatted(expectation.consumerName(), expectation.eventType(), field));
            }
        }

        // 2) enum 값 검증 — consumer 가 알고 있는 값이 producer enum 에 있어야 한다.
        for (var entry : expectation.enumValues().entrySet()) {
            String field = entry.getKey();
            List<String> consumerValues = entry.getValue();
            Map<String, Object> fieldSchema = producerProps.get(field);
            if (fieldSchema == null) {
                violations.add("[%s] %s — enum 검증할 필드 '%s' 가 producer schema 에 없음"
                        .formatted(expectation.consumerName(), expectation.eventType(), field));
                continue;
            }
            Object enumNode = fieldSchema.get("enum");
            if (!(enumNode instanceof List<?> producerEnum)) {
                violations.add("[%s] %s — '%s' 가 producer 측에서 enum 이 아님 (consumer 는 enum 가정)"
                        .formatted(expectation.consumerName(), expectation.eventType(), field));
                continue;
            }
            for (String value : consumerValues) {
                if (!producerEnum.contains(value)) {
                    violations.add("[%s] %s.%s — consumer 가 알고 있는 enum 값 '%s' 가 producer 에 없음"
                            .formatted(expectation.consumerName(), expectation.eventType(),
                                    field, value));
                }
            }
        }
        return violations;
    }

    /**
     * 여러 consumer 의 expectation 을 카탈로그에 대해 일괄 검증.
     * 모든 expectation 의 위반을 모아서 한 번에 반환 — 한 번의 빌드로 모든 깨진 contract 가 보이도록.
     */
    public static List<String> verifyAll(List<EventSchema> producerCatalog,
                                         List<ConsumerExpectation> expectations) {
        List<String> all = new ArrayList<>();
        for (ConsumerExpectation exp : expectations) {
            EventSchema schema = producerCatalog.stream()
                    .filter(s -> s.eventType().equals(exp.eventType()))
                    .findFirst()
                    .orElse(null);
            if (schema == null) {
                all.add("[%s] %s — producer catalog 에 이 eventType 의 schema 가 없음"
                        .formatted(exp.consumerName(), exp.eventType()));
                continue;
            }
            all.addAll(verify(schema, exp));
        }
        return all;
    }
}
