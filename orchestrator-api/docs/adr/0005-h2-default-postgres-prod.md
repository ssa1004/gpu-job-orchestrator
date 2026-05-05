# 0005 — H2 default + Postgres prod 프로필 분기

- **Status:** Accepted
- **Date:** 2026-04-22

## Context

로컬 개발 / 단위 테스트 / 운영 데이터베이스를 어떻게 일관시킬 것인가. 후보:

1. **운영도 H2** — 단순하지만 운영 적합 X (영속성, 트랜잭션 격리, 동시성)
2. **운영도 로컬도 Postgres** — 일관성 100%, 그러나 로컬 dev 진입 비용 ↑ (Docker 필수)
3. **로컬 H2 + 운영 Postgres** — 진입 비용 ↓, 그러나 SQL 방언 차이 위험
4. **Testcontainers로 로컬도 Postgres** — 일관성, 그러나 Docker daemon 필요

## Decision

- **default profile**: H2 인메모리 (`MODE=PostgreSQL` 호환 모드) + Flyway 마이그레이션
- **prod profile**: PostgreSQL + Flyway 동일 마이그레이션
- **Testcontainers IT**: `JobLifecycleIT` 가 실제 PostgreSQL 컨테이너로 e2e 검증
- 마이그레이션 SQL 은 **PostgreSQL 호환 부분집합** 으로만 작성 (예: H2 미지원인 partial index, JSONB 등 회피)

## Consequences

### 긍정
- 로컬 `./gradlew bootRun` 이 Docker 없이 즉시 동작 → 리뷰어 진입 비용 0
- WebMvc 슬라이스 테스트가 빠름 (DB 컨테이너 안 띄움)
- Flyway 마이그레이션은 dev/test/prod 동일 → 환경 간 schema drift 없음
- Testcontainers IT 가 SQL 방언 차이를 catch (H2 에서 통과해도 PG 에서 실패하면 IT 가 잡음)

### 부정
- H2 와 PG 의 행동 차이 (대표적으로 timestamp 정밀도, 함수 이름, partial index 미지원) 를 코드/마이그레이션에서 회피해야 함
- 일부 Postgres 전용 기능 (jsonb, advisory lock, GIN 인덱스 등) 은 운영 전용 마이그레이션 (V*\_pg) 으로 분리 필요

### 대안
- **로컬도 Postgres (Docker)**: 일관성 ↑↑, 그러나 macOS 에서 Docker for Mac 의 부팅 시간이 dev 경험에 영향
- **Testcontainers 가 default 테스트도 사용**: 모든 단위 테스트가 컨테이너 시작에 ~5s 추가 → CI 시간 비용
