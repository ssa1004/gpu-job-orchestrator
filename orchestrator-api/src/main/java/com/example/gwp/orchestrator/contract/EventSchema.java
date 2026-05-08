package com.example.gwp.orchestrator.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 한 이벤트 record 의 스키마 (필드 → 타입) 를 reflection 없이 직접 선언.
 *
 * <p>왜 reflection 으로 record components 를 자동 추출하지 않나:
 * <ul>
 *   <li>JSR-269 record class 의 component 순서는 컴파일러가 보장하지만, 그 *의미* (어떤
 *       필드가 required 인지, AsyncAPI 의 어떤 type 으로 매핑되는지) 는 코드 외부에 있다.</li>
 *   <li>이 클래스는 *contract 의 single source of truth* 다 — 코드가 바뀌어도 contract 는
 *       의식적으로 갱신해야 한다. reflection 자동화는 *backward-incompatible* 변경
 *       (필드 이름 변경 등) 을 컴파일러가 잡아주지 않는 위험을 만든다.</li>
 *   <li>그래서 {@link com.example.gwp.orchestrator.contract.EventCatalog} 가 record 와
 *       schema 를 *나란히* 정의하고, 단위 테스트가 두 정의가 일치하는지 검증한다.</li>
 * </ul>
 *
 * @param eventType  이벤트 식별자 (JobSubmitted / JobCompleted / JobPreempted)
 * @param description 사람이 읽을 한 줄 설명 (AsyncAPI summary 로 출력)
 * @param properties 필드 → JSON Schema fragment.
 *                   예: {@code Map.of("jobId", Map.of("type", "string", "format", "uuid"))}
 *                   {@link LinkedHashMap} 으로 받아 선언 순서를 YAML 출력에 보존한다.
 * @param required 필수 필드 이름 목록. spec evolution 규칙: 필드 추가는 OK, 기존 필수 필드의
 *                 제거 / 이름 변경은 BREAKING.
 */
public record EventSchema(
        String eventType,
        String description,
        Map<String, Map<String, Object>> properties,
        java.util.List<String> required
) {

    public EventSchema {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType blank");
        }
        // 순서 보존 immutable copy — Map.copyOf 는 hash 순서가 될 수 있어 baseline drift 사고.
        // LinkedHashMap 의 unmodifiable wrapper 로 *입력 순서 = 출력 순서* 보장.
        properties = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        required = java.util.List.copyOf(required);
    }
}
