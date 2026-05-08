-- V7: ShedLock 테이블 — 다중 인스턴스에서 같은 @Scheduled 메서드를 동시에 두 인스턴스
-- 가 돌리는 것을 방지. lock 이름 기준으로 한 번에 한 인스턴스만 메서드 진입.
-- 컬럼 정의는 ShedLock 라이브러리 (net.javacrumbs.shedlock) 의 표준 스키마를 따른다.

CREATE TABLE shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
