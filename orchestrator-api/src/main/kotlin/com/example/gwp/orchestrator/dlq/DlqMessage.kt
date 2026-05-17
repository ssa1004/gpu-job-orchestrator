package com.example.gwp.orchestrator.dlq

import java.time.Instant

/**
 * DLQ 콘솔이 보는 메시지 1건의 표현. 출처별 (outbox row / kafka DLQ topic / 콜백 retry
 * exhausted) 의 표현을 한 표준 모델로 정규화한다.
 *
 * - [id] — DLQ 안에서의 고유 id (replay / discard 시 path variable). source 가
 *   OUTBOX 면 outbox row 의 PK, CALLBACK 이면 retry exhausted 시점에 부여된 UUID
 *   (워커가 보내는 메시지에는 `callbackId` 헤더로 박혀 있다).
 * - [source] — 어느 saga 단계에서 떨어진 메시지인지.
 * - [topic] — Kafka topic 또는 도메인 endpoint 이름 (예: `gwp.job.completed`,
 *   `internal/jobs/{id}/status`). 필터 / stats 의 차원 키.
 * - [jobId] — 관련 잡의 UUID (있을 경우). cost / dependency 그래프와의 cross-ref 에 쓰임.
 * - [ownerId] — 잡의 owner. byOwner 통계 + 권한 검증 (admin 만 read).
 * - [gpuClass] — H100 / A100 / V100 등 GPU class. `JOB_DISPATCH` 실패 분포가
 *   class 별로 다를 때 preemption 정책 튜닝 신호 (선택 — null 가능).
 * - [errorType] — 사유 분류 키 (`TIMEOUT` / `K8S_5XX` / `KAFKA_DOWN` /
 *   `IDP_FORBIDDEN` 등). 같은 키 anomaly burst 감지에 사용.
 * - [errorMessage] — 마지막 실패 사유 원문 (2KB 절단). 진단용.
 * - [attempts] — 격리 전까지의 시도 횟수.
 * - [firstSeenAt] / [lastSeenAt] — 같은 메시지가 여러 번 retry 된 경우의
 *   시간 구간. firstSeen 으로 stale 여부 판단 (오래된 obsolete job 의 DLQ 는 discard).
 * - [payloadPreview] — 페이로드 헤드 (256자) 만 보여줘서 운영자가 디스카드 전에 sanity
 *   check 가능. 전체 payload 는 `GET /{messageId}` 단건 조회 시 별도 노출.
 *
 * 단일 모듈 100% Kotlin 컨벤션 — data class. JPA 엔티티가 아니라 *DTO* 라 별 어노테이션
 * 불필요.
 */
data class DlqMessage(
    val id: String,
    val source: DlqSource,
    val topic: String,
    val jobId: String?,
    val ownerId: String?,
    val gpuClass: String?,
    val errorType: String,
    val errorMessage: String?,
    val attempts: Int,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val payloadPreview: String?,
)
