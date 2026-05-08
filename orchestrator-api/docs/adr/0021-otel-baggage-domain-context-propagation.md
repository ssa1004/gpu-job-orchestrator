# ADR-0021: OpenTelemetry Baggage — owner / cost-center / priority 자동 전파

## 상태
적용

## 배경

ADR-0018 에서 W3C trace context (`traceparent`) 를 outbox / Kafka / HTTP 헤더로 흘려서
*trace 의 위치 (어디서 왔는지)* 는 이어졌다. 그런데 운영 / 회계 / 알림 측에서 가장 자주
하는 질문은 다음 세 가지인데, 이건 traceId 만으로 못 푼다:

1. *"이 시간대에 alice 의 잡들이 전부 어디서 막혀 있나?"* — owner 별 trace 검색
2. *"research-vision 부서의 GPU 시간이 갑자기 어디로 튀었나?"* — cost-center 별 metric 분리
3. *"HIGH priority 잡의 dispatch latency 만 따로 보고 싶다"* — priority 별 dashboard

지금까지의 우회: 각 서비스가 자기 페이로드 / Kafka payload 를 까서 owner 를 추출 → log /
metric 라벨로 박음. 페이로드마다 위치가 다르고, *전파 누락* 시 해당 hop 부터 라벨이 빈다.

표준 해법: **W3C Baggage** (RFC 9.5.3). traceparent 와 *세트* 인 별도 헤더 (`baggage:
key1=value1,key2=value2`). 한 번 활성화하면:

- HTTP 호출 / Kafka 헤더 / gRPC metadata 로 *자동 전파*.
- consumer 측 instrumentation 이 *자기 trace 의 baggage 로 풀어 넣음*.
- log / metric tag 로도 동시 노출 (Spring Boot 3 의 `management.tracing.baggage` 옵션).

Datadog 의 trace tags / NewRelic 의 attributes 와 같은 컨셉. OTel 진영의 표준 형태.

## 결정

### 1) baggage 키 화이트리스트 (`JobBaggage.ALLOWED`)

`{ owner, cost-center, priority }` 만 채택. *어떤 값도 baggage 에 담기 전에 화이트리스트
검증* — sensitive 값 (token / PII / secret) 의 우발적 전파 차단. 길이 cap 128 자 / entry.

baggage 는 매 hop 마다 헤더로 직렬화되어 *network overhead* 가 늘어난다. cap 이 곧 운영
비용 cap 이라 보수적으로 시작.

### 2) 진입점 (`BaggageHandlerInterceptor`)

`/api/**` 컨트롤러 진입 직전에 `SecurityContext` 에서 baggage 후보 추출:

- JWT principal → `owner = sub`, `cost-center = cost_center claim`
- non-JWT auth → `owner = auth.getName()` (Permissive 로컬 dev)
- no auth → baggage 채우지 않음 (다음 요청에 leak 차단)

`BaggagePopulator.activate(...)` 가 try-with-resources 로 활성화 →
`afterCompletion` 에서 close. *반드시* AutoCloseable scope 패턴 — 누락 시 thread-local
에 baggage 가 살아남아 다음 요청에 박히면 회계 / 보안 사고.

### 3) MDC 동시 미러링

baggage 활성화 시 같은 키 / 값을 MDC 에도 set, close 시 같이 unset.
`logging.pattern.level` 에 `%X{owner:-}`, `%X{cost-center:-}` 추가 → 모든 로그 라인이 자동
색인 가능. ELK / Loki 측에서 owner 별 grep 한 번에 가능.

Spring Boot 3.4+ 에서는 `management.tracing.baggage.correlation.fields` 가 같은 일을
자동화. 우리는 명시적 populator 도 갖춰서 *baggage 키 추가시 의식적으로 MDC 도 갱신*
하도록 (코드에서 review 가능).

### 4) Outbox / Kafka 헤더 박제 (V9 마이그레이션)

`OutboxWriter` 가 row INSERT 시점 (T0) 의 baggage 를 RFC 9.5.3 단일 문자열로 박제 →
`outbox.baggage` 컬럼. polling 시점 (T1) 에 `OutboxRelay.buildRecord` 가 그걸 Kafka
`baggage` 헤더로 복원. consumer (worker / billing-listener) 가 OTel propagator 로 자동 추출
→ 자기 trace 의 baggage 로 풀어 metric / log 라벨에 박는다.

값에 reserved 문자 (콤마 / 등호 / 세미콜론) 가 들어가면 percent-encoding (`URLEncoder`).
Sensitive 키는 OutboxWriter 단계에서도 한 번 더 화이트리스트로 거른다 (방어선 중첩).

### 5) Spring Boot 의 `management.tracing.baggage` 설정

```yaml
management.tracing.baggage:
  remote-fields: [owner, cost-center, priority]      # 받을 키
  correlation:
    enabled: true
    fields: [owner, cost-center, priority]            # MDC 미러링
  tag-fields: [owner, cost-center, priority]          # span tag 동시 노출
```

이게 inbound HTTP / outbound HTTP propagation 의 자동 wiring. Kafka 는 위의 outbox
박제 + 헤더 복원으로 처리.

## traceId / spanId / span tag / baggage 의 차이

| 면 | traceId / spanId | span tag | baggage |
|---|---|---|---|
| 라이프타임 | 한 trace 동안 (자동) | 한 span 에만 | 한 trace 동안 (전파 가능) |
| 전파 방식 | traceparent 헤더 | 전파 X (span 내부에만) | baggage 헤더 (모든 hop) |
| 의미 | 위치 식별자 | 그 span 의 메타 | 도메인 컨텍스트 |
| 개수 / 크기 | 고정 | 무제한 | 작아야 (network overhead) |

## 왜 baggage 크기를 cap 하는가

baggage 는 모든 hop 마다 헤더로 직렬화되어 *모든 outbound 호출에 네트워크 비용을 부과*.
극단적으로 큰 baggage 는 트래픽 수배 증가 / TCP 패킷 분할 / connection limit 도달 위험.

OTel 측 권장: baggage 전체가 ~8KB 미만, 한 entry 가 ~256자 미만. 우리는 더 보수적으로
화이트리스트 3개 × 128자 = 최대 ~400 byte 로 제한.

## 왜 sensitive 정보를 baggage 에 두지 않는가

baggage 는 *trace 가 닿는 모든 hop* 에 노출된다. 외부 SaaS (Tempo / Honeycomb), 다른 팀의
gRPC service, 로그 수집 파이프라인까지. JWT 토큰 / PII / API key 가 baggage 에 들어가면
모든 hop 에 그것이 같이 흘러들어가 사고 면적이 폭증.

방어:
1. `JobBaggage.ALLOWED` 화이트리스트 (코드 가드)
2. `BaggagePopulator.activate(...)` 가 화이트리스트 외 키 silent skip
3. `OutboxWriter` 가 헤더 박제 직전에 한 번 더 화이트리스트 검증
4. ADR 본문에서 정책 명시 (코드 리뷰 시 reviewer 가 인지)

## 다시 검토할 시점

- consumer 측 (worker / billing-listener) 도 baggage 자동 추출 + Micrometer Tracing
  baggage 옵션을 wiring 한 시점에 *진정한 cross-system propagation* 이 시작된다. 지금은
  producer 측 박제까지.
- Tempo 의 `service.{name}` + baggage 라벨이 결합한 search 가 운영에 도움이 되면, baggage
  키 추가 (예: `request-source`, `experiment-id`).
- baggage 가 작은 데도 트래픽이 의미 있게 늘어나면 *sampling 시 baggage 만 drop* 하는 옵션
  검토 (sampled=false 인 trace 는 baggage 도 보내지 않기).

## 참고 자료

- W3C Baggage — https://www.w3.org/TR/baggage/ (RFC 9.5.3 status)
- OpenTelemetry baggage spec — https://opentelemetry.io/docs/specs/otel/baggage/
- Spring Boot 3 Tracing — https://docs.spring.io/spring-boot/reference/actuator/tracing.html
- ADR-0018 (W3C trace context Kafka 전파) — baggage 가 traceparent 와 같은 channel 로 흐름
- ADR-0019 (Prometheus Exemplars) — exemplar 는 trace 만, baggage 는 별도 라벨로 노출
