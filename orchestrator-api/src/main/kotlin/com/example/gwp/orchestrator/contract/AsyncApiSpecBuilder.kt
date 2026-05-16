package com.example.gwp.orchestrator.contract

import com.example.gwp.orchestrator.config.properties.GwpProperties
import java.util.LinkedHashMap

/**
 * [EventCatalog] 와 outbox topic prefix 를 입력으로 받아 AsyncAPI 3.0 문서 (Map 으로
 * 표현) 를 만든다. 이 Map 은 외부에서 YAML 또는 JSON 으로 직렬화 가능.
 *
 * ### 왜 AsyncAPI 인가
 * - OpenAPI 는 *동기 request/response* 모델. 이벤트 (publish/subscribe) 는 channel /
 *   message 라는 다른 vocabulary 가 필요하다.
 * - AsyncAPI 3.0 은 channel-centric — channel 이 topic 에 대응, message 가 payload schema
 *   에 대응. Kafka / SQS / NATS / WebSocket 모두 같은 spec 으로 표현 가능.
 * - 도구 생태계: Studio (visual editor), generator (TS / Java client), spec linter.
 *
 * ### 이 빌더의 책임
 * - info / servers / channels / operations / components 의 4 영역을 채운다.
 * - JSON Schema fragment (catalog 에서 받은 properties / required) 를
 *   `components.schemas` 에 풀어 넣는다.
 * - topic 이름은 OutboxRelay 와 같은 규칙: `<prefix>job.<eventtype-lowercase>`.
 *
 * ADR-0020 참고.
 *
 * Java 호출자 (`AsyncApiSpecBuilder.build(...)`) 그대로 동작 — Kotlin `object` 의
 * `@JvmStatic` 메서드 + nested `SpecInfo` 의 `@JvmRecord data class` 가 같은 시그니처 노출.
 */
object AsyncApiSpecBuilder {

    private const val ASYNCAPI_VERSION = "3.0.0"

    /**
     * AsyncAPI 3.0 문서를 nested Map 으로 빌드. 호출자가 Jackson YAMLFactory 또는 JSON
     * writer 로 직렬화한다.
     *
     * @param info     서비스 메타 (title / version / description). null 가능 — null 이면 default.
     * @param topicPrefix outbox 의 topic prefix (예: `"gwp."`). OutboxRelay 와 동일한
     *                    값을 사용해야 *발행되는 topic* 과 *문서가 광고하는 topic* 이 일치한다.
     * @param brokerUrl  Kafka broker URL (servers.production.host 로 광고). null 이면 servers 생략.
     * @param events     contract 카탈로그 (보통 [EventCatalog.all]).
     */
    @JvmStatic
    fun build(
        info: SpecInfo?,
        topicPrefix: String?,
        brokerUrl: String?,
        events: List<EventSchema>,
    ): Map<String, Any> {
        require(events.isNotEmpty()) { "events empty" }
        val effectivePrefix = topicPrefix ?: ""
        val effective = info ?: SpecInfo.defaultInfo()

        val doc = LinkedHashMap<String, Any>()
        doc["asyncapi"] = ASYNCAPI_VERSION
        doc["info"] = buildInfo(effective)
        if (brokerUrl != null && brokerUrl.isNotBlank()) {
            doc["servers"] = buildServers(brokerUrl)
        }
        doc["channels"] = buildChannels(effectivePrefix, events)
        doc["operations"] = buildOperations(events)
        doc["components"] = buildComponents(events)
        return doc
    }

    /** [GwpProperties] 에서 broker / topic prefix 를 뽑아내는 편의 빌더. */
    @JvmStatic
    fun build(properties: GwpProperties): Map<String, Any> {
        val relay = properties.outbox().relay()
        return build(SpecInfo.defaultInfo(), relay.topicPrefix(), null, EventCatalog.all())
    }

    private fun buildInfo(info: SpecInfo): Map<String, Any> {
        // LinkedHashMap — Map.of 는 순서 보장 안 해서 매 빌드마다 YAML diff 가 발생할 수 있음.
        // baseline test (drift 감지) 가 거짓 fail 을 내는 사고 방지.
        val m = LinkedHashMap<String, Any>()
        m["title"] = info.title
        m["version"] = info.version
        m["description"] = info.description
        return m
    }

    private fun buildServers(brokerUrl: String): Map<String, Any> {
        val server = LinkedHashMap<String, Any>()
        server["host"] = brokerUrl
        server["protocol"] = "kafka"
        server["description"] = "Kafka broker — outbox relay 가 발행"
        val servers = LinkedHashMap<String, Any>()
        servers["production"] = server
        return servers
    }

    /** channel 한 개 = topic 한 개. address 가 실제 Kafka topic. */
    private fun buildChannels(topicPrefix: String, events: List<EventSchema>): Map<String, Any> {
        val channels = LinkedHashMap<String, Any>()
        for (event in events) {
            val topic = topicPrefix + "job." + event.eventType.lowercase()
            val channelId = "job_" + event.eventType
            val messageRef = LinkedHashMap<String, Any>()
            messageRef["\$ref"] = "#/components/messages/" + event.eventType
            val messages = LinkedHashMap<String, Any>()
            messages[event.eventType] = messageRef
            val channel = LinkedHashMap<String, Any>()
            channel["address"] = topic
            channel["title"] = event.eventType + " channel"
            channel["description"] = event.description
            channel["messages"] = messages
            channels[channelId] = channel
        }
        return channels
    }

    /** AsyncAPI 3.0 — operation 이 channel 과 분리. 우리는 모두 send (publish). */
    private fun buildOperations(events: List<EventSchema>): Map<String, Any> {
        val ops = LinkedHashMap<String, Any>()
        for (event in events) {
            val channelId = "job_" + event.eventType
            val channelRef = LinkedHashMap<String, Any>()
            channelRef["\$ref"] = "#/channels/$channelId"
            val op = LinkedHashMap<String, Any>()
            op["action"] = "send"
            op["channel"] = channelRef
            op["summary"] = "Producer (orchestrator-api) 가 이 이벤트를 발행"
            ops["send_" + event.eventType] = op
        }
        return ops
    }

    /** components.schemas + components.messages — JSON Schema 본체. */
    private fun buildComponents(events: List<EventSchema>): Map<String, Any> {
        val schemas = LinkedHashMap<String, Any>()
        val messages = LinkedHashMap<String, Any>()
        for (event in events) {
            schemas[event.eventType] = schemaFor(event)
            val payloadRef = LinkedHashMap<String, Any>()
            payloadRef["\$ref"] = "#/components/schemas/" + event.eventType
            val message = LinkedHashMap<String, Any>()
            message["name"] = event.eventType
            message["title"] = event.eventType
            message["summary"] = event.description
            message["contentType"] = "application/json"
            message["payload"] = payloadRef
            messages[event.eventType] = message
        }
        val components = LinkedHashMap<String, Any>()
        components["schemas"] = schemas
        components["messages"] = messages
        return components
    }

    private fun schemaFor(event: EventSchema): Map<String, Any> {
        val schema = LinkedHashMap<String, Any>()
        schema["type"] = "object"
        schema["properties"] = LinkedHashMap(event.properties)
        schema["required"] = event.required
        // additionalProperties=true — backward-compat 진화 (필드 추가) 를 consumer 가 reject
        // 하지 않도록 명시. 잘못된 필드 추가는 producer test 가 catalog 와의 정합성으로 잡는다.
        schema["additionalProperties"] = true
        return schema
    }

    /** 문서 메타. version 은 GpuJobOrchestrator 의 build version 과 맞추는 것을 권장. */
    @JvmRecord
    data class SpecInfo(val title: String, val version: String, val description: String) {

        companion object {
            @JvmStatic
            fun defaultInfo(): SpecInfo = SpecInfo(
                "GPU Job Orchestrator — Outbox events",
                "0.1.0",
                "outbox 패턴으로 발행되는 이벤트의 contract. consumer 는 이 spec 을 받아 " +
                    "src/test/resources/contracts/expectations.json 작성 → producer 가 expectations " +
                    "를 만족하는지 컴파일 후 검증.",
            )
        }
    }
}
