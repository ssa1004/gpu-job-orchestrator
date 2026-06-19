# Orchestrator API

GPU Job 의 생성부터 완료까지 관리하는 Spring Boot REST API 입니다. 사용자 요청을 검증하여
작업을 저장하고, Kubernetes Job 으로 실행 요청한 뒤, 워커가 보내는 콜백으로 상태를
갱신합니다. 결과 다운로드 URL 발급은 `PresignedUrlProvider` 인터페이스로 분리되어 있어
dev 에서는 Mock 으로 동작하고 운영에서는 실제 구현으로 교체할 수 있습니다.

## 기술 스택

Spring Boot 3.3, Kotlin (JVM 17), JPA + Flyway (DB 스키마 마이그레이션 도구), Spring Security
(OAuth2 + JWT), Redis, Kafka, fabric8-kubernetes-client (JVM 용 Kubernetes API 클라이언트),
Micrometer + OpenTelemetry (메트릭·트레이스 수집 라이브러리).

운영 환경 설계 배경은 [`docs/platform-design.md`](../docs/platform-design.md) 에, 의사결정
기록은 [`docs/adr/`](docs/adr/) (ADR = Architecture Decision Record, 설계 결정과 그
배경을 짧게 적은 문서) 에 정리되어 있습니다.

## 책임 범위

- GPU Job 제출 요청 검증 및 저장
- 사용자별 동시 실행 작업 수, GPU 사용량 제한
- Kubernetes batch/v1 Job 생성 요청
- Job 상태 저장 및 워커 콜백 처리
- 결과 파일 다운로드 URL 발급
- 상태 변경 이벤트의 Outbox 저장 후 Kafka 발행
- 로그 / 메트릭 / 트레이스에 Job 추적 정보 기록

## 처리 흐름

```text
1. 사용자가 Job 제출
   Client → JobController → JobSubmissionService
          → 쿼터 검사 → DB 저장 → Kubernetes Job 생성 요청 → Outbox 기록

2. 워커가 상태 콜백 전송
   Worker → InternalCallbackController → JobLifecycleService
          → 상태 전이 검증 → DB 갱신 → 조회 캐시 삭제 → 완료 이벤트 기록

3. 사용자가 결과 조회
   Client → JobController → JobQueryService
          → 권한 확인 → DB / Redis 조회 → 결과 URL 발급

4. 이벤트 발행
   OutboxRelay → 미발행 이벤트 조회 → Kafka 발행 → 발행 완료 표시
```

## 패키지 구조

| 패키지 | 역할 |
|---|---|
| `api` | REST 컨트롤러, 요청 / 응답 DTO, 예외 응답 |
| `application` | Job 제출 / 조회 / 라이프사이클 / 권한 / 쿼터 application service |
| `domain` | Job 애그리거트, JobSpec, 상태 / 우선순위 enum, repository 인터페이스, 도메인 예외 |
| `adapter.kubernetes` | Kubernetes Job 생성 / 취소 어댑터, Mock 구현 포함 |
| `adapter.storage` | 결과 다운로드 URL 발급 인터페이스, Mock 구현 |
| `outbox` | DB 저장 이벤트의 Kafka 발행 |
| `observability` | 요청 ID 필터, 메트릭 기록 |
| `config` | 보안, 캐시, 시간, 스케줄 설정 |

도메인 서비스를 단일 클래스로 통합하지 않고 책임별로 분리한 이유는 [ADR-0006](docs/adr/0006-domain-service-split-and-cache-self-invocation.md)
에 정리되어 있습니다. 하나의 서비스에 제출, 상태 변경, 조회, 권한 검증을 모두 포함시키면
트랜잭션 경계가 모호해지고 캐시 self-invocation (같은 클래스 내부에서 메서드를 호출하면
Spring AOP 프록시를 거치지 않아 `@Cacheable` 등이 동작하지 않는 문제) 이 발생하기 때문입니다.

- `JobSubmissionService`: 쿼터 검사 → DB 저장 → Kubernetes 호출 → Outbox 기록
- `JobLifecycleService`: 워커 콜백 + 취소 처리, 상태 변경 후 캐시 evict (해당 키 캐시 삭제)
- `JobQueryService`: 조회 (`@Cacheable` — 결과를 캐시에 저장해 다음 호출에서 재사용)
- `JobAccessControl`: 소유자 / 관리자 권한 검증

레이어 의존 방향은 `api → application → domain` 과 `application → adapter` 단방향입니다.
Kubernetes 호출과 결과 URL 발급은 인터페이스 (`JobDispatcher`, `PresignedUrlProvider`) 로
분리되어 있어 Mock 으로 교체 가능합니다.

시간 의존성은 `Clock` 빈을 통해서만 주입됩니다 ([ADR-0007](docs/adr/0007-clock-injection-and-utc.md)).
`Instant.now()` 를 직접 부르지 않고 `Clock` 을 받아서 쓰면, 테스트에서 고정된 시각을
주입할 수 있어 timestamp 검증이 결정적이 됩니다.

## API

| Method | Path | 인증 | 동작 |
|--------|------|------|------|
| POST | `/api/v1/jobs` | JWT | Job 제출 |
| GET | `/api/v1/jobs/{id}` | JWT (소유자 / 관리자) | 단건 조회 (Redis 캐시) |
| GET | `/api/v1/jobs?owner=&status=&page=` | JWT | 목록 조회 (관리자만 다른 사용자 작업 조회 가능) |
| POST | `/api/v1/jobs/{id}/cancel` | JWT (소유자 / 관리자) | 취소 |
| GET | `/api/v1/jobs/{id}/result-url` | JWT (소유자 / 관리자) | 결과 URL 발급 |
| POST | `/internal/jobs/{id}/status` | 공유 시크릿 헤더 | 워커 콜백 |
| GET | `/swagger` | (public) | OpenAPI UI |
| GET | `/actuator/prometheus` | (public, NetworkPolicy 권장) | 메트릭 스크레이프 |
| GET | `/actuator/health/{liveness,readiness}` | (public) | K8s 프로브 |

## 실행 방법

```bash
# H2 + Mock K8s + 인증 비활성화로 기동
./gradlew bootRun

# Job 제출 (dev 환경에서는 사용자 값이 anonymous 로 처리됩니다)
curl -s -X POST http://localhost:8080/api/v1/jobs \
  -H 'Content-Type: application/json' \
  -d '{"inputUri":"s3://demo/in.bin","image":"gpu-worker:1.0","gpuCount":1,"priority":"NORMAL"}' \
  | tee /tmp/job.json | jq

JOB_ID=$(jq -r .id /tmp/job.json)

# 상태 조회
curl -s "http://localhost:8080/api/v1/jobs/$JOB_ID" | jq

# 워커 콜백 시뮬레이션 (RUNNING → SUCCEEDED)
curl -s -X POST "http://localhost:8080/internal/jobs/$JOB_ID/status" \
  -H 'X-GWP-Callback-Secret: dev-secret-change-me' \
  -H 'Content-Type: application/json' \
  -d '{"status":"SUCCEEDED","resultUri":"s3://demo/out.bin"}' | jq

# 결과 URL 조회
curl -s "http://localhost:8080/api/v1/jobs/$JOB_ID/result-url" | jq

# 메트릭 확인
curl -s http://localhost:8080/actuator/prometheus | grep gwp_orchestrator_jobs
```

Swagger UI: <http://localhost:8080/swagger>

## 빌드 / 테스트

```bash
./gradlew compileKotlin compileTestKotlin        # 컴파일 (운영 코드는 Kotlin)
./gradlew test --tests '*Test'                   # 단위 + 슬라이스 (Docker 불필요)
./gradlew test                                   # 전체. Docker 환경에서 Testcontainers IT 도 실행
./gradlew jacocoTestReport                        # JaCoCo 커버리지 리포트 (build/reports/jacoco/test/)
./gradlew bootJar                                # 실행 jar 생성
./gradlew bootBuildImage                         # Buildpack 으로 OCI 이미지 생성
```

`test` 는 `finalizedBy(jacocoTestReport)` 라 테스트가 끝나면 커버리지 리포트
(HTML `build/reports/jacoco/test/html/index.html`, XML / CSV 동봉) 가 자동 생성됩니다.
CI 가 CSV 를 읽어 루트 README 의 coverage badge (`badges/jacoco.svg`) 를 갱신합니다.

테스트 분포 (50개 테스트 클래스 = 단위 / 슬라이스 49개 + IT 1개, 약 272개 `@Test`, JUnit 5 기반)

핵심 도메인 / application 레이어:

| Suite | 검증 영역 |
|---|---|
| `JobTest`, `JobPreemptionTest`, `JobDependencyTest`, `DependencyGraphTest`, `PreemptionEvaluatorTest` | 상태 전이 규칙, preemption / dependency DAG / cycle 검출 |
| `JobSubmissionServiceTest` | 쿼터 거부, K8s 실패 시 FAILED 저장, Outbox 기록, traceId 저장 |
| `JobLifecycleServiceTest` | 콜백 처리, 미지원 콜백 거부, 종료된 Job 중복 콜백 무시, 취소 멱등 |
| `JobQueryServiceTest`, `JobAccessControlTest`, `QuotaServiceTest` | 조회 / 권한 / 쿼터 |
| `PreemptionServiceTest`, `DependencyResolutionServiceTest` | 우선순위 선점, DAG 진행 / 보강 스캔 |
| `CostAttributionServiceTest`, `CostQueryServiceTest`, `CostRateTest`, `CostRateProviderTest`, `JobCostRecordTest` | 단가 스냅샷 + chargeback ledger |

API / 어댑터 / 보안:

| Suite | 검증 영역 |
|---|---|
| `JobControllerTest`, `CallerTest` | 201 + Location, 400 검증 / 잘못된 JSON, 404, 403, 409 충돌 |
| `KubernetesLabelsTest`, `ResilientJobDispatcherTest`, `RetryableExceptionPredicateTest` | label injection 정화, 회로 차단 / 재시도 with jitter |
| `OwnerLogMaskTest`, `ImageLogMaskTest` | 로그 PII 마스킹 |

이벤트 / leader / lifecycle:

| Suite | 검증 영역 |
|---|---|
| `OutboxWriterTest`, `OutboxRelayTest`, `OutboxRelayLeaderGateTest`, `OutboxWriterBaggageTest`, `OutboxRelayBaggageHeaderTest` | JSON 직렬화, Kafka 발행 + 미발행 유지, leader 게이팅, traceparent + baggage 헤더 전파 |
| `LeaderElectorTest` | Lease holderIdentity 검증 |
| `JobLifecycleStateMachineTest`, `DomainStateMachineConsistencyTest`, `MermaidStateDiagramTest` | 사이드카 상태 머신과 도메인 메서드 합의, 다이어그램 ↔ 코드 정합성 |

관측 / contract:

| Suite | 검증 영역 |
|---|---|
| `JobMetricsTest`, `BaggagePopulatorTest`, `BaggageHandlerInterceptorTest`, `JobBaggageTest` | 메트릭, OTel Baggage 자동 전파 |
| `ApplicationReadinessCoordinatorTest`, `GracefulShutdownLifecycleTest` | 외부 의존성 회로 OPEN 시 unready, SIGTERM 후 in-flight 완료 |
| `AsyncApiSpecBuilderTest`, `AsyncApiSpecBaselineTest`, `EventCatalogConsistencyTest`, `ConsumerExpectationsContractTest`, `ContractVerifierTest` | AsyncAPI 자동 생성 + Pact-style consumer-driven contract |

DLQ 관리 콘솔 (ADR-0026):

| Suite | 검증 영역 |
|---|---|
| `InMemoryDlqMessageStoreTest`, `DlqAdminServiceTest`, `DlqBulkAdminServiceTest` | DLQ 조회 / 단건 replay·discard 멱등, bulk dry-run / confirm 게이트 |
| `AdminDlqControllerTest` | `/api/v1/admin/dlq/*` WebMvc 슬라이스 — 권한 (`ROLE_admin`), 필터, cursor pagination |

e2e:

| Suite | 검증 영역 |
|---|---|
| `JobLifecycleIT` | Postgres + Flyway + JPA e2e (submit → callback → result) |

## 운영 프로필 (`prod`)

`SPRING_PROFILES_ACTIVE=prod` 일 때 dev 와 달라지는 부분입니다.

| 영역 | dev | prod |
|---|---|---|
| DB | H2 인메모리 | PostgreSQL (env `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`) |
| Cache | 메모리 캐시 | Redis (env `REDIS_HOST`, `REDIS_PORT`) |
| Kafka | 비활성화 | env `KAFKA_BOOTSTRAP` |
| K8s 호출 | `MockJobDispatcher` | `KubernetesJobDispatcher` (in-cluster 인증) |
| Storage | `MockPresignedUrlProvider` | 현재 Mock 사용. 실제 S3 / MinIO 구현 추가 필요 |
| 인증 | `PermissiveSecurityConfig` (모두 허용) | `SecurityConfig` (JWT 필수, env `OAUTH_ISSUER_URI`) |
| Outbox 발행 | 비활성화 | 1초 주기, 100건씩 |
| 트레이스 샘플링 | 100% | 10% |

## 설계 결정 (ADR 요약)

전체 26건은 [docs/adr/README.md](docs/adr/README.md) 인덱스에서 확인하실 수 있습니다.
주요 묶음만 옮겨 적었습니다.

기반 (백엔드 / 인프라 골격):

| 결정 | 핵심 |
|---|---|
| [ADR-0001](docs/adr/0001-spring-mvc-not-webflux.md) Spring MVC vs WebFlux | 동시성은 K8s 수평 확장으로 해결. 트랜잭션 / 테스트 도구의 풍부함이 WebFlux backpressure 보다 가치 있다고 판단 |
| [ADR-0002](docs/adr/0002-fabric8-direct-not-spring-cloud-kubernetes.md) fabric8 직접 호출 | Kubernetes Job 생성 코드가 명확하고 Mock 구현을 두기 쉬움 |
| [ADR-0003](docs/adr/0003-callback-push-not-polling.md) 콜백 push | 워커가 상태를 직접 알려주고, 누락된 콜백은 timeout watcher 가 보완 |
| [ADR-0004](docs/adr/0004-outbox-not-direct-kafka.md) Outbox | DB 저장과 Kafka 발행 사이 불일치 방지 |
| [ADR-0005](docs/adr/0005-h2-default-postgres-prod.md) H2 dev / Postgres prod | 로컬 실행 부담 제거, PostgreSQL 차이는 Testcontainers 로 검증 |
| [ADR-0006](docs/adr/0006-domain-service-split-and-cache-self-invocation.md) 도메인 서비스 분리 | 제출 / 상태 변경 / 조회 / 권한 분리로 트랜잭션 / 캐시 적용 위치 명확화 |
| [ADR-0007](docs/adr/0007-clock-injection-and-utc.md) Clock 주입 + UTC | 시간 의존 로직 테스트 가능, DB / JVM timezone 차이 회피 |

플랫폼 / 운영:

| 결정 | 핵심 |
|---|---|
| [ADR-0008](docs/adr/0008-keda-event-driven-autoscaling.md) KEDA 이벤트 기반 확장 | Kafka lag 기반으로 워커 Pod 를 0부터 확장 |
| [ADR-0009](docs/adr/0009-supply-chain-security.md) 공급망 보안 | SBOM, 이미지 서명, admission policy 로 배포 전 검증 |
| [ADR-0010](docs/adr/0010-disaster-recovery-strategy.md) 재해 복구 전략 | Velero 백업과 복구 절차로 RTO/RPO 관리 |
| [ADR-0011](docs/adr/0011-chaos-engineering-practice.md) 장애 주입 실험 | Pod kill, 네트워크 지연, Kafka 격리 등 운영 가설 검증 |
| [ADR-0012](docs/adr/0012-image-promotion-pipeline.md) 이미지 승격 파이프라인 | dev → staging → prod 태그 승격과 승인 흐름 분리 |
| [ADR-0013](docs/adr/0013-platform-engineering-self-service.md) 플랫폼 셀프서비스 | Backstage + Crossplane 으로 GPU namespace 요청 표준화 |

도메인 보강 (priority / DAG / cost):

| 결정 | 핵심 |
|---|---|
| [ADR-0014](docs/adr/0014-job-priority-preemption.md) 우선순위 + preemption | HIGH 잡이 LOW 잡을 밀어내는 평가기 + 스케줄러 |
| [ADR-0015](docs/adr/0015-job-dependencies.md) Job DAG | parent 완료 시 child WAITING_DEPS → QUEUED 자동 진행 |
| [ADR-0016](docs/adr/0016-cost-attribution.md) Cost attribution | 종료 hook 5곳에서 종료 시점 단가 스냅샷 → ledger 누적 chargeback |

분산 / 관측 / contract:

| 결정 | 핵심 |
|---|---|
| [ADR-0017](docs/adr/0017-k8s-lease-leader-election.md) K8s Lease leader election | ShedLock + coordination.k8s.io/Lease 이중 게이트 |
| [ADR-0018](docs/adr/0018-otel-kafka-trace-propagation.md) W3C trace context Kafka 헤더 | outbox → Kafka header 로 traceparent 주입, consumer 자동 복원 |
| [ADR-0019](docs/adr/0019-prometheus-exemplars.md) Prometheus exemplars | 히스토그램 버킷에서 trace 한 번 클릭 jump |
| [ADR-0020](docs/adr/0020-asyncapi-and-consumer-driven-contract.md) AsyncAPI + consumer contract | 이벤트 catalog 자동 생성 + Pact-style 검증 |
| [ADR-0021](docs/adr/0021-otel-baggage-domain-context-propagation.md) OTel Baggage | owner / cost-center / priority 를 trace / log / metric 라벨로 자동 전파 |

라이프사이클 / 안정성:

| 결정 | 핵심 |
|---|---|
| [ADR-0022](docs/adr/0022-lifecycle-state-machine-sidecar.md) 상태 머신 사이드카 | 도메인 메서드는 그대로, 전이 표 + Mermaid 가 단일 출처 |
| [ADR-0023](docs/adr/0023-k8s-three-probes.md) liveness / readiness / startup 3종 probe | 의존성 회로 OPEN 시 unready 로 트래픽 차단 |
| [ADR-0024](docs/adr/0024-graceful-shutdown.md) Graceful shutdown | SIGTERM → unready → in-flight 완료 → 종료 |
| [ADR-0025](docs/adr/0025-retry-with-jitter-and-circuit-chain.md) Retry with jitter + 회로 chain | K8s 디스패처 재시도 + thundering herd 방지 |
| [ADR-0026](docs/adr/0026-dlq-admin-api.md) DLQ 관리 콘솔 API | 영구 실패 메시지의 조회 / replay / discard 를 audit + 멱등 + bulk 게이트와 함께 노출 |

DB 스키마, 인덱스, 동시성 처리는 [docs/database-design.md](docs/database-design.md) 를
참고해 주세요.

## 배포

```bash
kubectl apply -f k8s/serviceaccount.yaml   # gwp-jobs 네임스페이스에 batch/jobs RBAC
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/deployment.yaml       # 2 replicas + startup/liveness/readiness probe
kubectl apply -f k8s/service.yaml
```

Kubernetes 매니페스트와 CI/CD 예시는 참고용으로 포함되어 있습니다. 백엔드 구현부터 검토를
시작하실 경우 도메인 → API → DB → Outbox 순서가 권장됩니다.

## 향후 개선 사항

- [ ] S3 / MinIO `PresignedUrlProvider` 운영 구현 (현재 Mock. presigned URL = 일정 시간만
  유효한 다운로드 링크). 인터페이스는 이미 분리되어 있어 어댑터만 추가하면 됨
- [ ] 콜백 mTLS 전환 (현재는 공유 시크릿. mTLS = 클라이언트와 서버가 서로 인증서 검증)
- [ ] `Idempotency-Key` 헤더 처리 — 같은 요청이 두 번 와도 한 번만 처리되게 막는 헤더
  (네트워크 재시도 안전성)
- [ ] Job timeout watcher — RUNNING 이 expected duration 의 1.5 배를 넘으면 K8s Pod 상태와
  강제 동기화 (콜백 유실 보완책). ShedLock + Lease 인프라는 준비되어 있음

이미 적용된 항목 (참고): Resilience4j 서킷 브레이커 + Retry with jitter
([ADR-0025](docs/adr/0025-retry-with-jitter-and-circuit-chain.md)),
ShedLock + K8s Lease 이중 leader election ([ADR-0017](docs/adr/0017-k8s-lease-leader-election.md)),
PostgreSQL advisory lock 기반 owner 별 쿼터 직렬화 (commit `fff8351`).
