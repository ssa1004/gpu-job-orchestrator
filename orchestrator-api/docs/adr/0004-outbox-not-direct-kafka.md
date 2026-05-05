# 0004 — Outbox 패턴 (vs Kafka 직접 produce)

- **Status:** Accepted
- **Date:** 2026-04-22

## Context

Job 상태 변경 (제출, 종료) 을 다운스트림 시스템 (회계, 알림, 분석) 에 알리려면 Kafka 이벤트를 발행해야 한다. 두 가지 방식:

1. **직접 produce**: `JobService.submit()` 에서 DB commit 후 `kafkaTemplate.send()` 호출
2. **Outbox 패턴**: 도메인 트랜잭션 안에서 `outbox` 테이블에 INSERT, 별도 relay 가 polling 으로 Kafka 발행

직접 produce 의 실패 시나리오:
- DB commit 성공 → Kafka send 실패: 이벤트 영구 유실. retry 로 복구되지 않음
- Kafka send 성공 → DB rollback (예: post-commit hook 에서): phantom event, 다운스트림이 존재하지 않는 Job 을 처리 시도

## Decision

Outbox 패턴을 도입한다. 도메인 변경과 outbox INSERT 가 같은 트랜잭션 안에서 atomicity 보장. `OutboxRelay` 가 1초 polling 으로 미발행 메시지를 Kafka 로 흘림.

스키마: `outbox(id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at)`

## Consequences

### 긍정
- 트랜잭션 일관성 보장 (DB commit ↔ 이벤트 발행 의도가 동일)
- Kafka 일시 장애에도 메시지 유실 없음 (relay 가 retry)
- 운영자가 outbox 테이블을 직접 보고 발행 상태 확인 가능
- 향후 Debezium CDC 로 전환 시 outbox 테이블이 그대로 source 로 동작 (구현 변경 거의 없음)

### 부정
- DB 부하 약간 증가 (이벤트당 INSERT 1, UPDATE 1)
- relay polling 으로 발행 지연 평균 0.5초 (수용 가능)
- at-least-once 발행 → 컨슈머가 멱등성 가져야 함 (`eventId` 기반 dedup)

### 대안
- **transactional outbox + Kafka transactional producer**: 더 강한 보장, 그러나 Kafka 트랜잭션 운영 복잡도 ↑. 우리 규모에 과함
- **Debezium CDC**: outbox 없이 도메인 테이블의 변경을 직접 캡처. 운영 복잡도 (Connect 클러스터, schema-registry) ↑. 향후 전환 옵션
