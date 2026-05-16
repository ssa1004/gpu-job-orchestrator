package com.example.gwp.orchestrator.contract

import java.util.Collections
import java.util.LinkedHashMap
import java.util.Objects

/**
 * 한 이벤트 record 의 스키마 (필드 → 타입) 를 reflection 없이 직접 선언.
 *
 * 왜 reflection 으로 record components 를 자동 추출하지 않나:
 * - JSR-269 record class 의 component 순서는 컴파일러가 보장하지만, 그 *의미* (어떤
 *   필드가 required 인지, AsyncAPI 의 어떤 type 으로 매핑되는지) 는 코드 외부에 있다.
 * - 이 클래스는 *contract 의 single source of truth* 다 — 코드가 바뀌어도 contract 는
 *   의식적으로 갱신해야 한다. reflection 자동화는 *backward-incompatible* 변경
 *   (필드 이름 변경 등) 을 컴파일러가 잡아주지 않는 위험을 만든다.
 * - 그래서 [EventCatalog] 가 record 와 schema 를 *나란히* 정의하고, 단위 테스트가
 *   두 정의가 일치하는지 검증한다.
 *
 * `@JvmRecord` 의 compact constructor 가 `properties = Collections.unmodifiableMap(...)` 같은
 * 재대입을 지원하지 않아, 일반 class + custom equals/hashCode + `@get:JvmName` accessor 로
 * Java record-style 호환을 유지한다 (Java: `schema.eventType()` / `schema.properties()` 그대로).
 *
 * @param eventType  이벤트 식별자 (JobSubmitted / JobCompleted / JobPreempted)
 * @param description 사람이 읽을 한 줄 설명 (AsyncAPI summary 로 출력)
 * @param properties 필드 → JSON Schema fragment.
 *                   예: `Map.of("jobId", Map.of("type", "string", "format", "uuid"))`
 *                   [LinkedHashMap] 으로 받아 선언 순서를 YAML 출력에 보존한다.
 * @param required 필수 필드 이름 목록. spec evolution 규칙: 필드 추가는 OK, 기존 필수 필드의
 *                 제거 / 이름 변경은 BREAKING.
 */
class EventSchema(
    eventType: String,
    description: String,
    properties: Map<String, Map<String, Any>>,
    required: List<String>,
) {

    @get:JvmName("eventType")
    val eventType: String

    @get:JvmName("description")
    val description: String

    @get:JvmName("properties")
    val properties: Map<String, Map<String, Any>>

    @get:JvmName("required")
    val required: List<String>

    init {
        require(eventType.isNotBlank()) { "eventType blank" }
        this.eventType = eventType
        this.description = description
        // 순서 보존 immutable copy — Map.copyOf 는 hash 순서가 될 수 있어 baseline drift 사고.
        // LinkedHashMap 의 unmodifiable wrapper 로 *입력 순서 = 출력 순서* 보장.
        this.properties = Collections.unmodifiableMap(LinkedHashMap(properties))
        this.required = java.util.List.copyOf(required)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EventSchema) return false
        return eventType == other.eventType &&
            description == other.description &&
            properties == other.properties &&
            required == other.required
    }

    override fun hashCode(): Int = Objects.hash(eventType, description, properties, required)

    override fun toString(): String =
        "EventSchema[eventType=$eventType, description=$description, properties=$properties, required=$required]"
}
