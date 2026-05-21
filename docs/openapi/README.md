# OpenAPI spec

`gpu-job-orchestrator` 의 REST API 를 OpenAPI 3 spec 으로 build-time export 한다.

## 무엇이 들어가나

- `gpu-job-orchestrator.yaml` — 빌드 시 생성되는 OpenAPI 3 문서. 외부 참조 / SDK codegen 의 단일 진실값.
  - GPU Job 제출 / 조회 / 취소
  - 쿼터 / 비용 조회
  - DLQ 관리 콘솔 백엔드 API (ADR-0026)

비동기 이벤트 계약은 `contract/` 패키지가 별도로 AsyncAPI spec 으로 export 한다 (OpenAPI 와 분리).

> 이 디렉토리의 `*.yaml` 은 CI 에서 생성·갱신된다. 로컬에서 수기로 편집하지 않는다.

## 생성 방법

`org.springdoc.openapi-gradle-plugin` 을 `orchestrator-api` 모듈에 적용했다.
Gradle 프로젝트가 `orchestrator-api/` 하위에 있으므로 spec 도 거기서 생성한다.
`generateOpenApiDocs` 태스크가 앱을 부팅한 뒤 `/v3/api-docs.yaml` 을 받아
repo 루트의 `docs/openapi/gpu-job-orchestrator.yaml` 로 저장한다.

```bash
cd orchestrator-api && ./gradlew generateOpenApiDocs
```

앱 부팅에 Postgres / Kafka / Redis 가 필요하므로, 의존 인프라를 먼저 띄워야 한다.
CI 에서는 service container 를 띄운 잡에서 위 태스크를 실행해 산출된 yaml 을
commit 하거나 아티팩트로 업로드한다.

## 보는 법

- Swagger UI — 앱 실행 후 `http://localhost:8080/swagger-ui.html`
- Redoc — `npx @redocly/cli preview-docs docs/openapi/gpu-job-orchestrator.yaml`
- 통합 뷰어 — profile repo `ssa1004/ssa1004` 의 `docs/api/index.html` (11 service spec 드롭다운)
