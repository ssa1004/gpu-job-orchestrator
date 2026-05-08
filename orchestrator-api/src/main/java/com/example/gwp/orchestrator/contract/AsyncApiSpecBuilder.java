package com.example.gwp.orchestrator.contract;

import com.example.gwp.orchestrator.config.properties.GwpProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EventCatalog} 와 outbox topic prefix 를 입력으로 받아 AsyncAPI 3.0 문서 (Map 으로
 * 표현) 를 만든다. 이 Map 은 외부에서 YAML 또는 JSON 으로 직렬화 가능.
 *
 * <h3>왜 AsyncAPI 인가</h3>
 * <ul>
 *   <li>OpenAPI 는 *동기 request/response* 모델. 이벤트 (publish/subscribe) 는 channel /
 *       message 라는 다른 vocabulary 가 필요하다.</li>
 *   <li>AsyncAPI 3.0 은 channel-centric — channel 이 topic 에 대응, message 가 payload schema
 *       에 대응. Kafka / SQS / NATS / WebSocket 모두 같은 spec 으로 표현 가능.</li>
 *   <li>도구 생태계: Studio (visual editor), generator (TS / Java client), spec linter.</li>
 * </ul>
 *
 * <h3>이 빌더의 책임</h3>
 * <ul>
 *   <li>info / servers / channels / operations / components 의 4 영역을 채운다.</li>
 *   <li>JSON Schema fragment (catalog 에서 받은 properties / required) 를
 *       {@code components.schemas} 에 풀어 넣는다.</li>
 *   <li>topic 이름은 OutboxRelay 와 같은 규칙: {@code <prefix>job.<eventtype-lowercase>}.</li>
 * </ul>
 *
 * <p>ADR-0020 참고.</p>
 */
public final class AsyncApiSpecBuilder {

    private static final String ASYNCAPI_VERSION = "3.0.0";

    private AsyncApiSpecBuilder() {}

    /**
     * AsyncAPI 3.0 문서를 nested Map 으로 빌드. 호출자가 Jackson YAMLFactory 또는 JSON
     * writer 로 직렬화한다.
     *
     * @param info     서비스 메타 (title / version / description). null 가능 — null 이면 default.
     * @param topicPrefix outbox 의 topic prefix (예: {@code "gwp."}). OutboxRelay 와 동일한
     *                    값을 사용해야 *발행되는 topic* 과 *문서가 광고하는 topic* 이 일치한다.
     * @param brokerUrl  Kafka broker URL (servers.production.host 로 광고). null 이면 servers 생략.
     * @param events     contract 카탈로그 (보통 {@link EventCatalog#all()}).
     */
    public static Map<String, Object> build(SpecInfo info,
                                            String topicPrefix,
                                            String brokerUrl,
                                            List<EventSchema> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("events empty");
        }
        if (topicPrefix == null) topicPrefix = "";
        SpecInfo effective = info != null ? info : SpecInfo.defaultInfo();

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("asyncapi", ASYNCAPI_VERSION);
        doc.put("info", buildInfo(effective));
        if (brokerUrl != null && !brokerUrl.isBlank()) {
            doc.put("servers", buildServers(brokerUrl));
        }
        doc.put("channels", buildChannels(topicPrefix, events));
        doc.put("operations", buildOperations(events));
        doc.put("components", buildComponents(events));
        return doc;
    }

    /** {@link GwpProperties} 에서 broker / topic prefix 를 뽑아내는 편의 빌더. */
    public static Map<String, Object> build(GwpProperties properties) {
        var relay = properties.outbox().relay();
        return build(SpecInfo.defaultInfo(), relay.topicPrefix(), null, EventCatalog.all());
    }

    private static Map<String, Object> buildInfo(SpecInfo info) {
        // LinkedHashMap — Map.of 는 순서 보장 안 해서 매 빌드마다 YAML diff 가 발생할 수 있음.
        // baseline test (drift 감지) 가 거짓 fail 을 내는 사고 방지.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", info.title());
        m.put("version", info.version());
        m.put("description", info.description());
        return m;
    }

    private static Map<String, Object> buildServers(String brokerUrl) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("host", brokerUrl);
        server.put("protocol", "kafka");
        server.put("description", "Kafka broker — outbox relay 가 발행");
        Map<String, Object> servers = new LinkedHashMap<>();
        servers.put("production", server);
        return servers;
    }

    /** channel 한 개 = topic 한 개. address 가 실제 Kafka topic. */
    private static Map<String, Object> buildChannels(String topicPrefix, List<EventSchema> events) {
        Map<String, Object> channels = new LinkedHashMap<>();
        for (EventSchema event : events) {
            String topic = topicPrefix + "job." + event.eventType().toLowerCase();
            String channelId = "job_" + event.eventType();
            Map<String, Object> messageRef = new LinkedHashMap<>();
            messageRef.put("$ref", "#/components/messages/" + event.eventType());
            Map<String, Object> messages = new LinkedHashMap<>();
            messages.put(event.eventType(), messageRef);
            Map<String, Object> channel = new LinkedHashMap<>();
            channel.put("address", topic);
            channel.put("title", event.eventType() + " channel");
            channel.put("description", event.description());
            channel.put("messages", messages);
            channels.put(channelId, channel);
        }
        return channels;
    }

    /** AsyncAPI 3.0 — operation 이 channel 과 분리. 우리는 모두 send (publish). */
    private static Map<String, Object> buildOperations(List<EventSchema> events) {
        Map<String, Object> ops = new LinkedHashMap<>();
        for (EventSchema event : events) {
            String channelId = "job_" + event.eventType();
            Map<String, Object> channelRef = new LinkedHashMap<>();
            channelRef.put("$ref", "#/channels/" + channelId);
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("action", "send");
            op.put("channel", channelRef);
            op.put("summary", "Producer (orchestrator-api) 가 이 이벤트를 발행");
            ops.put("send_" + event.eventType(), op);
        }
        return ops;
    }

    /** components.schemas + components.messages — JSON Schema 본체. */
    private static Map<String, Object> buildComponents(List<EventSchema> events) {
        Map<String, Object> schemas = new LinkedHashMap<>();
        Map<String, Object> messages = new LinkedHashMap<>();
        for (EventSchema event : events) {
            schemas.put(event.eventType(), schemaFor(event));
            Map<String, Object> payloadRef = new LinkedHashMap<>();
            payloadRef.put("$ref", "#/components/schemas/" + event.eventType());
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("name", event.eventType());
            message.put("title", event.eventType());
            message.put("summary", event.description());
            message.put("contentType", "application/json");
            message.put("payload", payloadRef);
            messages.put(event.eventType(), message);
        }
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("schemas", schemas);
        components.put("messages", messages);
        return components;
    }

    private static Map<String, Object> schemaFor(EventSchema event) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(event.properties()));
        schema.put("required", event.required());
        // additionalProperties=true — backward-compat 진화 (필드 추가) 를 consumer 가 reject
        // 하지 않도록 명시. 잘못된 필드 추가는 producer test 가 catalog 와의 정합성으로 잡는다.
        schema.put("additionalProperties", true);
        return schema;
    }

    /** 문서 메타. version 은 GpuJobOrchestrator 의 build version 과 맞추는 것을 권장. */
    public record SpecInfo(String title, String version, String description) {

        public static SpecInfo defaultInfo() {
            return new SpecInfo(
                    "GPU Job Orchestrator — Outbox events",
                    "0.1.0",
                    "outbox 패턴으로 발행되는 이벤트의 contract. consumer 는 이 spec 을 받아 "
                            + "src/test/resources/contracts/expectations.json 작성 → producer 가 expectations "
                            + "를 만족하는지 컴파일 후 검증."
            );
        }
    }
}
