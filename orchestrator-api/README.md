# Orchestrator API

GPU Job 의 생성부터 완료까지 관리하는 Spring Boot REST API 입니다. 사용자 요청을 검증하여
작업을 저장하고, Kubernetes Job 으로 실행 요청한 뒤, 워커가 보내는 콜백으로 상태를
갱신합니다. 결과 다운로드 URL 발급은 `PresignedUrlProvider` 인터페이스로 분리되어 있어
dev 에서는 Mock 으로 동작하고 운영에서는 실제 구현으로 교체할 수 있습니다.

## 기술 스택

Spring Boot 3.3, Java 17, JPA + Flyway, Spring Security (OAuth2 + JWT), Redis, Kafka,
fabric8-kubernetes-client, Micrometer + OpenTelemetry.

운영 환경 설계 배경은 [`docs/platform-design.md`](../docs/platform-design.md) 에, 의사결정
기록은 [`docs/adr/`](docs/adr/) 에 정리되어 있습니다.

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
| `domain` | Job 상태 규칙, 제출 / 조회 / 취소 서비스, 쿼터, 권한 검증 |
| `adapter.kubernetes` | Kubernetes Job 생성 / 취소 어댑터, Mock 구현 포함 |
| `adapter.storage` | 결과 다운로드 URL 발급 인터페이스, Mock 구현 |
| `outbox` | DB 저장 이벤트의 Kafka 발행 |
| `observability` | 요청 ID 필터, 메트릭 기록 |
| `config` | 보안, 캐시, 시간, 스케줄 설정 |

도메인 서비스를 단일 클래스로 통합하지 않고 책임별로 분리한 이유는 [ADR-0006](docs/adr/0006-domain-service-split-and-cache-self-invocation.md)
에 정리되어 있습니다. 하나의 서비스에 제출, 상태 변경, 조회, 권한 검증을 모두 포함시키면
트랜잭션 경계가 모호해지고 캐시 self-invocation 문제가 발생하기 때문입니다.

- `JobSubmissionService`: 쿼터 검사 → DB 저장 → Kubernetes 호출 → Outbox 기록
- `JobLifecycleService`: 워커 콜백 + 취소 처리, 상태 변경 후 캐시 evict
- `JobQueryService`: 조회 (`@Cacheable`)
- `JobAccessControl`: 소유자 / 관리자 권한 검증

레이어 의존 방향은 `api → domain → adapter` 단방향입니다. Kubernetes 호출과 결과 URL 발급은
인터페이스로 분리되어 있어 Mock 으로 교체 가능합니다.

시간 의존성은 `Clock` 빈을 통해서만 주입됩니다 ([ADR-0007](docs/adr/0007-clock-injection-and-utc.md)).
테스트에서 고정된 시각을 주입하여 결정적인 검증이 가능하도록 한 구성입니다.

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
./gradlew compileJava compileTestJava           # 컴파일
./gradlew test --tests '*Test'                   # 단위 + 슬라이스 (Docker 불필요)
./gradlew test                                   # 전체. Docker 환경에서 Testcontainers IT 도 실행
./gradlew bootJar                                # 실행 jar 생성
./gradlew bootBuildImage                         # Buildpack 으로 OCI 이미지 생성
```

테스트 분포 (단위 / 슬라이스 40개 + IT 1개)

| Suite | 갯수 | 검증 영역 |
|---|---|---|
| `JobTest` | 7 | 상태 전이 규칙, IllegalJobTransitionException 포함 |
| `JobSubmissionServiceTest` | 4 | 쿼터 거부, K8s 실패 시 FAILED 저장, Outbox 기록, traceId 저장 |
| `JobLifecycleServiceTest` | 6 | 콜백 처리, 종료된 Job 의 중복 콜백 무시, 취소 멱등 |
| `JobQueryServiceTest` | 5 | 조회 성공 / 실패, 사용자 + 상태 필터, 결과 URL 발급 조건 |
| `JobAccessControlTest` | 5 | 소유자 / 관리자 접근, 다른 사용자 거부, 위임 |
| `QuotaServiceTest` | 4 | 기본 쿼터, 작업 수 초과, GPU 합계 초과, 사용자별 |
| `OutboxWriterTest` | 2 | JSON 직렬화 |
| `OutboxRelayTest` | 3 | Kafka 발행 성공, 실패 시 미발행 유지, 빈 배치 |
| `JobControllerTest` | 4 | 201 + Location, 400 검증 실패, 404, 403 |
| `JobLifecycleIT` | 1 | Postgres + Flyway + JPA e2e (submit → callback → result) |

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

상세는 [docs/adr/](docs/adr/) 의 7건을 참고해 주세요.

| 결정 | 핵심 |
|---|---|
| [ADR-0001](docs/adr/0001-spring-mvc-not-webflux.md) Spring MVC vs WebFlux | 동시성은 K8s 수평 확장으로 해결. 트랜잭션 / 테스트 도구의 풍부함이 WebFlux backpressure 보다 가치 있다고 판단 |
| [ADR-0002](docs/adr/0002-fabric8-direct-not-spring-cloud-kubernetes.md) fabric8 직접 호출 | Kubernetes Job 생성 코드가 명확하고 Mock 구현을 두기 쉬움 |
| [ADR-0003](docs/adr/0003-callback-push-not-polling.md) 콜백 방식 | 워커가 상태를 직접 알려주고, 누락된 콜백은 timeout 감시 작업으로 보완 예정 |
| [ADR-0004](docs/adr/0004-outbox-not-direct-kafka.md) Outbox | DB 저장과 Kafka 발행 사이 불일치 방지 |
| [ADR-0005](docs/adr/0005-h2-default-postgres-prod.md) H2 dev / Postgres prod | 로컬 실행 부담 제거, PostgreSQL 차이는 Testcontainers 로 검증 |
| [ADR-0006](docs/adr/0006-domain-service-split-and-cache-self-invocation.md) 도메인 서비스 분리 | 제출 / 상태 변경 / 조회 / 권한 분리로 트랜잭션 / 캐시 적용 위치 명확화 |
| [ADR-0007](docs/adr/0007-clock-injection-and-utc.md) Clock 주입 + UTC | 시간 의존 로직 테스트 가능, DB / JVM timezone 차이 회피 |

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

- [ ] 콜백 mTLS 전환 (현재는 공유 시크릿)
- [ ] S3 / MinIO `PresignedUrlProvider` 운영 구현 (현재 Mock)
- [ ] `Idempotency-Key` 헤더 처리 (네트워크 재시도 안전성)
- [ ] Resilience4j 서킷 브레이커 (K8s API / Kafka 장애 격리)
- [ ] Job timeout 감시 작업 (Spring Scheduling + ShedLock)
- [ ] PostgreSQL advisory lock 으로 쿼터 동시성 강화
