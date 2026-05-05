# Contributing

본 저장소는 백엔드 (`orchestrator-api/`) 와 인프라 (`infrastructure/`) 가 함께 포함되어
있어 변경 종류가 다양합니다. 이에 맞춘 commit / branch 규칙을 정리한 문서입니다.

## 브랜치 전략

GitHub Flow 기반의 단순한 모델을 사용합니다.

```
main (protected)
  ├── feature/job-quota-by-priority
  ├── fix/optimistic-lock-retry
  ├── chore/terraform-monitoring-helm-version
  └── docs/runbook-add-callback-missing
```

`main` 에서 분기 → 작업 → PR → CI 통과 → Squash and merge → feature 브랜치 삭제의 순서를
따르며, `main` 은 항상 배포 가능한 상태로 유지됩니다.

## Commit 메시지

Conventional Commits 형식을 따릅니다.

```
<type>(<scope>): <짧은 설명, 50자 이내>

<상세 설명, 한 줄에 72자 이내>
- 무엇이 / 왜 변경되었는지
- 영향받는 영역
```

type 은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf` 중 하나입니다.
scope 는 변경 영역을 나타냅니다. 백엔드 변경의 경우 `domain`, `api`, `outbox`,
`observability` 등 패키지명이, 인프라 변경의 경우 `infra`, `terraform`, `ansible`,
`argocd`, `ci` 등이 사용됩니다.

### 예시

백엔드 변경:

```
feat(domain): JobLifecycleService 의 워커 콜백 처리 추가

- RUNNING / SUCCEEDED / FAILED 상태 전이 검증
- 이미 종료된 Job 의 중복 콜백은 무시 (멱등성)
- 상태 변경 시 조회 캐시 evict
```

```
fix(outbox): Kafka 발행 실패 시 published_at 가 채워지던 버그

OutboxRelay 가 send 결과 확인 없이 markPublished 를 호출하던 문제입니다.
KafkaTemplate.send().get() 으로 동기 대기 후 성공한 row 만 published 로
표시하도록 수정했습니다. 실패 row 는 다음 polling 에서 자동 재시도됩니다.
```

인프라 / 관측 변경:

```
feat(observability): 외부 PG 호출 latency p95 알림 추가

- PrometheusRule 에 PgAuthorizeLatencyHigh (p95 > 500ms, 5m) 추가
- 대응 runbook (docs/runbooks/pg-latency.md) 동시 작성
- Grafana 대시보드의 latency 패널은 변경 없음 (이미 p50/p95/p99 표시)
```

## Commit 단위

한 commit 은 하나의 변경을 담는 것을 원칙으로 합니다. 백엔드 코드 변경과 Terraform 변경이
같은 commit 에 포함되어 있다면 거의 항상 두 개로 분리해야 합니다 (리뷰어가 다를 가능성도
높습니다).

PR 머지 전에 WIP commit 들은 squash 하고, fixup commit 은 `git rebase -i` 로 정리합니다.

## 테스트

PR 전 통과가 필요한 항목입니다.

```bash
cd orchestrator-api
./gradlew test --tests '*Test'                 # 단위 + 슬라이스 (Docker 불필요)
./gradlew test                                 # 전체. Docker 환경에서 Testcontainers IT 도 실행
./gradlew bootJar                              # 실행 jar 생성
```

Terraform 변경이 포함된 PR 의 경우 `terraform fmt -check` 와 `terraform validate` 를 함께
실행합니다. Ansible 변경의 경우 `ansible-lint` 가 필요합니다. CI 에서 자동 실행되지만
로컬에서 미리 확인하는 것이 효율적입니다.

## 코드 스타일

- **Java**: Google Java Format 또는 IntelliJ default
- **YAML / Terraform / Markdown**: 들여쓰기 통일 (Terraform 2-space, k8s manifest 2-space)
- **주석**: 자연스러운 한국어. 영어 직역체 지양. 기술 명사는 영어 그대로 사용

## ADR

설계 결정을 변경하는 작업의 경우 [`orchestrator-api/docs/adr/`](orchestrator-api/docs/adr/)
에 새 ADR 을 추가합니다. 기존 ADR 을 무효화하는 경우 해당 ADR 의 status 를 Superseded 로
변경하고 새 ADR 에서 link 합니다.
