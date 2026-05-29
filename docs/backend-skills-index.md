# 백엔드 스킬 인덱스 — 이 레포에서 무엇을 배우나

> 이 레포가 시연하는 백엔드 / DevOps 패턴을 **"무엇 → 이 레포 어디서 → 왜(ADR) → 더 깊은 이론"** 으로 잇는 학습용 인덱스.
> "이 패턴 공부하려면 어디부터 보나"의 진입점. 설명을 다시 쓰지 않고 코드·결정·이론으로 연결만 한다.
>
> 도메인: **GPU 학습/추론 Job 오케스트레이터** — 사용자 JWT → job 제출 → K8s Job dispatch → 워커 콜백 → 종료 이벤트 발행(알림/빌링) 까지. Spring Boot(Kotlin) 백엔드 + Go 워커 + K8s/Terraform/ArgoCD/Prometheus DevOps 풀스택. ADR 전체 색인은 [orchestrator-api/docs/adr/](../orchestrator-api/docs/adr/).

## 작업 디스패치 · 라이프사이클

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **워커 → API 콜백 push (vs polling)** | [`InternalCallbackController`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/api/InternalCallbackController.kt) · 워커 측 [`worker/`](../worker/) | [ADR-0003](../orchestrator-api/docs/adr/0003-callback-push-not-polling.md) | 10~30분 GPU Job 은 상태 변경이 2~3회뿐 — polling 부하 대신 천이 시점 push |
| **K8s Job 직접 생성 (fabric8, vs Spring Cloud K8s)** | [`KubernetesJobDispatcher`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/adapter/kubernetes/KubernetesJobDispatcher.kt) | [ADR-0002](../orchestrator-api/docs/adr/0002-fabric8-direct-not-spring-cloud-kubernetes.md) | Job/probe/label 을 코드로 조립 — 추상화 레이어 없이 fabric8 client 직접 |
| **도메인 불변식 = 메서드로만 천이** | [`Job`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/domain/Job.kt) · [`JobStatus`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/domain/JobStatus.kt) | — | setter 금지, 상태 전이를 메서드로 — 불법 천이는 컴파일/런타임에 차단 |
| **라이프사이클 State Machine (사이드카)** | [`JobLifecycleStateMachine`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/lifecycle/JobLifecycleStateMachine.kt) | [ADR-0022](../orchestrator-api/docs/adr/0022-lifecycle-state-machine-sidecar.md) | 도메인 메서드 옆에 전이표를 선언적으로 — Mermaid 다이어그램 자동 생성 |
| **콜백 멱등 (already-terminal no-op)** | [`JobLifecycleService`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/application/JobLifecycleService.kt) | [ADR-0003](../orchestrator-api/docs/adr/0003-callback-push-not-polling.md) | 이미 종료된 Job 의 중복 콜백은 자동 무시 — at-least-once 콜백 대비 |
| **Clock 주입 + UTC 일관성** | [`ClockConfig`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/config/ClockConfig.kt) | [ADR-0007](../orchestrator-api/docs/adr/0007-clock-injection-and-utc.md) | `Clock` 빈 주입으로 시간 테스트 가능 + 전 구간 UTC |

→ 이론: `dev-lab/k8s` (Job / probe / 스케줄링), `dev-lab/system-design` (헥사고날 / 어댑터 경계), `dev-lab/temporal` (durable workflow 와 콜백 기반 오케스트레이션 대비)

## 메시징 · 일관성

| 패턴 | 이 레포 어디서 | 왜 (ADR) | 한 줄 |
|------|---------------|---------|-------|
| **Outbox 패턴 (vs Kafka 직접 produce)** | [`outbox/`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/outbox/) — [`OutboxWriter`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/outbox/OutboxWriter.kt) + [`OutboxRelay`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/outbox/OutboxRelay.kt) | [ADR-0004](../orchestrator-api/docs/adr/0004-outbox-not-direct-kafka.md) | 도메인 변경과 이벤트 INSERT 를 한 트랜잭션으로 — dual-write 유실/phantom 해소 |
| **OTel W3C trace context — Kafka header 전파** | Outbox payload + consumer | [ADR-0018](../orchestrator-api/docs/adr/0018-otel-kafka-trace-propagation.md) | producer→broker→consumer 가 한 trace 로 이어짐 (비동기 경계 추적) |
| **OTel Baggage — 도메인 컨텍스트 전파** | [`observability/baggage/`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/observability/baggage/) | [ADR-0021](../orchestrator-api/docs/adr/0021-otel-baggage-domain-context-propagation.md) | owner / cost-center / priority 를 요청 헤더 → 로그·트레이스 라벨로 자동 전파 |
| **AsyncAPI + consumer-driven contract** | [`contract/`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/contract/) | [ADR-0020](../orchestrator-api/docs/adr/0020-asyncapi-and-consumer-driven-contract.md) | 발행 이벤트 스키마를 spec 으로 박제 + Pact-style 소비자 기대 검증 |

→ 이론: `dev-lab/cdc` (Outbox vs CDC), `dev-lab/kafka` (전파 의미 / consumer), `dev-lab/distributed-systems` (exactly-once 환상)

## 분산 트랜잭션 · 워크플로우

| 패턴 | 이 레포 어디서 | 왜 | 한 줄 |
|------|---------------|-----|-------|
| **DLQ source 별 saga 단계 분리** | [`dlq/DlqSource`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/dlq/DlqSource.kt) · [`DlqAdminService`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/dlq/DlqAdminService.kt) | [ADR-0026](../orchestrator-api/docs/adr/0026-dlq-admin-api.md) | JOB_DISPATCH / CALLBACK / OUTBOX / PREEMPTION / DAG_EVAL — 흐름 단계별 영구 실패를 분리 격리 |
| **DLQ admin REST API (replay/discard/dry-run/audit)** | [`AdminDlqController`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/api/AdminDlqController.kt) · [`DlqBulkAdminService`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/dlq/DlqBulkAdminService.kt) | [ADR-0026](../orchestrator-api/docs/adr/0026-dlq-admin-api.md) | 8 endpoint — idempotency-key replay + confirm 없으면 dry-run + 전 조작 audit |
| **Job DAG 의존성 — 워크플로우 자동 진행** | [`DependencyResolutionService`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/application/DependencyResolutionService.kt) · [`DependencyGraph`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/domain/DependencyGraph.kt) | [ADR-0015](../orchestrator-api/docs/adr/0015-job-dependencies.md) | parent 종료 → child WAITING_DEPS 자동 진행, cycle 은 제출 시점 거절 |

→ 이론: `dev-lab/distributed-systems` (2PC vs Saga, 보상), `dev-lab/temporal` (durable execution 대안), `dev-lab/kafka` (DLQ / 재처리)

## 스케줄링 · 리더 선출

| 패턴 | 이 레포 어디서 | 왜 | 한 줄 |
|------|---------------|-----|-------|
| **Job priority + preemption** | [`PreemptionEvaluator`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/domain/PreemptionEvaluator.kt) · [`PreemptionService`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/application/PreemptionService.kt) | [ADR-0014](../orchestrator-api/docs/adr/0014-job-priority-preemption.md) | GPU 부족 시 저우선 Job 을 선점 — 도메인 평가기 + K8s priority class |
| **K8s Lease 기반 leader election (ShedLock 보강)** | [`leader/`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/leader/) — [`KubernetesLeaseLeaderElector`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/leader/KubernetesLeaseLeaderElector.kt) | [ADR-0017](../orchestrator-api/docs/adr/0017-k8s-lease-leader-election.md) | 다중 replica 중 한 인스턴스만 스케줄러 구동 — Lease 우선, DB ShedLock 폴백 |
| **쿼터 + 분산 락 (PG advisory lock)** | [`QuotaService`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/application/QuotaService.kt) · [`PgAdvisoryQuotaLock`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/application/PgAdvisoryQuotaLock.kt) | — | owner 별 GPU 쿼터 검사 — 동시 제출 경합을 advisory lock 으로 직렬화 |
| **KEDA event-driven 워커 autoscaling** | [`infrastructure/keda/scaledjob-gpu-worker.yaml`](../infrastructure/keda/scaledjob-gpu-worker.yaml) | [ADR-0008](../orchestrator-api/docs/adr/0008-keda-event-driven-autoscaling.md) | 큐 깊이로 GPU 워커 ScaledJob 스케일 — CPU 기반 HPA 로는 못 잡는 신호 |

→ 이론: `dev-lab/k8s` (Lease / scheduling / priority / KEDA), `dev-lab/distributed-systems` (leader election / 분산 락)

## 회복탄력성 (Resilience)

| 패턴 | 이 레포 어디서 | 왜 | 한 줄 |
|------|---------------|-----|-------|
| **retry + 지수 백오프 + jitter + circuit chain** | [`ResilientJobDispatcher`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/adapter/kubernetes/ResilientJobDispatcher.kt) · [`CircuitBreakerConfig`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/config/CircuitBreakerConfig.kt) | [ADR-0025](../orchestrator-api/docs/adr/0025-retry-with-jitter-and-circuit-chain.md) | Resilience4j — thundering herd 없이 K8s API 일시 장애 흡수 + 차단기 연쇄 |
| **retryable vs non-retryable 구분** | [`RetryableExceptionPredicate`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/adapter/kubernetes/RetryableExceptionPredicate.kt) | [ADR-0025](../orchestrator-api/docs/adr/0025-retry-with-jitter-and-circuit-chain.md) | 5xx/네트워크만 재시도, 4xx 는 즉시 실패 (재시도 무의미) — 워커 콜백도 동일 정책 |
| **Graceful shutdown — SIGTERM 단계화** | [`observability/availability/GracefulShutdownLifecycle`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/observability/availability/GracefulShutdownLifecycle.kt) | [ADR-0024](../orchestrator-api/docs/adr/0024-graceful-shutdown.md) | readiness off → in-flight drain → 종료 — 롤링 배포 중 요청 유실 방지 |

→ 이론: `dev-lab/resilience` (circuit breaker / bulkhead / 백오프), `dev-lab/networking` (timeout / 재시도 안전성)

## 관측성 (Observability) · SRE

| 패턴 | 이 레포 어디서 | 왜 | 한 줄 |
|------|---------------|-----|-------|
| **K8s liveness / readiness / startup probe 3종 분리** | [`orchestrator-api/k8s/deployment.yaml`](../orchestrator-api/k8s/deployment.yaml) · [`ApplicationReadinessCoordinator`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/observability/availability/ApplicationReadinessCoordinator.kt) | [ADR-0023](../orchestrator-api/docs/adr/0023-k8s-three-probes.md) | 셋의 의미가 달라 같은 endpoint 로 묶으면 CrashLoop / outage 증폭 |
| **Prometheus Exemplars — metric ↔ trace** | [`JobMetrics`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/observability/JobMetrics.kt) | [ADR-0019](../orchestrator-api/docs/adr/0019-prometheus-exemplars.md) | 느린 메트릭 한 점에서 바로 그 trace 로 점프 |
| **trace_id → MDC 상관관계** | [`CorrelationIdFilter`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/observability/CorrelationIdFilter.kt) | — | 한 trace_id 로 metric→trace→log 점프 |
| **SLO + error budget + 런북 연결** | [`prometheus-rules/orchestrator-slo.yaml`](../infrastructure/observability/prometheus-rules/orchestrator-slo.yaml) · [`docs/runbooks/`](runbooks/) | — | 알람마다 runbook URL — "어디부터 보나" 절차서 (outbox-lag / callback-missing / gpu-oom / …) |
| **대시보드 as code** | [`grafana-dashboards/`](../infrastructure/observability/grafana-dashboards/) | — | overview / gpu-fleet / gpu-cost 를 JSON 으로 버전관리 |

→ 이론: `dev-lab/observability` (3축 + SLI/SLO + exemplar), `dev-lab/incident-response` (런북 / postmortem), `dev-lab/performance` (USE/RED)

## DevOps · 플랫폼 · 보안

| 패턴 | 이 레포 어디서 | 왜 | 한 줄 |
|------|---------------|-----|-------|
| **Terraform 모듈화 (멀티 환경)** | [`infrastructure/terraform/`](../infrastructure/terraform/) — `modules/` + `environments/{cloud,onprem,hybrid}` | — | eks / gpu-nodes / vpc / storage / monitoring 을 모듈로, 환경별 조합 |
| **ArgoCD GitOps (base + overlays)** | [`infrastructure/ci-cd/argocd/`](../infrastructure/ci-cd/argocd/) | — | staging / production overlay — 선언적 배포 |
| **공급망 보안 — SBOM + Cosign + Kyverno** | [`infrastructure/security/`](../infrastructure/security/) | [ADR-0009](../orchestrator-api/docs/adr/0009-supply-chain-security.md) | 이미지 서명 검증 + admission 정책으로 미서명 거부 |
| **이미지 promotion 파이프라인 (dev→staging→prod)** | [`infrastructure/ci-cd/`](../infrastructure/ci-cd/) | [ADR-0012](../orchestrator-api/docs/adr/0012-image-promotion-pipeline.md) | 같은 다이제스트를 환경 간 승격 — 재빌드 없이 동일성 보장 |
| **Disaster Recovery — Velero + 시간별 백업** | [`infrastructure/dr/`](../infrastructure/dr/) · [`docs/dr/`](dr/) | [ADR-0010](../orchestrator-api/docs/adr/0010-disaster-recovery-strategy.md) | 클러스터/볼륨 백업 + 복구 절차 |
| **Chaos engineering 정기 실시** | [`infrastructure/chaos/`](../infrastructure/chaos/) · [`docs/dr/chaos-results.md`](dr/chaos-results.md) | [ADR-0011](../orchestrator-api/docs/adr/0011-chaos-engineering-practice.md) | 의도적 장애 주입 → 반응 관찰 → 결과 기록 |
| **Platform engineering self-service** | [`infrastructure/platform-engineering/`](../infrastructure/platform-engineering/) (Backstage + Crossplane) | [ADR-0013](../orchestrator-api/docs/adr/0013-platform-engineering-self-service.md) | 개발자가 카탈로그에서 셀프서비스로 리소스 프로비저닝 |
| **default-deny NetworkPolicy** | [`orchestrator-api/k8s/security/network-policy.yaml`](../orchestrator-api/k8s/security/network-policy.yaml) | — | 기본 차단 + 필요한 ingress/egress 만 허용 |

→ 이론: `dev-lab/k8s` (probe / NetworkPolicy / GitOps), `dev-lab/system-design` (IaC / 환경 분리), `dev-lab/observability` (관측 스택 IaC)

## Spring Boot 심화

| 패턴 | 이 레포 어디서 | 한 줄 |
|------|---------------|-------|
| **H2 default + Postgres prod 프로필 분기** | [`db/migration/`](../orchestrator-api/src/main/resources/db/) + 프로필 | [ADR-0005](../orchestrator-api/docs/adr/0005-h2-default-postgres-prod.md) — 로컬은 의존 0, prod 는 Postgres + Flyway |
| **Spring MVC (vs WebFlux)** | 전 컨트롤러 | [ADR-0001](../orchestrator-api/docs/adr/0001-spring-mvc-not-webflux.md) — I/O 패턴상 동기 MVC 가 단순/충분 |
| **도메인 서비스 분리 + Cache self-invocation 해결** | [`application/`](../orchestrator-api/src/main/kotlin/com/example/gwp/orchestrator/application/) | [ADR-0006](../orchestrator-api/docs/adr/0006-domain-service-split-and-cache-self-invocation.md) — `@Cacheable` 자기호출 우회 |
| **port/adapter (헥사고날)** | `domain` ↔ `application` ↔ `adapter` | dispatcher / storage / repository 를 인터페이스로 — Mock/Resilient/Kubernetes 교체 |

→ 이론: `dev-lab/system-design` (헥사고날 / 모듈 경계)

## 학습 순서 제안 (이 레포 기준)

1. **[README 빠른 실행](../README.md#빠른-실행)** → `make run-api` + `make demo` 로 전체 흐름 감 잡기
2. **[README 시스템 흐름 / 백엔드 핵심](../README.md)** → 컴포넌트 / 데이터 흐름
3. **[orchestrator-api/docs/adr/](../orchestrator-api/docs/adr/)** → 왜 그렇게 했나 (ADR 26개) ← 이 레포의 핵심 학습 자료
4. **위 패턴 표** 에서 관심 패턴 → 코드 + 해당 ADR + dev-lab 이론
5. **[docs/runbooks/](runbooks/)** → 운영자 관점 (알람 → 대응)
6. **[통합 시연](../README.md#통합-시연-mock)** → `make up` + `make integration-demo` 로 9 레포 portfolio set 안에서의 위치

> 짝 학습 레포: [dev-lab](https://github.com/ssa1004/dev-lab) (이론) ↔ 이 레포 (구현). 이론에서 "왜"를, 여기서 "실제로 어떻게"를 본다.
