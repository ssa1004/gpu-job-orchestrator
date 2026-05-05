# 운영 설계 요약

이 문서는 [orchestrator-api](../orchestrator-api/) 를 운영할 때 백엔드 설계에 영향을 주는 부분만 정리합니다. Terraform, Ansible, ArgoCD 코드는 운영 환경을 설명하기 위한 참고 구현이며, 이 repo 의 중심은 Spring Boot API입니다.

---

## 운영 요구사항

GPU Job API는 일반적인 CRUD API와 다르게 다음 조건을 고려해야 합니다.

| 요구사항 | 백엔드 설계에 반영한 내용 |
|---|---|
| 작업 실행 시간이 길다 | 작업 상태를 DB에 저장하고, 워커 콜백으로 상태를 갱신 |
| 중간에 서버가 재시작될 수 있다 | Job ID 기준으로 상태를 다시 조회할 수 있게 설계 |
| 같은 사용자가 GPU를 과도하게 점유할 수 있다 | 사용자별 동시 실행 작업 수와 GPU 합계를 제한 |
| 결과 파일이 크다 | API 서버가 파일을 직접 전달하지 않고 다운로드 URL만 발급 |
| 상태 변경을 다른 시스템에 알려야 한다 | DB 트랜잭션 안에서 Outbox 이벤트를 먼저 저장한 뒤 Kafka로 발행 |
| 장애 원인을 빠르게 찾아야 한다 | 요청 ID, 트레이스 ID, Job ID를 로그와 메트릭에 함께 남김 |

---

## 백엔드 흐름

```text
Client
  │
  │ POST /api/v1/jobs
  ▼
Orchestrator API
  ├─ 요청 검증
  ├─ 사용자별 쿼터 검사
  ├─ Job 저장
  ├─ Kubernetes Job 생성 요청
  └─ Outbox 이벤트 저장
      │
      ▼
Worker Pod
  │
  │ POST /internal/jobs/{id}/status
  ▼
Orchestrator API
  ├─ 상태 전이 검증
  ├─ Job 상태 갱신
  ├─ 조회 캐시 삭제
  └─ 완료 이벤트 저장
```

핵심은 작업 실행 자체를 API 서버가 오래 붙잡고 있지 않는 것입니다. API 서버는 작업을 등록하고 상태를 관리하며, 실제 실행은 Kubernetes Job과 워커가 담당합니다.

---

## 환경별 차이

환경별 인프라를 깊게 설명하지 않고, 백엔드 입장에서 달라지는 지점만 남겼습니다.

| 구분 | 백엔드에서 달라지는 점 |
|---|---|
| 로컬 개발 | H2 DB, Mock Kubernetes, Mock 결과 URL 생성기 사용 |
| 클라우드 | PostgreSQL, Redis, Kafka, Kubernetes in-cluster 인증 사용 |
| 온프레미스 | Kubernetes 배포 방식과 스토리지 구현만 다르고 API 코드는 동일 |
| 하이브리드 | 여러 클러스터를 쓰더라도 Job 상태의 기준은 Orchestrator API와 DB |

즉, 환경이 달라져도 API의 책임은 바뀌지 않습니다. 달라지는 부분은 DB, 캐시, 메시지 브로커, Kubernetes 연결 설정입니다.

---

## 장애 대응 관점

### 워커 콜백 유실

워커가 상태 콜백을 보내지 못하면 DB에는 오래된 상태가 남을 수 있습니다. 현재 구조에서는 콜백을 기준으로 상태를 갱신하고, 다음 단계로 timeout 감시 작업을 추가할 계획입니다.

### Kafka 발행 실패

Kafka에 직접 발행하지 않고 Outbox 테이블에 먼저 저장합니다. Kafka 발행이 실패해도 DB에는 발행할 이벤트가 남아 있어 재시도할 수 있습니다.

### Kubernetes API 장애

Kubernetes Job 생성 요청이 실패하면 Job을 `FAILED` 상태로 기록합니다. 사용자는 같은 Job ID로 실패 원인을 조회할 수 있습니다.

### 결과 파일 전달

API 서버가 대용량 파일을 직접 내려주지 않습니다. 결과 파일 경로가 있는 경우 `PresignedUrlProvider`를 통해 다운로드 URL을 발급합니다. 현재는 Mock 구현이며, 실제 S3/MinIO 구현은 다음 작업입니다.

---

## 코드와 연결되는 지점

| 운영 요구 | 관련 코드 |
|---|---|
| Job 상태 관리 | [`Job`](../orchestrator-api/src/main/java/com/example/gwp/orchestrator/domain/Job.java), [`JobLifecycleService`](../orchestrator-api/src/main/java/com/example/gwp/orchestrator/domain/JobLifecycleService.java) |
| 사용자별 쿼터 | [`QuotaService`](../orchestrator-api/src/main/java/com/example/gwp/orchestrator/domain/QuotaService.java), [`JobRepository`](../orchestrator-api/src/main/java/com/example/gwp/orchestrator/domain/JobRepository.java) |
| Kubernetes 실행 요청 | [`JobDispatcher`](../orchestrator-api/src/main/java/com/example/gwp/orchestrator/adapter/kubernetes/JobDispatcher.java), [`KubernetesJobDispatcher`](../orchestrator-api/src/main/java/com/example/gwp/orchestrator/adapter/kubernetes/KubernetesJobDispatcher.java) |
| 결과 URL 발급 | [`PresignedUrlProvider`](../orchestrator-api/src/main/java/com/example/gwp/orchestrator/adapter/storage/PresignedUrlProvider.java) |
| 이벤트 발행 안정성 | [`OutboxWriter`](../orchestrator-api/src/main/java/com/example/gwp/orchestrator/outbox/OutboxWriter.java), [`OutboxRelay`](../orchestrator-api/src/main/java/com/example/gwp/orchestrator/outbox/OutboxRelay.java) |
| 로그 추적 | [`CorrelationIdFilter`](../orchestrator-api/src/main/java/com/example/gwp/orchestrator/observability/CorrelationIdFilter.java), [`JobMetrics`](../orchestrator-api/src/main/java/com/example/gwp/orchestrator/observability/JobMetrics.java) |

---

## 참고

인프라 코드는 [infrastructure](../infrastructure/) 아래에 별도로 정리했습니다. 코드 검토 순서는 이 문서보다 [orchestrator-api](../orchestrator-api/) 의 도메인/API/Outbox 와 [ADR](../orchestrator-api/docs/adr/) 을 먼저 보는 편이 적합합니다.
