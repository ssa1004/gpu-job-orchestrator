package com.example.gwp.orchestrator.contract;

import com.example.gwp.orchestrator.outbox.JobEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EventCatalog} 와 {@link JobEvent} sealed interface 의 record class 사이의 정합성 검증.
 *
 * <p>이 테스트가 실패하면:
 * <ul>
 *   <li>JobEvent record 에 새 component 를 추가했는데 catalog 갱신을 잊었다 → consumer 가
 *       볼 spec 에 빠진다 (under-specified).</li>
 *   <li>catalog 에 어떤 필드를 선언했는데 record 에 같은 이름의 component 가 없다 →
 *       직렬화 시 그 필드가 발행되지 않는다 (over-promised contract).</li>
 * </ul>
 *
 * <p>두 경우 모두 production 출시 전에 컴파일 후 테스트로 막는 것이 목적.</p>
 */
class EventCatalogConsistencyTest {

    @Test
    void catalog_eventTypes_match_JobEvent_subclasses() {
        Set<String> catalogTypes = EventCatalog.all().stream()
                .map(EventSchema::eventType)
                .collect(Collectors.toSet());
        Set<String> recordTypes = Arrays.stream(JobEvent.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(catalogTypes).isEqualTo(recordTypes);
    }

    @Test
    void each_catalog_property_exists_in_corresponding_record() {
        for (EventSchema schema : EventCatalog.all()) {
            Class<?> recordClass = findRecord(schema.eventType());
            Set<String> recordComponents = Arrays.stream(recordClass.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toSet());
            for (String declared : schema.properties().keySet()) {
                assertThat(recordComponents)
                        .as("catalog 가 광고하는 %s.%s 가 record 에 component 로 존재해야 한다",
                                schema.eventType(), declared)
                        .contains(declared);
            }
        }
    }

    @Test
    void each_record_component_exists_in_catalog_properties() {
        for (EventSchema schema : EventCatalog.all()) {
            Class<?> recordClass = findRecord(schema.eventType());
            for (RecordComponent component : recordClass.getRecordComponents()) {
                assertThat(schema.properties())
                        .as("record %s 의 component '%s' 는 catalog 에도 선언되어야 한다 (직렬화 시 발행됨)",
                                schema.eventType(), component.getName())
                        .containsKey(component.getName());
            }
        }
    }

    @Test
    void required_fields_are_subset_of_properties() {
        for (EventSchema schema : EventCatalog.all()) {
            assertThat(schema.properties().keySet())
                    .as("required 는 properties 의 부분집합이어야 한다 (%s)", schema.eventType())
                    .containsAll(schema.required());
        }
    }

    private static Class<?> findRecord(String simpleName) {
        return Arrays.stream(JobEvent.class.getPermittedSubclasses())
                .filter(c -> c.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "JobEvent permitted subclass 에 " + simpleName + " 이 없다"));
        }
}
