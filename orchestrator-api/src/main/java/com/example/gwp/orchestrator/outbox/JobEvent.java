package com.example.gwp.orchestrator.outbox;

/**
 * Job 애그리거트가 발행하는 도메인 이벤트의 sealed marker.
 *
 * <p>typed event 의 장점:</p>
 * <ul>
 *   <li>외부 컨슈머와의 contract 가 코드로 명시됨</li>
 *   <li>스키마 진화 시 컴파일러가 미사용 필드 catch</li>
 *   <li>Map&lt;String, Object&gt; payload 보다 IDE/리뷰 친화적</li>
 * </ul>
 *
 * <p>각 record 는 {@link OutboxWriter#write(JobEvent)} 로 발행되며, JSON 직렬화 시 {@code _type}
 * 필드로 식별 가능하도록 {@link #type()} 을 노출한다.</p>
 */
public sealed interface JobEvent
        permits JobEvent.JobSubmitted, JobEvent.JobCompleted {

    String aggregateId();

    String type();

    record JobSubmitted(
            String jobId,
            String owner,
            String image,
            int gpuCount,
            String priority,
            String status,
            String traceId
    ) implements JobEvent {
        @Override public String aggregateId() { return jobId; }
        @Override public String type() { return "JobSubmitted"; }
    }

    record JobCompleted(
            String jobId,
            String status,           // SUCCEEDED | FAILED | CANCELLED
            String resultUri,        // SUCCEEDED 일 때만 not blank
            String errorMessage,     // FAILED 일 때만 not blank
            String finishedAt        // ISO-8601
    ) implements JobEvent {
        @Override public String aggregateId() { return jobId; }
        @Override public String type() { return "JobCompleted"; }
    }
}
