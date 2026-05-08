package com.example.gwp.orchestrator.contract;

import com.example.gwp.orchestrator.outbox.JobEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 발행 이벤트들의 schema catalog — AsyncAPI spec 생성과 consumer-driven contract test 의
 * 단일 진실 (single source of truth).
 *
 * <h3>왜 이 카탈로그를 코드로 두는가</h3>
 * <ul>
 *   <li>{@link JobEvent} record 는 producer 측 *직렬화 형태* 의 source. consumer 가 보는
 *       *공개 contract* 는 따로 명시적으로 선언해야 — record 의 모든 component 가 contract 의
 *       일부일 필요는 없다 (예: 내부 디버깅 필드는 schema 에서 빼는 식).</li>
 *   <li>반대로 schema 는 있는데 record 에 component 가 없으면 직렬화에 누락 — 그래서
 *       {@link com.example.gwp.orchestrator.contract.EventCatalogConsistencyTest} 가 두 정의의
 *       정합성을 컴파일 후 매 빌드마다 검증한다.</li>
 * </ul>
 *
 * <h3>스키마 진화 규칙</h3>
 * <ul>
 *   <li><b>OK</b>: optional 필드 추가 (consumer 는 모르는 필드 무시).</li>
 *   <li><b>BREAKING</b>: 기존 필수 필드 제거 / 이름 변경 / 타입 변경.</li>
 *   <li><b>주의</b>: required → optional 로 완화는 forward-compatible 이지만 consumer 가
 *       이미 non-null 가정했다면 런타임 NPE. 신중히.</li>
 * </ul>
 *
 * <p>ADR-0020 참고.</p>
 */
public final class EventCatalog {

    private EventCatalog() {}

    /**
     * 이 producer 가 발행하는 모든 이벤트의 schema. 순서 보존 ({@link LinkedHashMap}).
     */
    public static List<EventSchema> all() {
        return List.of(jobSubmitted(), jobCompleted(), jobPreempted());
    }

    /** {@link JobEvent.JobSubmitted}. consumer: worker pool / dashboard / billing pre-check. */
    public static EventSchema jobSubmitted() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("jobId", strField("uuid"));
        props.put("owner", strField(null));
        props.put("image", strField(null));
        Map<String, Object> gpuCountSchema = new LinkedHashMap<>();
        gpuCountSchema.put("type", "integer");
        gpuCountSchema.put("minimum", 1);
        props.put("gpuCount", gpuCountSchema);
        props.put("priority", enumField("LOW", "NORMAL", "HIGH", "CRITICAL"));
        props.put("status", strField(null));
        props.put("traceId", strField(null));
        return new EventSchema(
                "JobSubmitted",
                "신규 잡이 제출되어 QUEUED 상태로 들어왔다 (의존성이 있으면 WAITING_DEPS).",
                props,
                List.of("jobId", "owner", "image", "gpuCount", "priority", "status")
        );
    }

    /** {@link JobEvent.JobCompleted}. consumer: result-listener / billing finalize / 알림. */
    public static EventSchema jobCompleted() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("jobId", strField("uuid"));
        props.put("status", enumField("SUCCEEDED", "FAILED", "CANCELLED"));
        props.put("resultUri", strField(null));
        props.put("errorMessage", strField(null));
        props.put("finishedAt", strField("date-time"));
        return new EventSchema(
                "JobCompleted",
                "잡이 종료되었다. SUCCEEDED 면 resultUri 가, FAILED 면 errorMessage 가 채워진다.",
                props,
                List.of("jobId", "status", "finishedAt")
        );
    }

    /** {@link JobEvent.JobPreempted}. consumer: 알림 / 빌링 (양보 보상) / 분석. */
    public static EventSchema jobPreempted() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("jobId", strField("uuid"));
        props.put("owner", strField(null));
        props.put("preemptorJobId", strField("uuid"));
        props.put("preemptorPriority", enumField("LOW", "NORMAL", "HIGH", "CRITICAL"));
        props.put("reason", strField(null));
        props.put("preemptedAt", strField("date-time"));
        return new EventSchema(
                "JobPreempted",
                "더 높은 우선순위 잡에게 GPU 를 양보당해 죽었다 (잡 자체에는 잘못 없음).",
                props,
                List.of("jobId", "owner", "preemptorJobId", "preemptorPriority", "preemptedAt")
        );
    }

    /** Helper — string 타입 + 선택적 format. LinkedHashMap 으로 순서 보존 (baseline 안정성). */
    private static Map<String, Object> strField(String format) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        if (format != null) m.put("format", format);
        return m;
    }

    /** Helper — enum 필드. */
    private static Map<String, Object> enumField(String... values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("enum", List.of(values));
        return m;
    }
}
