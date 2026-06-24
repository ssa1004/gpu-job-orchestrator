# ADR-0019: Prometheus Exemplars — Metric ↔ Trace 연결

## 상태
적용

## 배경

운영 dashboard 에서 가장 자주 일어나는 흐름:

```
Grafana 패널 → "p95 latency 가 2초로 spike 났네"
            ↓
            "근데 어떤 잡 / 어떤 trace 가 그 spike 를 만들었지?"
            ↓
   기존: Tempo / Jaeger 에 가서 시간 + 서비스 + 사용자 필터로 추정 검색 → 정확한 trace 못 찾음
```

metric 과 trace 가 같은 시스템에서 흐르는데도 지금 보고 있는 spike 의 trace 로 한 번에
도달할 수 없다는 점이 약점이다. 운영 incident 응답 시간이 늘어나는 원인이 된다.

표준 해법은 Prometheus Exemplar 다. metric data point (특히 histogram bucket) 에
trace_id 를 attach 한다. Grafana 가 그 점에 작은 다이아몬드 마커를 그려 주고, 클릭하면
Tempo / Jaeger 의 그 trace 로 바로 jump 할 수 있다.

상용 APM 들도 같은 컨셉을 자체 구현으로 제공한다. 오픈소스 진영에서는 2021 년
OpenMetrics spec 에 정식 채택되어 Prometheus 2.26+ 에서 지원된다.

## 결정

### Histogram bucket 에 exemplar 자동 attach

Prometheus exemplar 의 동작:

```
# HELP gwp_orchestrator_job_submit_seconds submit() wall-clock time
# TYPE gwp_orchestrator_job_submit_seconds histogram
gwp_orchestrator_job_submit_seconds_bucket{le="0.1"} 142
gwp_orchestrator_job_submit_seconds_bucket{le="0.25"} 198
gwp_orchestrator_job_submit_seconds_bucket{le="0.5"} 215  # {trace_id="0af7651916cd43dd"} 0.341 1715200345.123
gwp_orchestrator_job_submit_seconds_bucket{le="1"} 220   # {trace_id="b7ad6b7169203331"} 0.892 1715200367.456
gwp_orchestrator_job_submit_seconds_bucket{le="+Inf"} 221
```

각 bucket 마다 최근 1개 trace_id 만 attach — per-bucket × 1 → cardinality 폭증이 없다
(metric 자체는 bucket 수 × 라벨 조합만 늘어나고, exemplar 는 별도 storage 영역에 들어간다).

### 어떻게 wiring 되는가 — Spring Boot 3.3 자동

```
Tracer (이미 있음)
  ↓ [PrometheusExemplarsAutoConfiguration 가 자동 wiring]
SpanContext (Prometheus client 측 인터페이스)
  ↓
PrometheusMeterRegistry → Histogram bucket 마다 exemplar 자동 첨부
```

코드 추가 0 — `Tracer` 빈만 있으면 (이미 있음) Spring Boot 3.3 actuator-autoconfigure 의
`PrometheusExemplarsAutoConfiguration` 이 SpanContext 빈을 만들어 PrometheusMeterRegistry
와 연결한다. 이 ADR 의 구현은 사실상 조건을 갖추는 작업 — histogram 활성화 + scrape config.

### Histogram 활성화

기존 Counter 는 그대로 두고, latency 측정용 Timer 를 새로 추가:

```java
this.submitTimer = Timer.builder("gwp_orchestrator_job_submit_seconds")
        .publishPercentileHistogram()
        .serviceLevelObjectives(
                Duration.ofMillis(100), Duration.ofMillis(250),
                Duration.ofMillis(500), Duration.ofSeconds(1),
                Duration.ofSeconds(2),  Duration.ofSeconds(5),
                Duration.ofSeconds(10))
        .register(registry);
```

`publishPercentileHistogram()` + `serviceLevelObjectives(...)` 로 SLO bucket 7개 명시:
100ms / 250ms / 500ms / 1s / 2s / 5s / 10s. SLO 측정과 exemplar attach point 를 같은 bucket
으로 통일.

### SLO bucket 선택 근거

- 너무 fine (10ms/20ms/30ms/...) → bucket 수 ↑ → cardinality 폭증, 읽기 쉽지 않음
- 너무 coarse (1s/10s/+Inf) → p95 의 의미가 없어짐 (양쪽 끝만 알게 됨)
- 7개 SLO bucket 이 80% 사용 케이스 cover. 추가로 Micrometer 의 percentile-histogram 이
  자동 추가 bucket 을 더해 정확도 보강.

### application.yml 변경

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        gwp_orchestrator_job_submit_seconds: true
        gwp_orchestrator_dispatch_latency_seconds: true
  prometheus:
    metrics:
      export:
        enabled: true   # OpenMetrics 포맷 + exemplar 노출
```

### Prometheus 스크레이프 측 — `enable_exemplar_storage`

scrape config:

```yaml
global:
  scrape_interval: 15s
storage:
  exemplars:
    max_exemplars: 100000   # in-memory exemplar 보관 한도

scrape_configs:
  - job_name: orchestrator-api
    metrics_path: /actuator/prometheus
    # OpenMetrics format 으로 스크레이프 — exemplar 가 OpenMetrics spec 일부
    honor_labels: true
    scheme: http
    static_configs:
      - targets: ["orchestrator-api.gwp.svc.cluster.local:8080"]
```

### Grafana datasource 측 — `exemplarTraceIdDestinations`

```yaml
# datasources/prometheus.yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    jsonData:
      exemplarTraceIdDestinations:
        - name: trace_id
          datasourceUid: tempo-uid       # Tempo / Jaeger UID
          urlDisplayLabel: "View trace"
```

Grafana panel 에서 histogram bucket 의 다이아몬드 마커 클릭 → "View trace" → Tempo /
Jaeger 가 해당 trace_id 의 흐름 표시.

## 대안

### Custom dashboard link (수동)

탈락 — 이유:

- 엔지니어가 사고 발생 시 이 spike 의 정확한 trace_id 를 유추해야 한다
- exemplar 이전 시대의 패턴 — 지금은 표준이 있다

### 시간 + 서비스 + 사용자 필터로 Tempo 검색

탈락 — 이유:

- spike 의 정확한 trace_id 가 아니라 그 시간대의 모든 trace 를 가져온다
- p95 spike 를 일으킨 1개 trace 와 normal trace 200개를 사람이 직접 구분해야 한다
- 시간이 지나면 Tempo retention 으로 사라진다 (보통 1주~30일)

### Prometheus Native Histogram

후속 검토 — 이유:

- Prometheus 2.40+ 정식 도입, 더 적은 메모리 + 임의 정밀도. 현재 Histogram bucket 의 후속.
- exemplar 도 native histogram 에 attach 가능 (spec 동일)
- 이 시스템의 Prometheus 버전이 stable 채널에 안정화될 때 마이그레이션. 별도 ADR.

### OpenTelemetry Collector 의 spanmetrics processor

탈락 — 이유:

- trace 로부터 metric 을 역으로 만드는 컨셉 — exemplar 와 다른 방향
- 이미 있는 metric pipeline (Micrometer → Prometheus) 에 추가 layer 필요
- 운영 복잡도 ↑. 후속 검토 가능.

## 결과

- p95 latency spike 의 정확한 trace 로 한 번 클릭에 jump (운영 incident 응답 시간 ↓)
- exemplar 자체가 cardinality 폭증을 안 일으킴 — per-bucket × 1
- 외부 의존성 0 — 이미 있는 Spring Boot 3.3 actuator + Tracer + Prometheus. 자동 wiring.
- SLO 측정 (95% < 2s) 도 같은 bucket 에서 직접 — error budget 자동 계산
- (단점) Histogram 활성화로 bucket 수만큼 시계열 ↑ — 그러나 cardinality 의 큰 비중은
  tag 조합이지 bucket 이 아님 (이 timer 들은 tag 0개)
- (단점) Prometheus 측 `enable_exemplar_storage` 설정 필요 — infrastructure 측 변경
- (단점) Grafana datasource 측 `exemplarTraceIdDestinations` 도 갖춰야 — UI 에서 jump 동작
- (단점) Tracer 빈이 noop 이거나 Tracer 빈이 없으면 exemplar 가 안 붙음 — 현재 구성에서는
  Tracer 가 항상 있으므로 OK

## 검증

- 단위 테스트: `JobMetricsTest.submitTimer_exposesHistogramBuckets_forExemplarAttachment` —
  bucket 형성을 SimpleMeterRegistry 로 검증 (exemplar 자체는 PrometheusMeterRegistry +
  SpanContext 가 있어야 attach 되므로 통합 테스트 영역).
- 통합 검증 (수동):
  ```
  curl http://localhost:8080/actuator/prometheus | grep gwp_orchestrator_job_submit_seconds_bucket
  ```
  output 에 `# {trace_id="..."} 0.341 ...` 같은 exemplar comment 가 보여야 함 (OpenMetrics).

## 후속 ADR (백로그)

번호는 실제 채택된 ADR 번호와 무관하며, 본 ADR 의 후속 작업 백로그입니다.
실제 ADR-0020 은 [AsyncAPI + consumer-driven contract](0020-asyncapi-and-consumer-driven-contract.md),
ADR-0021 은 [OTel Baggage 전파](0021-otel-baggage-domain-context-propagation.md) 가 채택되었습니다.

- Prometheus Native Histogram 마이그레이션 — bucket 갯수 폭증 문제 해결
- Tempo / Jaeger 와의 Grafana datasource 통합 자동화 (provisioning)

## 용어 풀이 (쉽게)

- **exemplar (대표 표본)** — 지표 그래프의 한 점에 "이 순간을 만든 바로 그 추적 ID"를 콕 붙여둔 표본. 그래프에서 튀어오른 점을 클릭하면 그 사건의 추적 화면으로 바로 점프한다.
- **histogram / bucket (히스토그램·구간)** — 응답 시간을 "0.1초 이하 몇 건, 0.5초 이하 몇 건"처럼 구간별로 세어 담는 통. 이 통 분포를 봐야 p95 같은 값을 뽑을 수 있다.
- **p95 / percentile (백분위)** — 느린 순으로 줄 세웠을 때 95번째 위치의 값. "100건 중 95건은 이 시간 안에 끝난다"는 뜻으로, 평균보다 꼬리(느린 요청)를 잘 드러낸다.
- **cardinality (카디널리티)** — 라벨 조합의 가짓수. 너무 많으면 저장·메모리가 폭발하는데, exemplar는 구간마다 1개만 붙여 이 폭발을 피한다.
