# ADR-0020: AsyncAPI spec 자동 생성 + Pact-style consumer-driven contract test

## 상태
적용

## 배경

`JobEvent` sealed interface 가 producer 측 코드상의 contract 다. 그러나 외부 consumer
(worker pool / billing-listener / dashboard) 는 그 코드를 직접 들여다보지 않고, Kafka 에
실제로 흐르는 JSON 만 본다. 그래서 다음 시나리오가 운영에서 자주 깨진다.

```
1) producer 가 record 의 필드를 rename / remove (compile-OK, test-OK)
2) 새 record 가 발행됨
3) consumer 가 그 필드를 못 찾고 NPE / parse fail
4) outbox relay 는 이미 commit 된 메시지를 계속 push → DLQ 폭증
```

OpenAPI 가 동기 REST 를 위한 것이라면, 이벤트 기반 통신은 별도의 spec 모델이 필요하다.
표준이 **AsyncAPI 3.0** — channel / message / operation 어휘로 발행/구독 contract 를 표현.

추가로, spec 만 있으면 consumer 가 어느 부분에 의존하는지는 여전히 producer 쪽에서
모른다. 그걸 명시적으로 고정하는 패턴이 consumer-driven contract (CDC) 다. Pact /
Spring Cloud Contract 로 잘 알려져 있다. 우리는 모노레포라 broker 인프라 없이 in-repo
expectations.json 으로 같은 효과를 얻을 수 있다.

## 결정

### 1) AsyncAPI 3.0 문서 자동 생성 + git-committed baseline

`com.example.gwp.orchestrator.contract.EventCatalog` 가 발행 이벤트의 schema 단일 source
of truth. 빌더 (`AsyncApiSpecBuilder`) 가 카탈로그 → AsyncAPI 3.0 nested Map 변환,
`AsyncApiSpecWriter` 가 YAML / JSON 직렬화.

체크인된 baseline `docs/asyncapi/job-events.yaml` 을 외부 팀에 공유하는 artifact 로
운영한다. 코드 변경이 baseline 을 바꾸면 `AsyncApiSpecBaselineTest` 가 fail 한다.
개발자가 의식적으로 갱신 후 commit 해야 한다. 자동 덮어쓰기는 하지 않는다 — schema
진화는 항상 의식적인 결정이어야 한다.

### 2) Pact-style in-repo expectations.json

각 consumer 가 자기가 실제로 사용하는 필드 / enum 값을 명시적으로 고정한다.

```
src/test/resources/contracts/expectations/
  ├── worker.json
  └── billing-listener.json
```

`ConsumerExpectationsContractTest` 가 빌드마다 모든 expectations 를 카탈로그에 대해
검증 (`ContractVerifier`). 한 명이라도 깨지면 어느 consumer / 어떤 필드 / 어떤 값인지가
메시지에 적혀 fail 된다.

### 3) Schema evolution 규칙 (코드 / ADR / 빌드로 강제)

- **OK (backward-compat)**: optional 필드 추가. consumer 는 모르는 필드 무시.
  AsyncAPI schema 는 `additionalProperties: true` 로 명시.
- **BREAKING**: 기존 필수 필드 제거 / 이름 변경 / 타입 변경 / enum 값 제거.
  최소 한 명의 consumer expectation 을 깨뜨리면 빌드 fail.
- **주의**: required → optional 완화는 spec 상 forward-compat 이지만 consumer 가
  non-null 가정했다면 런타임 NPE. 사전 협의 + 점진적 롤아웃.

### 4) Catalog ↔ record 정합성 컴파일 후 검증

`EventCatalogConsistencyTest` 가 다음을 reflection 으로 매번 검증:

- catalog 의 eventType 집합 = `JobEvent.permittedSubclasses()` 의 simpleName 집합
- 각 catalog 의 `properties` 키 ⊆ record component 이름들
- 각 record component 이름 ⊆ catalog `properties` 키들 (over-promised contract 방지)

→ "record 에 새 component 추가했는데 catalog 갱신 깜빡" 같은 사고가 컴파일 후 빌드에서 즉시 잡힌다.

## 왜 OpenAPI 가 아닌 AsyncAPI

| 면 | OpenAPI | AsyncAPI 3.0 |
|---|---|---|
| 모델 | request / response (synchronous) | channel / message / operation (pub-sub) |
| topic / queue 표현 | 없음 | channel.address |
| 다중 protocol | HTTP 만 | Kafka / SQS / NATS / WebSocket / MQTT |
| 도구 | Swagger UI / openapi-generator | AsyncAPI Studio / asyncapi-generator |

이벤트 기반 통신을 OpenAPI 로 표현하면 "POST /events 의 request body 가 N 종 union" 같은
어색한 우회가 생긴다. AsyncAPI 는 처음부터 그 어휘를 가지고 출발한다.

## 왜 full Pact (broker) 가 아닌 in-repo

Pact / Pact Broker 는 contract 가 분리된 레포의 producer / consumer 사이를 잇는 것이
주된 설계다. 여기서는 다음과 같은 환경이라 in-repo 로 충분하다.

- producer / worker / billing-listener 가 모두 같은 모노레포 (또는 곧 그렇게 될) 일정.
- consumer 가 "expectations 갱신 PR → producer 가 받는 PR" 흐름이 git 로 충분히 단순.
- broker 운영 (DB / 인증 / API gateway) 비용이 지금 단계에서 ROI 가 낮다.

## 다시 검토할 시점

- consumer 팀이 여러 repo 로 분산되고 각자 다른 release cadence 를 가지면 Pact Broker 의
  verification matrix 가 필요해진다 (어느 producer 버전 × 어느 consumer 버전 조합이 호환?).
- Avro / Protobuf 같은 schema registry 기반 binary 포맷으로 옮기면 Confluent Schema Registry
  + 내장 backward / forward compatibility check 로 같은 효과를 더 강하게 얻는다 — JSON 의
  open-world 가 아니라 strict schema 쪽으로.
- AsyncAPI Studio 의 self-hosted 인스턴스 + CI 의 spec lint (`asyncapi validate`) 도 도입 후보.

## 참고 자료

- AsyncAPI 3.0 specification — https://www.asyncapi.com/docs/reference/specification/v3.0.0
- Pact / consumer-driven contracts — https://docs.pact.io/
- ADR-0004 (Outbox 패턴) — Outbox 가 이 contract 의 발행 channel
- ADR-0018 (W3C trace propagation) — outbox 가 contract 외에 metadata (trace) 를 어떻게 전파하는지
