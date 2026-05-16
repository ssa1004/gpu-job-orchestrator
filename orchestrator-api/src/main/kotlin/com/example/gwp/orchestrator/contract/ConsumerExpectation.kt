package com.example.gwp.orchestrator.contract

import java.util.Objects

/**
 * 한 consumer 가 지금 사용 중인 필드 / 값을 그대로 적어 둔 입력. consumer-driven contract
 * test 의 fixture 가 된다.
 *
 * 왜 expectation 을 consumer 가 적나:
 * - Producer 는 자신이 *발행하는* 모든 필드를 안다. 하지만 consumer 가 그중 *어느 부분에
 *   의존하는지* 는 모른다 — 그래서 임의 필드를 마음대로 빼면 consumer 가 깨진다.
 * - consumer 가 "나는 이 필드들을 본다" 를 명시하면, producer 가 그 필드를 의도치 않게
 *   빼는 변경을 컴파일 후 단위 테스트에서 잡을 수 있다 — 운영 출시 전에.
 * - full Pact 도구는 broker / verifier / fixture 인프라가 무겁다. 우리는 같은 모노레포라
 *   expectations.json 한 파일로 충분 (in-repo CDC).
 *
 * 지원 matcher:
 * - [requiredFields] — 이 필드들이 producer schema 의 properties 에 *반드시
 *   존재* 해야 한다. 빠지면 fail.
 * - [enumValues] — 이 필드의 schema 가 enum 타입이면, consumer 가 알고 있는
 *   값들이 모두 producer enum 안에 있어야 한다. (즉 producer 가 enum 값을 마음대로
 *   빼면 fail.)
 *
 * ADR-0020 참고.
 *
 * `@JvmRecord` 의 compact constructor 가 `requiredFields = List.copyOf(requiredFields)` 같은
 * 재대입을 지원하지 않아, 일반 class + custom equals/hashCode + `@get:JvmName` accessor 로
 * Java record-style 호환을 유지한다 (Java: `exp.consumerName()` / `exp.requiredFields()` 그대로).
 *
 * @param consumerName 자유 식별자 (예: "worker", "billing-listener").
 * @param eventType   [EventCatalog] 의 eventType 과 매칭 (JobSubmitted 등).
 * @param requiredFields consumer 가 사용하는 필드 이름들.
 * @param enumValues  field 별 — consumer 가 "이 enum 값이 producer 에 있어야 한다" 고 주장하는 값들.
 *                    빈 map 이면 enum 검증 안 함.
 */
class ConsumerExpectation(
    consumerName: String,
    eventType: String,
    requiredFields: List<String>,
    enumValues: Map<String, List<String>>,
) {

    @get:JvmName("consumerName")
    val consumerName: String

    @get:JvmName("eventType")
    val eventType: String

    @get:JvmName("requiredFields")
    val requiredFields: List<String>

    @get:JvmName("enumValues")
    val enumValues: Map<String, List<String>>

    init {
        require(consumerName.isNotBlank()) { "consumerName blank" }
        require(eventType.isNotBlank()) { "eventType blank" }
        this.consumerName = consumerName
        this.eventType = eventType
        this.requiredFields = java.util.List.copyOf(requiredFields)
        this.enumValues = java.util.Map.copyOf(enumValues)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConsumerExpectation) return false
        return consumerName == other.consumerName &&
            eventType == other.eventType &&
            requiredFields == other.requiredFields &&
            enumValues == other.enumValues
    }

    override fun hashCode(): Int =
        Objects.hash(consumerName, eventType, requiredFields, enumValues)

    override fun toString(): String =
        "ConsumerExpectation[consumerName=$consumerName, eventType=$eventType, " +
            "requiredFields=$requiredFields, enumValues=$enumValues]"
}
