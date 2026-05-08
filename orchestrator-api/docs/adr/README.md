# Architecture Decision Records

각 결정의 배경(Context) → 결정(Decision) → 결과(Consequences) 를 기록합니다. 형식은 [Michael Nygard ADR](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions) 을 따릅니다.

| # | 제목 | 상태 |
|---|---|---|
| [0001](0001-spring-mvc-not-webflux.md) | Spring MVC over WebFlux | 적용 |
| [0002](0002-fabric8-direct-not-spring-cloud-kubernetes.md) | fabric8 직접 호출 (vs Spring Cloud Kubernetes) | 적용 |
| [0003](0003-callback-push-not-polling.md) | 워커 → 오케스트레이터 콜백 push (vs DB polling) | 적용 |
| [0004](0004-outbox-not-direct-kafka.md) | Outbox 패턴 (vs Kafka 직접 produce) | 적용 |
| [0005](0005-h2-default-postgres-prod.md) | H2 default + Postgres prod 프로필 분기 | 적용 |
| [0006](0006-domain-service-split-and-cache-self-invocation.md) | 도메인 서비스 4-way 분리 + Cache self-invocation 해결 | 적용 |
| [0007](0007-clock-injection-and-utc.md) | Clock 주입 + UTC 일관성 | 적용 |
| [0008](0008-keda-event-driven-autoscaling.md) | KEDA event-driven worker autoscaling | 적용 |
| [0009](0009-supply-chain-security.md) | 공급망 보안 — SBOM + Cosign + Kyverno | 적용 |
| [0010](0010-disaster-recovery-strategy.md) | Disaster Recovery 전략 — Velero + 시간별 백업 | 적용 |
| [0011](0011-chaos-engineering-practice.md) | Chaos Engineering 정기 실시 | 적용 |
| [0012](0012-image-promotion-pipeline.md) | Image Promotion Pipeline (dev → staging → prod) | 적용 |
| [0013](0013-platform-engineering-self-service.md) | Platform Engineering — Backstage + Crossplane self-service | 적용 |
| [0014](0014-job-priority-preemption.md) | Job priority + preemption (Slurm/Kueue 패턴) | 적용 |
| [0015](0015-job-dependencies.md) | Job Dependencies (DAG) — 워크플로우 자동 진행 | 적용 |
| [0016](0016-cost-attribution.md) | Cost Attribution / Chargeback — FinOps 기반 | 적용 |
| [0017](0017-k8s-lease-leader-election.md) | K8s Lease 기반 Leader Election (ShedLock 보강) | 적용 |
| [0018](0018-otel-kafka-trace-propagation.md) | OTel W3C trace context — Kafka header 전파 | 적용 |
| [0019](0019-prometheus-exemplars.md) | Prometheus Exemplars — metric ↔ trace 연결 | 적용 |
| [0020](0020-asyncapi-and-consumer-driven-contract.md) | AsyncAPI spec 자동 생성 + Pact-style consumer-driven contract test | 적용 |
| [0021](0021-otel-baggage-domain-context-propagation.md) | OTel Baggage — owner / cost-center / priority 자동 전파 | 적용 |
| [0022](0022-lifecycle-state-machine-sidecar.md) | Job 라이프사이클 State Machine — 도메인 메서드 보강 사이드카 | 적용 |
| [0023](0023-k8s-three-probes.md) | K8s liveness / readiness / startup probe 3종 분리 | 적용 |
| [0024](0024-graceful-shutdown.md) | Graceful Shutdown — SIGTERM 후 in-flight 처리 단계화 | 적용 |
| [0025](0025-retry-with-jitter-and-circuit-chain.md) | Resilience4j Retry — Exponential Backoff with Jitter + CircuitBreaker Chain | 적용 |
