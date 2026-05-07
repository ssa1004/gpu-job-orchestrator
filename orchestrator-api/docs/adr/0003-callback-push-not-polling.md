# 0003 — 워커 → 오케스트레이터 콜백 push (vs DB polling)

- **Status:** Accepted
- **Date:** 2026-04-22

## Context

워커 (GPU Job) 가 작업 진행 상황을 오케스트레이터에 알리는 두 가지 방식:

1. **Polling**: 워커가 주기적으로 DB 또는 API 를 호출해서 자기 상태를 갱신
2. **Push (콜백)**: 워커가 상태 천이 시점에 오케스트레이터에 HTTP POST

GPU Job 은 평균 10~30분 실행되며, 상태 변경은 시작 (RUNNING), 종료
(SUCCEEDED / FAILED) 의 2~3 회뿐. 워커는 GPU 에 묶여 있어 polling 호출 자체도 부하.

## Decision

워커는 상태 변경 시점마다 `POST /internal/jobs/{id}/status` 콜백을 보낸다.

인증은 헤더 공유 시크릿 (`X-GWP-Callback-Secret` — 워커와 API 가 같은 비밀 문자열을
가지고 헤더로 검증). v1 에서는 이 방식, mTLS (양쪽이 서로 인증서를 검증하는 TLS) 로의
전환은 향후 과제.

## Consequences

### 긍정
- 상태 갱신 지연 ↓ (push 즉시 반영, polling 주기 만큼 지연 없음)
- 폴링 주기 결정의 장단점 (자주 부르면 CPU / 네트워크 부하, 드물게 부르면 지연 큼) 회피
- 워커가 GPU 작업에 전념, 통신은 상태 변경 시점에만
- 오케스트레이터 측 콜백 endpoint 가 자연스럽게 webhook (외부에서 호출하는 콜백 URL)
  인프라로 확장 가능 (외부 알림 등)

### 부정
- 콜백 유실 시 상태가 영원히 stale (업데이트 안 된 채로 남음) → 보완책: Job timeout
  watcher (Spring Scheduling 으로 주기 실행) 가 K8s API 에 직접 조회해서 동기화 (별도
  백로그)
- 워커 → 오케스트레이터 네트워크가 양방향 필요 (방화벽 / NetworkPolicy 설계)
- 인증 (현재 shared-secret) 이 mTLS 전환 전까지 약점

### 대안
- **DB polling**: 단순함, 통신 양방향성 불필요. 그러나 워커 수만큼 polling 부하, 지연 큼
- **Kafka topic + 워커가 produce**: 콜백 자체를 이벤트로 변환 (워커가 Kafka 토픽에
  메시지 발행). 추가 인프라 의존 (워커 Pod 에 Kafka producer 라이브러리 + topic 권한).
  우리 outbox 는 오케스트레이터 → 외부 방향이라 다른 흐름. 향후 도입 가능
