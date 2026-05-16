package com.example.gwp.orchestrator.contract

/**
 * Producer schema (catalog) 가 한 consumer 의 expectation 을 만족하는지 검증.
 *
 * Pact-style 의 핵심 원리:
 * - verifier 는 *consumer 가 실제로 쓰는 필드* 만 본다 — producer 가 추가로 발행하는
 *   필드는 무시 (open-world). 그래서 producer 의 *backward-compat 한 필드 추가* 는
 *   fail 시키지 않는다.
 * - verifier 는 *없으면 안 되는 것* 만 fail 시킨다 — required 필드 누락, 알려진 enum
 *   값 제거. fail 메시지는 어떤 consumer 가 어떤 필드를 잃었는지 가리켜야 한다.
 *
 * ADR-0020 참고.
 *
 * Java 호출자 (`ContractVerifier.verify(...)` / `ContractVerifier.verifyAll(...)`) 그대로 동작 —
 * Kotlin `object` 의 `@JvmStatic` 메서드가 같은 static 시그니처를 노출.
 */
object ContractVerifier {

    /**
     * 한 expectation 을 producer 카탈로그에 대해 검증.
     *
     * @return 위반 메시지 목록. 비어 있으면 통과. 비어 있지 않으면 호출자 (테스트) 가 fail 처리.
     */
    @JvmStatic
    fun verify(producerSchema: EventSchema, expectation: ConsumerExpectation): List<String> {
        if (producerSchema.eventType != expectation.eventType) {
            return listOf(
                "[${expectation.consumerName}] eventType mismatch: producer=${producerSchema.eventType} consumer=${expectation.eventType}",
            )
        }
        val violations = ArrayList<String>()
        val producerProps = producerSchema.properties

        // 1) required field 검증.
        for (field in expectation.requiredFields) {
            if (!producerProps.containsKey(field)) {
                violations.add(
                    "[${expectation.consumerName}] ${expectation.eventType} — consumer 가 필수로 보는 필드 '$field' 가 producer schema 에 없음",
                )
            }
        }

        // 2) enum 값 검증 — consumer 가 알고 있는 값이 producer enum 에 있어야 한다.
        for ((field, consumerValues) in expectation.enumValues) {
            val fieldSchema = producerProps[field]
            if (fieldSchema == null) {
                violations.add(
                    "[${expectation.consumerName}] ${expectation.eventType} — enum 검증할 필드 '$field' 가 producer schema 에 없음",
                )
                continue
            }
            val enumNode = fieldSchema["enum"]
            if (enumNode !is List<*>) {
                violations.add(
                    "[${expectation.consumerName}] ${expectation.eventType} — '$field' 가 producer 측에서 enum 이 아님 (consumer 는 enum 가정)",
                )
                continue
            }
            for (value in consumerValues) {
                if (!enumNode.contains(value)) {
                    violations.add(
                        "[${expectation.consumerName}] ${expectation.eventType}.$field — consumer 가 알고 있는 enum 값 '$value' 가 producer 에 없음",
                    )
                }
            }
        }
        return violations
    }

    /**
     * 여러 consumer 의 expectation 을 카탈로그에 대해 일괄 검증.
     * 모든 expectation 의 위반을 모아서 한 번에 반환 — 한 번의 빌드로 모든 깨진 contract 가 보이도록.
     */
    @JvmStatic
    fun verifyAll(
        producerCatalog: List<EventSchema>,
        expectations: List<ConsumerExpectation>,
    ): List<String> {
        val all = ArrayList<String>()
        for (exp in expectations) {
            val schema = producerCatalog.firstOrNull { it.eventType == exp.eventType }
            if (schema == null) {
                all.add(
                    "[${exp.consumerName}] ${exp.eventType} — producer catalog 에 이 eventType 의 schema 가 없음",
                )
                continue
            }
            all.addAll(verify(schema, exp))
        }
        return all
    }
}
