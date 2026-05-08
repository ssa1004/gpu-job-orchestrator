package com.example.gwp.orchestrator.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AsyncAPI 3.0 문서 빌더의 출력 구조 검증. 외부 도구 (Studio / generator) 가 spec 을 받아
 * 깨지지 않도록 *반드시 채워야 하는 키* 들을 회귀 방지로 못박는다.
 */
class AsyncApiSpecBuilderTest {

    @Test
    void build_producesValidAsyncApi3StructureWithChannels() {
        Map<String, Object> spec = AsyncApiSpecBuilder.build(
                AsyncApiSpecBuilder.SpecInfo.defaultInfo(),
                "gwp.",
                "kafka.production:9092",
                EventCatalog.all());

        assertThat(spec.get("asyncapi")).isEqualTo("3.0.0");
        assertThat(spec).containsKey("info");
        assertThat(spec).containsKey("servers");
        assertThat(spec).containsKey("channels");
        assertThat(spec).containsKey("operations");
        assertThat(spec).containsKey("components");
    }

    @Test
    void channels_useTopicPrefix_andEventTypeLowercase() {
        Map<String, Object> spec = AsyncApiSpecBuilder.build(
                null, "gwp.", null, EventCatalog.all());

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> channels =
                (Map<String, Map<String, Object>>) spec.get("channels");

        // OutboxRelay 의 topic 컨벤션과 정확히 같은 규칙: prefix + "job." + eventType.toLowerCase()
        assertThat(channels.get("job_JobSubmitted").get("address")).isEqualTo("gwp.job.jobsubmitted");
        assertThat(channels.get("job_JobCompleted").get("address")).isEqualTo("gwp.job.jobcompleted");
        assertThat(channels.get("job_JobPreempted").get("address")).isEqualTo("gwp.job.jobpreempted");
    }

    @Test
    void components_schemas_includeAllEventsWithRequiredFields() {
        Map<String, Object> spec = AsyncApiSpecBuilder.build(
                null, "gwp.", null, EventCatalog.all());

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> schemas =
                (Map<String, Map<String, Object>>) components.get("schemas");

        for (EventSchema event : EventCatalog.all()) {
            Map<String, Object> schema = schemas.get(event.eventType());
            assertThat(schema).as("schema for %s", event.eventType()).isNotNull();
            assertThat(schema.get("type")).isEqualTo("object");
            assertThat(schema.get("required")).isEqualTo(event.required());
            // additionalProperties=true — backward-compat 진화 보장.
            assertThat(schema.get("additionalProperties")).isEqualTo(true);
        }
    }

    @Test
    void operations_areAllSendActions_matchingChannels() {
        Map<String, Object> spec = AsyncApiSpecBuilder.build(
                null, "gwp.", null, EventCatalog.all());

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> ops =
                (Map<String, Map<String, Object>>) spec.get("operations");

        // producer 만 있음 — receive 는 없음 (consumer 가 별도 spec 가짐).
        assertThat(ops).hasSize(EventCatalog.all().size());
        ops.values().forEach(op -> assertThat(op.get("action")).isEqualTo("send"));
    }

    @Test
    void build_rejectsEmptyCatalog() {
        assertThatThrownBy(() -> AsyncApiSpecBuilder.build(null, "gwp.", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
