# Architecture Decision Records

각 결정의 배경(Context) → 결정(Decision) → 결과(Consequences) 를 기록합니다. 형식은 [Michael Nygard ADR](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions) 을 따릅니다.

| # | Title | Status |
|---|---|---|
| [0001](0001-spring-mvc-not-webflux.md) | Spring MVC over WebFlux | Accepted |
| [0002](0002-fabric8-direct-not-spring-cloud-kubernetes.md) | fabric8 직접 호출 (vs Spring Cloud Kubernetes) | Accepted |
| [0003](0003-callback-push-not-polling.md) | 워커 → 오케스트레이터 콜백 push (vs DB polling) | Accepted |
| [0004](0004-outbox-not-direct-kafka.md) | Outbox 패턴 (vs Kafka 직접 produce) | Accepted |
| [0005](0005-h2-default-postgres-prod.md) | H2 default + Postgres prod 프로필 분기 | Accepted |
| [0006](0006-domain-service-split-and-cache-self-invocation.md) | 도메인 서비스 4-way 분리 + Cache self-invocation 해결 | Accepted |
| [0007](0007-clock-injection-and-utc.md) | Clock 주입 + UTC 일관성 | Accepted |
