# ADR-0018: OpenTelemetry W3C Trace Context — Kafka Header 전파

## 상태
적용

## 배경

`JobEvent` 페이로드 안에 `traceId` 필드가 있긴 했지만, 이는 값으로서의 ID 한 개일 뿐
실제 distributed trace 가 service 경계 (orchestrator → worker → callback) 를 가로질러
한 흐름으로 보이게 하지 못한다. 한계는 다음과 같다.

- spanId 가 없다 — child span 이 parent 를 가리킬 수 없어 trace 가 분리된 두 trace 로 보임
- sampling 결정이 안 박힌다 — orchestrator 는 trace 를 sampled 로 시작했는데 worker 가
  자기만의 sampling 으로 drop → 절반만 보이는 trace
- 문자열 컨벤션이 ad-hoc — 우리 시스템 안에서만 통하는 포맷이라 표준 APM 도구가 인식 못 함
- outbox 시간차 — outbox row 가 INSERT 된 시점 (T0) 의 trace context 가 polling
  스레드 (T1) 에서 살아있지 않다. consumer 가 새 trace 를 시작 → trace 끊김

표준 해법은 W3C Trace Context (RFC 9.5) 를 message broker 헤더 / HTTP 헤더에 그대로
실어 보내서 receiver 가 그걸로 child span 을 시작하게 하는 방식이다. OpenTelemetry SDK,
Spring Cloud Stream 을 비롯한 표준 도구가 모두 같은 포맷을 따른다.

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

### Outbox 시간차 해결 — row 에 trace context 스냅샷

```
T0: 도메인 트랜잭션              T1: OutboxRelay polling
┌──────────────────────┐         ┌──────────────────────┐
│ JobSubmissionService │         │ OutboxRelay          │
│  ↓                   │         │  ↓                   │
│ outbox.save(msg)     │  ----   │ select unpublished   │
│  with traceparent ━━━╪━━━━━━━━▶│ 같은 traceparent     │
└──────────────────────┘         │ 헤더에 실어 send     │
   T0 의 trace 가                 └──────────────────────┘
   row 에 그대로 보관
                                  consumer 가 헤더로 child span 시작
```

핵심은 `OutboxWriter.write()` 가 row 영속 시점 (T0) 의 활성 span 을 W3C 포맷 문자열로
만들어 `traceparent` 컬럼에 INSERT 한다는 점이다. polling 시점 (T1) 에는 그 문자열을
그대로 Kafka 헤더로 복원한다. polling 스레드의 현재 trace 와 무관하게 원래 잡 제출
trace 를 그대로 이어받는다.

```java
// OutboxWriter — T0 시점 trace context 를 그대로 캡처
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

Micrometer Tracing 의 `Propagator.inject(carrier, setter)` 는 carrier 에 값을 주입하는
것이 목적이다. outbox 의 경우 trace context 를 단일 문자열로 빼서 DB 컬럼에 넣는 것이
목적이라 carrier 추상화가 어색하다.

W3C 포맷이 RFC 표준 (55자 고정) 이라 직접 포맷팅이 더 명시적이다. 추출 (consumer side)
은 Spring Boot 의 Kafka instrumentation / OTel SDK 가 자동 처리하므로 직접 파싱 코드는
불필요하다.

### 컨슈머 (worker) 측 동작

worker / callback consumer 는 별도 코드 변경 없이 OTel auto-instrumentation 이 처리:

- **Java agent** 또는 spring-cloud-stream 의 KafkaTemplate 가 inbound 헤더에서
  `traceparent` 자동 추출 → `Span.parent()` 로 설정 → 새 span 이 자동으로 child 가 됨
- **callback HTTP 호출** 도 자동 — outbound RestTemplate / WebClient 가 `traceparent` 자동 주입

즉, worker 코드 변경 0. 환경 변수 / 의존성만 갖추면 자동 작동.

### 기존 `JobEvent.traceId` 는 어떻게

`JobSubmitted.traceId` 필드는 그대로 둠. 두 가지 이유:

1. **payload 안의 traceId 는 비-tracing 컨텍스트 (예: SQL 로그 join) 에서도 활용**한다.
   사용자 잡 ID 로 trace 검색이 필요한데 OTel backend 미연동 환경이라면 payload 의
   string 필드가 grep 으로 빠르다.
2. **호환성** — 이미 Kafka 컨슈머가 payload field 를 쓰고 있을 수 있어 제거하면 break 가
   난다.

향후 정리: payload 의 traceId 필드 deprecate (별도 백로그). 헤더 전파가 충분히
검증되면 enum 자체를 제거.

## 대안

### 페이로드 안에 traceparent 만 박기

탈락 — 이유:

- consumer 의 OTel auto-instrumentation 은 헤더를 본다. payload 안의 필드는 자동 처리되지 않는다
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
- Tempo / Jaeger 의 trace view 에서 잡 제출 → dispatch → worker 실행 → 종료 콜백을 한
  흐름으로 시각화
- p95 latency 의 어느 stage 가 느린지 (DB INSERT 인지, Kafka 발행 시간차인지, K8s
  dispatch 인지) 한눈에 분리 가능
- 외부 의존성 0 — 이미 있는 Spring Kafka / Micrometer 만 사용
- backward compatible — traceparent NULL 허용, 기존 payload.traceId 도 유지
- (단점) outbox 컬럼 1개 추가 (V8) — 운영 DB schema migration 필요
- (단점) outbox row 에 들어간 traceparent 의 sampled flag 는 발행 시점까지 그대로 유지된다.
  결과적으로 sampling 정책이 발행 시점이 아니라 작성 시점 기준이 된다. 작성 trace 와
  발행 trace 가 같은 흐름이어야 하므로 일반적으로 맞는 동작.
- (단점) 페이로드 내 `traceId` 필드 중복 — 후속 ADR 에서 정리

## 검증

- 단위 테스트: `OutboxWriterTest.write_capturesActiveTraceContextAsW3cTraceparent`,
  `OutboxRelayTest.publishPending_propagatesTraceparentHeaderFromRow`
- 통합 검증 (수동): Tempo 에 trace 가 단일 흐름으로 그려지는지 확인 (잡 제출 → callback)

## 후속 ADR

- [ADR-0019](0019-prometheus-exemplars.md): Prometheus Exemplars — metric 에서 trace 로
  한 번 클릭 jump (적용)
- [ADR-0021](0021-otel-baggage-domain-context-propagation.md): OTel Baggage 자동 전파 —
  owner / cost-center / priority 를 trace / log / metric 라벨로 (적용)
- 백로그: tracestate (RFC 9.5.2) 보존 — vendor-specific sampling 정보 유지
- 백로그: `JobEvent.traceId` payload 필드 deprecate

## 용어 풀이 (쉽게)

- **distributed trace (분산 추적)** — 요청 하나가 여러 서비스(오케스트레이터→워커→콜백)를 거쳐도 같은 ID로 묶어 끝까지 한 흐름으로 따라가는 것. 택배 송장번호 하나로 출고부터 도착까지 추적하는 셈.
- **span / spanId** — 한 흐름 안의 작은 한 구간(예: DB 저장, Kafka 발행). spanId로 "이 구간은 저 구간의 자식"임을 가리켜 단계별로 누가 느렸는지 본다.
- **trace context 전파 (propagation)** — 이 추적 ID를 메시지 헤더에 실어 보내, 받는 쪽이 끊지 않고 이어받게 하는 것. 안 실어 보내면 거기서 추적이 끊겨 따로 노는 두 흐름이 된다.
- **sampling (표본 추출)** — 모든 요청의 추적을 다 저장하면 비싸서, 일부만 골라 남기는 것. 흐름의 시작에서 "이건 남긴다/버린다"를 정해 끝까지 같이 가져가야 절반만 보이는 사고가 안 난다.
- **W3C traceparent** — 위 추적 ID·구간 ID·표본 여부를 한 줄 표준 포맷으로 담은 헤더. 표준이라 어떤 도구든 같은 방식으로 읽고 이어 붙인다.
