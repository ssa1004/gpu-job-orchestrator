# ADR-0018: OpenTelemetry W3C Trace Context — Kafka Header 전파

## 상태
적용

## 배경

`JobEvent` 페이로드 안에 `traceId` 필드가 있긴 했지만, 이는 *값으로서의 ID 한 개* 일 뿐
실제 distributed trace 가 service 경계 (orchestrator → worker → callback) 를 가로질러
한 흐름으로 보이게 하지 못한다. 한계:

- *spanId 가 없다* — child span 이 parent 를 가리킬 수 없어 trace 가 분리된 두 trace 로 보임
- *sampling 결정이 안 박힘* — orchestrator 는 trace 를 sampled 로 시작했는데 worker 가
  자기만의 sampling 으로 drop → 절반만 보이는 trace
- *문자열 컨벤션이 ad-hoc* — 우리 시스템 안에서만 통하는 포맷, OTel / Datadog / Naver
  Pinpoint 같은 표준 도구가 인식 못 함
- *outbox 시간차* — outbox row 가 INSERT 된 시점 (T0) 의 trace context 가 polling
  스레드 (T1) 에서 살아있지 않다 → consumer 가 새 trace 를 시작 → trace 끊김

표준 해법: **W3C Trace Context (RFC 9.5)** 를 message broker 헤더 / HTTP 헤더에 그대로
실어 보내서 receiver 가 그걸로 child span 을 시작. OTel / Spring Cloud Stream / Datadog
APM / Naver Pinpoint 모두 동일 포맷.

## 결정

### W3C `traceparent` 헤더 (RFC 9.5.1)

55자 고정 포맷:

```
00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
│  └─── traceId (32 hex) ──────────┘ └─── spanId ───┘ │
│                                                     └─ flags (sampled)
└─ version (현재 00)
```

- `traceId` (16 byte) — 한 trace 의 globally unique ID. 모든 span 이 공유.
- `spanId` (8 byte) — 한 span 의 ID. consumer 는 이 값을 parentSpanId 로 받아 child span 시작.
- `flags` — `01` = sampled (export 함), `00` = unsampled (drop 가능).
- `version` — 현재 표준 단일 값 (`00`).

### 두 path — HTTP / Kafka

**HTTP (worker → orchestrator callback)**: Spring Boot 3 + Micrometer Tracing 이 자동.
{@code management.tracing.propagation.type=w3c} 설정으로 `traceparent` 헤더가
inbound 자동 추출 / outbound 자동 주입. 코드 변경 0.

**Kafka (orchestrator → outbox → broker → worker)**: 직접 구현 — outbox 시간차 때문에.

### Outbox 시간차 해결 — row 에 박제

```
T0: 도메인 트랜잭션              T1: OutboxRelay polling
┌──────────────────────┐         ┌──────────────────────┐
│ JobSubmissionService │         │ OutboxRelay          │
│  ↓                   │         │  ↓                   │
│ outbox.save(msg)     │  ----   │ select unpublished   │
│  with traceparent ━━━╪━━━━━━━━▶│ 같은 traceparent      │
└──────────────────────┘         │ 헤더에 박아 send       │
   T0 의 trace 가                  └──────────────────────┘
   row 에 박제됨
                                  consumer 가 헤더로 child span 시작
```

핵심: `OutboxWriter.write()` 가 row 영속 *시점* 의 활성 span 을 W3C 포맷 문자열로 만들어
`traceparent` 컬럼에 INSERT. polling 시점에는 그 문자열을 그대로 Kafka 헤더로 복원 —
polling 스레드의 *현재 trace* 와 무관하게 *원래 잡 제출 trace* 를 이어받는다.

```java
// OutboxWriter — T0 시점에 trace 박제
private String currentTraceparent() {
    Span span = tracer.currentSpan();
    if (span == null || span.isNoop()) return null;
    var ctx = span.context();
    String flags = Boolean.TRUE.equals(ctx.sampled()) ? "01" : "00";
    return "00-" + ctx.traceId() + "-" + ctx.spanId() + "-" + flags;
}

// OutboxRelay — T1 시점에 헤더로 복원
ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
if (msg.getTraceparent() != null) {
    record.headers().add(new RecordHeader("traceparent",
            msg.getTraceparent().getBytes(UTF_8)));
}
```

### V8 마이그레이션

```sql
ALTER TABLE outbox ADD COLUMN traceparent VARCHAR(64);
```

NULL 허용 — 이전 row / 비-tracing 환경 (테스트 / 로컬 dev) 의 backward compatibility.
NULL 이면 OutboxRelay 가 헤더 주입을 건너뜀.

### 왜 Propagator API 가 아닌 직접 포맷팅인가

Micrometer Tracing 의 `Propagator.inject(carrier, setter)` 는 *carrier 에 값 주입* 이 목적이다.
outbox 박제는 *trace context 를 단일 문자열로 빼서 DB 컬럼에 넣는* 게 목적이라 carrier 추상화가 어색.

W3C 포맷이 RFC 표준이라 (55자 고정) 직접 포맷팅이 더 명시적. 추출 (consumer side) 은 Spring
Boot 의 Kafka instrumentation / OTel SDK 가 자동 처리하므로 직접 파싱 코드는 불필요.

### 컨슈머 (worker) 측 동작

worker / callback consumer 는 별도 코드 변경 없이 OTel auto-instrumentation 이 처리:

- **Java agent** 또는 spring-cloud-stream 의 KafkaTemplate 가 inbound 헤더에서
  `traceparent` 자동 추출 → `Span.parent()` 로 설정 → 새 span 이 자동으로 child 가 됨
- **callback HTTP 호출** 도 자동 — outbound RestTemplate / WebClient 가 `traceparent` 자동 주입

즉, worker 코드 변경 0. 환경 변수 / 의존성만 갖추면 자동 작동.

### 기존 `JobEvent.traceId` 는 어떻게

`JobSubmitted.traceId` 필드는 그대로 둠. 두 가지 이유:

1. **payload 안의 traceId 는 *비-tracing* 컨텍스트 (e.g. SQL 로그 join) 에서도 활용**.
   사용자 잡 ID 로 trace 검색이 필요한데 OTel backend 미연동 환경이라면 payload 의
   string 필드가 grep 으로 빠름.
2. **호환성** — 이미 Kafka 컨슈머가 payload field 를 쓰고 있을 수 있어 제거하면 break.

향후 정리: ADR-0021 (예정) 에서 payload 의 traceId 필드 deprecate.

## 대안

### 페이로드 안에 traceparent 만 박기

탈락 — 이유:

- consumer 의 OTel auto-instrumentation 은 *헤더* 를 본다. payload 안의 필드는 자동 처리 안 됨
- kafka 헤더는 Kafka 자체 메커니즘 — header-based filtering / routing 이 가능
- 표준 컨벤션 (Confluent, Spring Cloud Stream, OTel) 이 모두 헤더 기반

### 페이로드 + 헤더 둘 다

탈락 — 이유:

- 두 곳에 같은 정보 → 진실 출처 (source of truth) 모호
- payload 직렬화 비용 증가 (매번 JSON serialize)
- 헤더만 두면 충분

### Spring Cloud Stream

탈락 — 이유:

- 이 시스템은 plain spring-kafka 기반 (Spring Cloud Stream 미사용)
- SCS 도입은 큰 변경 + dependency 폭증
- KafkaTemplate 의 ProducerRecord 헤더 직접 조작이 충분 (이미 spring-kafka 가 정식 지원)

### B3 헤더 (Zipkin)

탈락 — 이유:

- B3 는 헤더 4개 (`X-B3-TraceId`, `X-B3-SpanId`, `X-B3-ParentSpanId`, `X-B3-Sampled`) — 헤더 양 ↑
- W3C 가 single-header (`traceparent`) 라 효율 ↑ + 표준 통일성 ↑
- 신규 시스템에서 B3 도입 이유 없음 (legacy compat 시에만)

후속 시스템이 B3 만 받으면 양쪽 켜는 옵션도 있음 (Micrometer 의
`management.tracing.propagation.type=w3c,b3`) — 지금은 단일 W3C.

## 결과

- orchestrator → outbox → Kafka → worker → callback HTTP 까지 하나의 trace ID 로 추적
- Tempo / Jaeger / Datadog 의 trace view 에서 잡 제출 → dispatch → worker 실행 → 종료
  콜백을 한 흐름으로 시각화
- p95 latency 의 어느 stage 가 느린지 (DB INSERT 인지, Kafka 발행 시간차인지, K8s
  dispatch 인지) 한눈에 분리 가능
- 외부 의존성 0 — 이미 있는 Spring Kafka / Micrometer 만 사용
- backward compatible — traceparent NULL 허용, 기존 payload.traceId 도 유지
- (단점) outbox 컬럼 1개 추가 (V8) — 운영 DB schema migration 필요
- (단점) outbox row 에 박힌 traceparent 의 sampled flag 가 늦게 publish 될 때까지
  유지됨 — sampling 정책이 *발행 시점* 기준이 아닌 *작성 시점* 기준이 됨. 이게 일반적으로
  맞음 (작성 trace 와 발행 trace 가 같은 흐름이어야 하므로).
- (단점) 페이로드 내 `traceId` 필드 중복 — 후속 ADR 에서 정리

## 검증

- 단위 테스트: `OutboxWriterTest.write_capturesActiveTraceContextAsW3cTraceparent`,
  `OutboxRelayTest.publishPending_propagatesTraceparentHeaderFromRow`
- 통합 검증 (수동): Tempo 에 trace 가 단일 흐름으로 그려지는지 확인 (잡 제출 → callback)

## 후속 ADR

- ADR-0019: Prometheus Exemplars — metric 에서 trace 로 한 번 클릭 jump
- ADR-0020 (예정): tracestate (RFC 9.5.2) 박제 — vendor-specific sampling 정보 보존
- ADR-0021 (예정): `JobEvent.traceId` payload 필드 deprecate
