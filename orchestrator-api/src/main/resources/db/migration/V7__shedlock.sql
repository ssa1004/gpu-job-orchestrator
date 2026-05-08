-- V7: ShedLock 테이블 — 다중 인스턴스에서 같은 @Scheduled 메서드를 동시에 두 인스턴스가
-- 돌리는 것을 방지. lock 이름 기준으로 한 번에 한 인스턴스만 메서드 진입.
--
-- 동작:
--   1. 인스턴스 A 가 메서드 진입 시 INSERT (name=...) 시도. 성공 → 락 획득.
--   2. 동시각에 인스턴스 B 가 같은 INSERT 시도 → PRIMARY KEY 충돌로 실패 → skip.
--   3. A 가 메서드 종료 시 lock_until 을 현재 시각으로 UPDATE → 다음 tick 에서 재획득 가능.
--   4. A 가 죽어 락이 안 풀리면 다른 인스턴스가 lock_until < now 보고 takeover.
--
-- 예 row:
--   name='outbox-relay'        lock_until=2026-05-08 10:00:30 locked_at=10:00:00 locked_by='pod-abc'
--   name='preemption-scheduler' lock_until=2026-05-08 10:01:00 locked_at=10:00:00 locked_by='pod-abc'
--
-- 컬럼 정의는 ShedLock 라이브러리 (net.javacrumbs.shedlock) 의 표준 스키마를 그대로 따른다.

CREATE TABLE shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
