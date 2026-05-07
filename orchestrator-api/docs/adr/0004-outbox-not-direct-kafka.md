# 0004 — Outbox 패턴 (vs Kafka 직접 produce)

- **Status:** Accepted
- **Date:** 2026-04-22

## Context

Job 상태 변경 (제출, 종료) 을 다운스트림 시스템 (회계, 알림, 분석 등 우리 다음에서
받아 쓰는 시스템) 에 알리려면 Kafka 이벤트를 발행해야 한다. 두 가지 방식:

1. **직접 produce**: `JobService.submit()` 에서 DB commit 후 `kafkaTemplate.send()` 호출
2. **Outbox 패턴**: 도메인 트랜잭션 안에서 `outbox` 테이블 (DB 안의 발신함) 에 INSERT,
   별도 relay 가 polling 으로 Kafka 발행

직접 produce 의 실패 시나리오:
- DB commit 성공 → Kafka send 실패: 이벤트 영구 유실. retry 로 복구되지 않음
- Kafka send 성공 → DB rollback (예: post-commit hook 에서): phantom event (실제로는
  취소된 Job 인데 발행된 이벤트), 다운스트림이 존재하지 않는 Job 을 처리 시도

## Decision

Outbox 패턴을 도입한다. 도메인 변경과 outbox INSERT 가 같은 트랜잭션 안에서 원자성
(둘 다 commit 되거나 둘 다 rollback) 보장. `OutboxRelay` 가 1초 polling 으로 미발행
메시지를 Kafka 로 흘림.

스키마: `outbox(id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at)`

## Consequences

### 긍정
- 트랜잭션 일관성 보장 (DB commit ↔ 이벤트 발행 의도가 동일)
- Kafka 일시 장애에도 메시지 유실 없음 (relay 가 retry)
- 운영자가 outbox 테이블을 직접 보고 발행 상태 확인 가능
- 향후 Debezium CDC (DB 변경 사항을 실시간 스트리밍하는 CDC 도구) 로 전환 시 outbox
  테이블이 그대로 source 로 동작 (구현 변경 거의 없음)

### 부정
- DB 부하 약간 증가 (이벤트당 INSERT 1, UPDATE 1)
- relay polling 으로 발행 지연 평균 0.5초 (수용 가능)
- at-least-once 발행 (최소 한 번은 발행되지만 같은 메시지가 두 번 갈 수도 있음) →
  컨슈머가 멱등성 (같은 메시지를 두 번 받아도 결과가 같게 처리하는 성질) 가져야 함
  (`eventId` 기반 dedup — 이벤트 ID 로 중복 제거)

### 대안
- **transactional outbox + Kafka transactional producer**: 더 강한 보장, 그러나 Kafka
  트랜잭션 운영 복잡도 ↑. 우리 규모에 과함
- **Debezium CDC**: outbox 없이 도메인 테이블의 변경을 직접 캡처 (Change Data Capture
  — DB 의 binlog/WAL 을 읽어 변경을 스트리밍). 운영 복잡도 (Kafka Connect 클러스터,
  schema-registry — 메시지 스키마 관리 서비스) ↑. 향후 전환 옵션
