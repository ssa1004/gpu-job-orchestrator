# 0001 — Spring MVC over WebFlux

- **Status:** Accepted
- **Date:** 2026-04-22

## Context

오케스트레이터 API 의 트래픽 패턴을 분석:
- 요청 자체는 짧음 (DB 한두 번 + K8s API 한 번 호출)
- 백엔드 호출 중 가장 느린 것은 K8s API (P95 ~50ms — 100건 중 95번째로 느린 호출 기준)
  와 DB 쓰기 (P95 ~10ms)
- 동시 요청 수: 운영 초기 < 100 RPS (초당 요청 수), 장기적으로도 1000 RPS 미만 예상
- 응답을 기다리지 않는 streaming / SSE (Server-Sent Events) / WebSocket 사용처 없음
  (콜백은 별도 endpoint)
- 팀의 Spring 경험은 MVC (전통적인 요청-스레드 모델) 위주

## Decision

Spring MVC (Servlet stack — 요청 한 건당 스레드 한 개를 점유하는 전통적 모델) 를 사용한다.
WebFlux (리액티브 비동기 모델) 는 도입하지 않는다.

## Consequences

### 긍정
- 디버깅 단순함 (Tomcat 스레드 모델, stack trace 가 깔끔 — 한 요청이 한 스레드에서
  처리되니 호출 스택만 따라가면 됨)
- JPA + JDBC 가 그대로 사용 가능 (R2DBC — 리액티브용 DB 클라이언트, 학습 비용 없음)
- 트랜잭션 전파 (`@Transactional` 이 같은 스레드 안에서 자동으로 트랜잭션을 잇는 기능) 가
  자연스럽게 작동
- 테스트 도구가 풍부 (`MockMvc`, `@WebMvcTest` — Spring 이 제공하는 컨트롤러 단위 테스트 도구)

### 부정
- 스레드 풀 한계까지 갔을 때 backpressure (요청이 너무 많이 몰리면 처리량을 자동으로
  낮춰 시스템을 보호하는 역압) 가 약함 → Tomcat max-threads 와 DB 풀 사이즈를 신경써서 튜닝
- 향후 SSE / WebSocket 도입 시 별도 엔드포인트가 필요할 수 있음

### 대안
- **WebFlux**: 단일 인스턴스 동시성은 높지만, K8s 로 수평 확장 (Pod 수를 늘림) 이 가능하므로
  인스턴스 한계가 주된 병목이 아님. 이 프로젝트에서는 학습 비용 대비 얻는 이점이 작음.
