package com.example.gwp.orchestrator.dlq

import java.time.Instant

/**
 * `GET /api/v1/admin/dlq/stats?from=&to=&bucket=PT1H` 의 응답.
 *
 * - [total] — 전체 DLQ 메시지 수 (구간 내).
 * - [bySource] — saga 단계별 분포. 어느 단계에서 가장 많이 떨어지는지 — DAG_EVAL 이
 *   급증하면 dependency 해소 코드 검토, JOB_DISPATCH 가 급증하면 K8s API / quota 검토.
 * - [byTopic] — Kafka topic 별 분포. 같은 source 라도 topic 별로 분리해서 본다.
 * - [byErrorType] — 사유 분류 키별 분포. 운영자 화면에서 anomaly burst 감지.
 * - [byOwner] — gpu 특유 차원. 같은 owner (research team / billing tenant) 의 job
 *   여러 개가 한꺼번에 stuck 되는 패턴 (예: 잘못된 입력 URI 로 모든 잡이 dispatch
 *   실패) 을 감지. notification ADR-0015 의 byTenant, billing ADR-0033 의 byCustomer,
 *   market ADR-0028 의 bySku 와 같은 위치.
 * - [byGpuClass] — gpu 특유 차원. H100 / A100 / V100 별 dispatch failure 분포 —
 *   특정 class 만 failure 가 몰리면 그 class node pool 의 spot 회수율 / driver
 *   호환성 문제 신호. preemption 정책 튜닝의 입력.
 * - [buckets] — 시간 구간별 카운트 (히트맵용). bucket 폭은 ISO-8601 duration
 *   (예: PT1H = 1시간, P1D = 1일). 콘솔 차트.
 *
 * 모든 분포는 LinkedHashMap 으로 *carrier-side ordering* (서비스가 정렬해서 반환) —
 * 콘솔이 그대로 보여줘도 의미 있게 정렬되어 있도록.
 */
data class DlqStats(
    val from: Instant,
    val to: Instant,
    val total: Long,
    val bySource: Map<DlqSource, Long>,
    val byTopic: Map<String, Long>,
    val byErrorType: Map<String, Long>,
    val byOwner: Map<String, Long>,
    val byGpuClass: Map<String, Long>,
    val buckets: List<DlqStatsBucket>,
) {
    data class DlqStatsBucket(val start: Instant, val count: Long)
}
