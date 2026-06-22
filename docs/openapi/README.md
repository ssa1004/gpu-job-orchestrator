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

앱을 기본(dev) 프로파일로 띄우면 H2 + Mock K8s 로 외부 인프라 없이 부팅되므로,
Postgres / Kafka / Redis 없이도 spec 을 생성할 수 있다. 앱을 임의의 포트로 띄운 뒤
`/v3/api-docs.yaml` 을 받아 `docs/openapi/gpu-job-orchestrator.yaml` 로 저장한다.

```bash
cd orchestrator-api
./gradlew bootRun --args='--server.port=18080' &
# health 가 아니라 spec endpoint 로 폴링 (Redis health 는 dev 에서 DOWN 이라 무관)
until curl -fsS http://localhost:18080/v3/api-docs.yaml -o /dev/null; do sleep 2; done
curl -fsS http://localhost:18080/v3/api-docs.yaml -o ../docs/openapi/gpu-job-orchestrator.yaml
kill %1
```

CI(`ci.yml`)의 `openapi-spec` 잡이 위와 동일한 zero-infra 부팅으로 spec 을 재생성한 뒤
`git diff --exit-code docs/openapi/gpu-job-orchestrator.yaml` 로 drift 를 검사한다.
코드와 spec 이 어긋나면 CI 가 실패한다. `server.port` 는 18080 으로 고정해
`servers[].url` 까지 결정적으로 일치시킨다.

## 보는 법

- Swagger UI — 앱 실행 후 `http://localhost:8080/swagger-ui.html`
- Redoc — `npx @redocly/cli preview-docs docs/openapi/gpu-job-orchestrator.yaml`
- 통합 뷰어 — profile repo `ssa1004/ssa1004` 의 `docs/api/index.html` (11 service spec 드롭다운)
