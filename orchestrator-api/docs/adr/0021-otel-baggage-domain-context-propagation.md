# ADR-0021: OpenTelemetry Baggage — owner / cost-center / priority 자동 전파

## 상태
적용

## 배경

ADR-0018 에서 W3C trace context (`traceparent`) 를 outbox / Kafka / HTTP 헤더로 흘려서
trace 의 위치 (어디서 왔는지) 는 이어졌다. 그런데 운영 / 회계 / 알림 측에서 가장 자주
하는 질문은 다음 세 가지인데, 이건 traceId 만으로 못 푼다.

1. "이 시간대에 alice 의 잡들이 전부 어디서 막혀 있나?" — owner 별 trace 검색
2. "research-vision 부서의 GPU 시간이 갑자기 어디로 튀었나?" — cost-center 별 metric 분리
3. "HIGH priority 잡의 dispatch latency 만 따로 보고 싶다" — priority 별 dashboard

지금까지의 우회는 각 서비스가 자기 페이로드 / Kafka payload 를 까서 owner 를 추출한 뒤
로그·메트릭 라벨로 박는 방식이었다. 페이로드마다 owner 위치가 다르고, 전파가 누락되면
해당 hop 부터 라벨이 빈다.

표준 해법은 W3C Baggage (RFC 9.5.3). traceparent 와 짝으로 나가는 별도 헤더로,
`baggage: key1=value1,key2=value2` 포맷이다. 한 번 활성화하면 다음 효과를 얻는다.

- HTTP 호출 / Kafka 헤더 / gRPC metadata 로 자동 전파
- consumer 측 instrumentation 이 자기 trace 의 baggage 로 자동 복원
- log / metric tag 로도 동시 노출 (Spring Boot 3 의 `management.tracing.baggage` 옵션)

OpenTelemetry 표준에 정의된 형태로, 같은 개념의 자체 구현이 여러 APM 도구에 존재한다.

## 결정

### 1) baggage 키 화이트리스트 (`JobBaggage.ALLOWED`)

`{ owner, cost-center, priority }` 만 채택. 어떤 값도 baggage 에 담기 전에 화이트리스트로
검증해서 sensitive 값 (token / PII / secret) 의 우발적 전파를 차단한다. 길이는 entry 당
128자로 cap.

baggage 는 매 hop 마다 헤더로 직렬화되어 network overhead 가 늘어난다. 키와 길이의 cap 이
곧 운영 비용의 cap 이라 보수적으로 시작했다.

### 2) 진입점 (`BaggageHandlerInterceptor`)

`/api/**` 컨트롤러 진입 직전에 `SecurityContext` 에서 baggage 후보를 추출한다.

- JWT principal → `owner = sub`, `cost-center = cost_center claim`
- non-JWT auth → `owner = auth.getName()` (Permissive 로컬 dev)
- no auth → baggage 채우지 않음 (다음 요청에 leak 차단)

`BaggagePopulator.activate(...)` 가 try-with-resources 로 활성화하고 `afterCompletion`
에서 close. 반드시 AutoCloseable scope 패턴을 지켜야 한다. 누락하면 thread-local 에
baggage 가 살아남아 다음 요청에 그대로 박히고, 그 결과는 회계 / 보안 사고로 이어진다.

### 3) MDC 동시 미러링

baggage 활성화 시 같은 키 / 값을 MDC 에도 set, close 시 같이 unset.
`logging.pattern.level` 에 `%X{owner:-}`, `%X{cost-center:-}` 를 추가하면 모든 로그
라인이 자동으로 색인 가능해진다. ELK / Loki 측에서 owner 별 grep 이 한 번에 끝난다.

Spring Boot 3.4+ 에서는 `management.tracing.baggage.correlation.fields` 가 같은 일을
자동으로 해 준다. 우리는 명시적 populator 도 따로 두어, baggage 키를 추가할 때 MDC 갱신도
의식적으로 같이 가져가도록 (코드 리뷰에서 보이도록) 했다.

### 4) Outbox / Kafka 헤더 스냅샷 (V9 마이그레이션)

`OutboxWriter` 가 row INSERT 시점 (T0) 의 baggage 를 RFC 9.5.3 단일 문자열로 만들어
`outbox.baggage` 컬럼에 그대로 저장한다. polling 시점 (T1) 에 `OutboxRelay.buildRecord`
가 그 값을 Kafka `baggage` 헤더로 복원. consumer (worker / billing-listener) 가 OTel
propagator 로 자동 추출 → 자기 trace 의 baggage 로 풀어 metric / log 라벨에 박는다.

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
스냅샷 + 헤더 복원으로 처리한다.

## traceId / spanId / span tag / baggage 의 차이

| 면 | traceId / spanId | span tag | baggage |
|---|---|---|---|
| 라이프타임 | 한 trace 동안 (자동) | 한 span 에만 | 한 trace 동안 (전파 가능) |
| 전파 방식 | traceparent 헤더 | 전파 X (span 내부에만) | baggage 헤더 (모든 hop) |
| 의미 | 위치 식별자 | 그 span 의 메타 | 도메인 컨텍스트 |
| 개수 / 크기 | 고정 | 무제한 | 작아야 (network overhead) |

## 왜 baggage 크기를 cap 하는가

baggage 는 모든 hop 마다 헤더로 직렬화되어 모든 outbound 호출에 네트워크 비용을 부과한다.
극단적으로 큰 baggage 는 트래픽 수배 증가 / TCP 패킷 분할 / connection limit 도달 위험을
가져온다.

OTel 권장값은 baggage 전체 ~8KB 미만, 한 entry 가 ~256자 미만. 우리는 더 보수적으로
화이트리스트 3개 × 128자 = 최대 ~400 byte 로 제한했다.

## 왜 sensitive 정보를 baggage 에 두지 않는가

baggage 는 trace 가 닿는 모든 hop 에 노출된다. 외부 SaaS (Tempo / Honeycomb), 다른 팀의
gRPC service, 로그 수집 파이프라인까지 모두 포함. JWT 토큰 / PII / API key 가 baggage 에
들어가면 그 값이 모든 hop 에 같이 흘러들어 사고 면적이 폭증한다.

방어:
1. `JobBaggage.ALLOWED` 화이트리스트 (코드 가드)
2. `BaggagePopulator.activate(...)` 가 화이트리스트 외 키를 silent skip
3. `OutboxWriter` 가 헤더에 담기 직전에 한 번 더 화이트리스트 검증
4. ADR 본문에서 정책을 명시 (코드 리뷰 시 reviewer 가 인지)

## 다시 검토할 시점

- consumer 측 (worker / billing-listener) 도 baggage 자동 추출 + Micrometer Tracing
  baggage 옵션을 wiring 한 시점에 cross-system propagation 이 본격 가동된다. 현재는
  producer 측까지만 정리.
- Tempo 의 `service.{name}` + baggage 라벨 결합 검색이 운영에 도움이 되면 baggage 키를
  추가 (예: `request-source`, `experiment-id`).
- baggage 가 작은 데도 트래픽이 의미 있게 늘어나면 sampling 시 baggage 만 drop 하는 옵션
  검토 (sampled=false 인 trace 는 baggage 도 보내지 않기).

## 용어 풀이 (쉽게)

- **baggage (수하물)** — 추적 ID와 함께 모든 구간을 따라다니는 작은 도메인 꼬리표(owner·부서·우선순위). 짐가방에 이름표를 붙여 어느 환승지에서든 누구 짐인지 알아보게 하는 셈.
- **whitelist (화이트리스트)** — baggage에 담아도 되는 키를 미리 정한 허용 목록. 토큰·개인정보 같은 민감 값이 실수로 따라다니지 못하게 막는 울타리.
- **MDC (로그 진단 컨텍스트)** — 로그 한 줄 한 줄에 owner 같은 값을 자동으로 찍어주는 자리. 덕분에 "alice의 로그만" 같은 검색이 한 번에 된다.
- **PII (개인식별정보)** — 이름·이메일처럼 사람을 특정할 수 있는 민감 정보. baggage는 모든 구간에 노출되므로 PII는 절대 담지 않는다.
- **thread-local leak (스레드 누수)** — baggage를 쓰고 제대로 닫지 않으면 그 값이 스레드에 남아 다음 요청에 엉뚱하게 묻어가는 사고. 그래서 반드시 try-with-resources로 자동으로 닫는다.

## 참고 자료

- W3C Baggage — https://www.w3.org/TR/baggage/ (RFC 9.5.3 status)
- OpenTelemetry baggage spec — https://opentelemetry.io/docs/specs/otel/baggage/
- Spring Boot 3 Tracing — https://docs.spring.io/spring-boot/reference/actuator/tracing.html
- ADR-0018 (W3C trace context Kafka 전파) — baggage 가 traceparent 와 같은 channel 로 흐름
- ADR-0019 (Prometheus Exemplars) — exemplar 는 trace 만, baggage 는 별도 라벨로 노출

## 도메인 시나리오

운영 incident 한 건의 추적 경로를 예로 들면 다음과 같다.

1. Grafana 대시보드의 "p95 latency by cost-center" 패널에서 `research-vision` 부서가
   2초 spike. 이전엔 모든 잡이 한 라벨로만 보였기 때문에 어느 부서가 원인인지 보려면
   payload 를 직접 까야 했다.
2. baggage 가 metric tag 로 자동 노출되므로, cost-center 별로 break down 한 패널에서
   바로 부서가 식별된다.
3. 같은 panel 의 exemplar (ADR-0019) 클릭 → 그 spike 를 만든 trace ID 로 바로 jump.
4. trace view 에서 baggage 의 `owner` 값으로 어느 사용자의 잡인지까지 한 흐름에 드러남.

baggage 화이트리스트가 좁다는 점이 이 시나리오의 강점이다. 이 정도 수준의 도메인
컨텍스트만 trace 에 따라다니면 운영 / 회계 측 질문 대부분이 한 화면에서 풀린다.
