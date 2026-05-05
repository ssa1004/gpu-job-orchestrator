# 0001 — Spring MVC over WebFlux

- **Status:** Accepted
- **Date:** 2026-04-22

## Context

오케스트레이터 API 의 트래픽 패턴을 분석:
- 요청 자체는 짧음 (DB 한두 번 + K8s API 한 번 호출)
- 백엔드 호출 중 가장 느린 것은 K8s API (P95 ~50ms) 와 DB 쓰기 (P95 ~10ms)
- 동시 요청 수: 운영 초기 < 100 RPS, 장기적으로도 1000 RPS 미만 예상
- 응답을 기다리지 않는 streaming/SSE/WebSocket 사용처 없음 (콜백은 별도 endpoint)
- 팀의 Spring 경험은 MVC 위주

## Decision

Spring MVC (Servlet stack) 를 사용한다. WebFlux 는 도입하지 않는다.

## Consequences

### 긍정
- 디버깅 단순함 (Tomcat 스레드 모델, stack trace 가 깔끔)
- JPA + JDBC 가 그대로 사용 가능 (R2DBC 학습 비용 없음)
- 트랜잭션 전파 (`@Transactional`) 가 자연스럽게 작동
- 테스트 도구가 풍부 (`MockMvc`, `@WebMvcTest`)

### 부정
- 스레드 풀 한계까지 갔을 때 backpressure 가 약함 → Tomcat max-threads 와 DB 풀 사이즈를 신경써서 튜닝
- 향후 SSE/WebSocket 도입 시 별도 엔드포인트가 필요할 수 있음

### 대안
- **WebFlux**: 단일 인스턴스 동시성 ↑, 그러나 K8s 로 수평 확장이 가능하므로 인스턴스 한계가 병목이 아님. 학습 비용이 Trade-off 의 가치를 능가하지 않음.
