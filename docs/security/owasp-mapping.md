# OWASP API Security Top 10 (2023) 매핑

본 문서는 `orchestrator-api` (GPU Job Orchestrator) 의 외부 / 내부 API surface 를
[OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x00-header/)
의 10 개 항목으로 점검한 결과입니다. 각 항목별로 *해당 위협이 이 시스템에서 어떤 모양으로
나타나는가* → *현재 어떤 통제가 있는가* → *남는 위험 / 후속 작업* 순으로 정리합니다.

이 시스템에서 보호해야 할 핵심 자산은 다음과 같습니다.

- **Job 자체** — owner 식별 (JWT subject) 로 격리. 다른 owner 의 잡 조회 / 취소 / 결과
  다운로드 금지.
- **Cost ledger** — GPU 사용 / 빌링 정보. owner 본인 또는 admin 만 조회 가능.
- **K8s API 호출 권한** — `gwp-jobs` namespace 의 Job / Pod 만 생성 / 삭제. 다른 namespace
  로 권한 폭증 금지.
- **콜백 endpoint** — 워커 → orchestrator 의 상태 갱신 채널. 외부 인터넷에 노출 금지.

scope 외:

- 워커 컨테이너 (`worker/`) 내부 동작 — Go 코드는 SECURITY.md 의 보고 채널 대상이지만,
  본 매핑은 orchestrator-api 의 HTTP / RPC surface 만 다룹니다.
- 인프라 manifest (Kyverno / Falco / NetworkPolicy) — `infrastructure/security/` 가 별도
  레이어 통제. 본 문서는 API 코드에서 *어떻게 그 통제와 짝을 이루는가* 정도만 짚습니다.

각 항목 옆 ✦ 는 본 sweep 에서 추가로 발견되어 같은 PR 로 보강한 항목입니다.

---

## API1: Broken Object Level Authorization (BOLA)

다른 owner 의 Job ID 를 알게 되면 (예: 로그 누수 / UUID 추측 가능성은 낮지만 0 은 아님)
그 Job 의 상세 / cost / preemption history / 결과 다운로드 URL 까지 노출될 수 있는
시나리오.

**현재 통제**

- 컨트롤러 → [`JobAccessControl#getOwned`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/application/JobAccessControl.java)
  / `cancelOwned` / `resultUrlOwned` 단일 게이트에서 ownership 검증. JWT subject 기반으로
  `caller.owner()` 와 `job.getOwner()` 를 비교해 다르면 `AccessDeniedException` →
  HTTP 403.
- 영향 endpoint:
  - `GET /api/v1/jobs/{id}`
  - `POST /api/v1/jobs/{id}/cancel`
  - `GET /api/v1/jobs/{id}/result-url`
  - `GET /api/v1/cost/jobs/{jobId}` — `JobAccessControl.getOwned` 로 ownership 검증 후
    cost 조회
  - `GET /api/v1/jobs/{jobId}/preemption-history` — 같은 패턴
- admin (`ROLE_admin`) 만 ownership 우회 가능. admin 판정도 같은 `Caller` 한 곳에서.
- `GET /api/v1/jobs` (list) 는 `effectiveOwner = caller.isAdmin() && owner != null ? owner
  : caller.owner()` 로 비-admin 은 항상 자기 owner 로 fix — 쿼리 파라미터 `?owner=` 를
  넘겨도 무시.
- 외부에 노출되는 에러 메시지는 [`GlobalExceptionHandler`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/api/exception/GlobalExceptionHandler.java)
  에서 `"access denied"` 로 일반화 — Job 존재 여부 / 다른 owner 식별자가 노출되지 않음.

**검증**

[`JobAccessControlTest`](../../orchestrator-api/src/test/java/com/example/gwp/orchestrator/application/JobAccessControlTest.java)
가 owner 본인 / 타 owner / admin 세 케이스를 모두 검증.

**남는 위험**

- Job UUID 가 시간 순서로 추측 가능한 v1 이면 enumeration 위험이 커지지만, 도메인은
  `Job.submit` 에서 `UUID.randomUUID()` (v4) 를 사용 — 사실상 추측 불가.
- admin role 자체가 IdP 측에서 잘못 부여되는 경우는 OAuth/IdP 운영의 책임이라 코드
  레이어에서는 별도 보호 없음.

---

## API2: Broken Authentication

JWT 검증 / 익명 접근 / 토큰 sub 누락 시의 행동.

**현재 통제**

- 운영 ([`SecurityConfig`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/config/SecurityConfig.java))
  은 OAuth2 Resource Server + JWT — `issuer-uri` 에서 JWKS 자동 fetch, 표준 검증 (서명 /
  exp / iss). `application.yml` 의 `gwp.security.jwt.enabled=true` (prod 만) 일 때 활성.
- 로컬 dev 는 [`PermissiveSecurityConfig`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/config/PermissiveSecurityConfig.java)
  — 인증 없이 모든 요청 허용. `gwp.security.jwt.enabled=false` 또는 미설정. 운영 yml
  (`spring.config.activate.on-profile: prod`) 에서는 항상 `true` 로 set — 두 빈은
  `@ConditionalOnProperty` 로 상호 배타.
- [`Caller.from`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/api/Caller.java)
  이 JWT 의 `sub` 클레임을 owner 로 사용. `sub` 가 null / blank 면 즉시
  `AccessDeniedException` — Spring Security 검증을 통과했어도 식별 가능한 principal 이
  없으면 어떤 동작도 허용하지 않음. [`CallerTest`](../../orchestrator-api/src/test/java/com/example/gwp/orchestrator/api/CallerTest.java)
  가 이 path 검증.
- 세션 정책: `SessionCreationPolicy.STATELESS` — 매 요청마다 토큰 검증.

**남는 위험**

- Permissive 모드는 의도적으로 dev 전용. 운영 helm chart (`values-prod.yaml`) 는
  `gwp.security.jwt.enabled=true` + `OAUTH_ISSUER_URI` env 를 무조건 set 하도록 구성.
  실수로 prod 에 false 가 들어가면 모든 endpoint 가 인증 없이 열림 — 이는 SECURITY.md
  에서 in-scope 로 명시한 *위험한 default* 항목.
- JWT validation clock skew / replay 보호는 Spring Security default (60s allowance) 에
  맡김. 추가 보호 (jti 블랙리스트 등) 는 미구현.

---

## API3: Broken Object Property Authorization

`JobResponse` / `JobCostResponse` 가 다른 owner 의 민감 필드 (cost / image / inputUri /
errorMessage) 를 노출하는지.

**현재 통제**

- 모든 detail / cost / preemption-history endpoint 는 API1 의 ownership 게이트를 먼저
  통과한 뒤 응답을 만든다 — 즉 *다른 owner 의 row 자체* 가 응답으로 흘러나갈 수 없음.
- `GET /api/v1/cost/summary` / `GET /api/v1/cost/top-spenders` 는 admin 전용. 비-admin
  이 다른 owner 의 GPU 사용량 합계를 보지 못함. 그 두 endpoint 만 `caller.isAdmin()`
  체크를 명시적으로 둠 ([`CostController`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/api/CostController.java)).
- 로그 출력 시 owner 는 [`OwnerLogMask`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/application/OwnerLogMask.java)
  로 해시 마스킹, 이미지 reference 는 [`ImageLogMask`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/application/ImageLogMask.java)
  로 (자격증명 패턴 차단) 마스킹 — 응답이 아니라 *로그* 측에서도 다른 owner 식별자가
  새지 않음.

**남는 위험**

- `JobResponse` / `JobCostResponse` 는 admin 시점에 그대로 노출되는데, admin 은 정의상
  운영 신뢰 가능 actor 라 의도된 동작.
- DTO 가 도메인 entity 의 모든 필드를 그대로 직렬화 — 신규 필드 추가 시 *예상치 못한
  필드* 가 자동 노출될 risk. 현재는 record 가 필드 명시적이라 신규 추가가 visible 한 코드
  변경으로 들어옴 (review gate).

---

## API4: Unrestricted Resource Consumption ✦

GPU 스케줄러는 다른 도메인보다 자원 소비형 공격 표면이 넓다. 특히 (a) 한 잡에 대량의
parent 의존을 매달아 cycle 검사 그래프를 폭증시키기, (b) page 요청을 거대화해서 DB /
응답을 폭주시키기, (c) cost 시간 구간을 무한대로 보내기 — 세 axis.

**기존 통제**

- [`CostQueryService.MAX_RANGE`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/application/CostQueryService.java)
  = 366일. 더 큰 구간 요청은 400 — 호출자가 `Instant.EPOCH` 를 default 로 보내는
  사고 차단.
- `CostQueryService.MAX_TOP_N` = 100 — `?limit=` 무한대 차단.
- [`PreemptionController.MAX_RECENT_LIMIT`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/api/PreemptionController.java)
  = 200 — admin 의 `?limit=` 도 응답 크기 폭증 방지.
- `JobSubmissionRequest.image` 는 정규식으로 256자 + 형식 제한, `inputUri` 는 `s3://`
  scheme 강제.
- [`QuotaService`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/application/QuotaService.java)
  + Postgres advisory lock 으로 owner 단위 GPU / 동시 잡 over-commit 차단.

**본 sweep 에서 보강한 항목 ✦**

- `JobSubmissionRequest.parentJobIds` 에 `@Size(max = 16)` 추가. 예전엔 size 캡 없음 →
  악성 / 실수 클라이언트가 수천 개 parent UUID 를 보내면 [`JobSubmissionService#validateNoCycle`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/application/JobSubmissionService.java)
  의 BFS frontier 와 `findByChildJobIdIn` IN-쿼리 size 가 그에 비례해 폭주.
  ([`JobSubmissionRequest.java`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/api/dto/JobSubmissionRequest.java))
- `spring.data.web.pageable.max-page-size: 100` 을 `application.yml` 에 명시. 예전엔 Spring
  Data default (2000) — `GET /api/v1/jobs?size=10000` 같은 요청이 큰 page 를 만들어
  응답 직렬화 + DB 부하 폭증.

**남는 위험**

- API rate limit 자체는 코드 레이어가 아니라 ingress (nginx) / API gateway 책임으로 분리.
  현재 helm 의 nginx ingress 에는 별도 rate limit annotation 이 없어 운영에서 보강
  필요. (이 문서 scope 밖.)
- Quota 자체 (sequence 가 아닌 burst) 는 `defaultMaxConcurrentJobs` / `defaultMaxGpuCount`
  로 제한 — owner 단위 burst 는 막히지만 *여러 owner 가 동시 burst* 는 글로벌 quota 가
  따로 없음. 이건 capacity planning + KEDA scaling 의 영역.

---

## API5: Broken Function Level Authorization

admin 전용 동작 (전체 cost summary / top spender / 전체 preemption timeline) 을 일반
사용자가 호출할 수 있는가.

**현재 통제**

- admin 식별: `Caller.isAdmin()` — Spring Security `Authentication.getAuthorities()` 에서
  `ROLE_admin` 존재 여부. JWT 의 `realm_access.roles` (Keycloak 표준 위치) 에서
  `JwtAuthenticationConverter` 가 자동 매핑 ([`SecurityConfig#jwtAuthConverter`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/config/SecurityConfig.java)).
- admin-only endpoint 와 그 검증 위치:
  - `GET /api/v1/cost/summary` — `CostController` 안에서 `!caller.isAdmin()` → 403.
  - `GET /api/v1/cost/top-spenders` — 같은 패턴.
  - `GET /api/v1/preemption-history` — `PreemptionController` 안에서 직접 검증.
  - `GET /api/v1/jobs?owner=…` — `effectiveOwner` 계산이 비-admin 의 `?owner=` 를 자기
    owner 로 강제 덮어씀.
- preemption / leader election 자체는 *사용자 API* 가 아닌 *시스템 스케줄러*. HTTP 로
  노출 안 됨. PreemptionScheduler 는 `@Scheduled` 트리거 + LeaderElector gate. 같은 패턴
  으로 OutboxRelay / DependencyScanScheduler 도 leader 만 동작.
- Job cancel 은 owner 또는 admin — admin-only 가 아님. owner 가 자기 잡을 끄는 건 정상
  사용자 동작.

**남는 위험**

- 현재 컨트롤러 메서드 안에서 `caller.isAdmin()` 분기를 명시적으로 작성. `@PreAuthorize`
  같은 어노테이션 일관화는 후속. 빠질 위험이 있는 만큼, *admin-only* 검증이 누락된
  PR 은 code review 에서 잡혀야 한다.

---

## API6: Unrestricted Access to Sensitive Business Flows

자기 quota 우회 / 다른 owner 로 잡 제출 / 자기 cost 를 0 으로 위조 등.

**현재 통제**

- `JobSubmissionRequest` 는 owner 필드를 *받지 않음*. owner 는 `caller.owner()` (JWT
  subject) 만 반영 — request body 로 owner 를 spoofing 할 수 없다.
- Quota 검사는 `QuotaService.enforceForSubmission(spec.owner(), spec.gpuCount())` —
  spec.owner 는 위에서 caller.owner 로 set. admin 여부와 무관하게 같은 검사 통과 필요
  (admin 이라도 quota 우회 path 없음).
- Cost 는 `CostAttributionService.recordCost` 가 lifecycle hook 으로 자동 계산. 사용자
  API 에서 cost 값을 set 하는 endpoint 없음. UNIQUE(job_id) DB 제약 + 도메인 lifecycle
  invariant 가 이중 기록 / 누락 차단.

**남는 위험**

- admin role 부여 자체가 sensitive flow 의 master key. IdP 운영 측에서 admin 부여를
  audit log 로 추적해야 함. 코드 레이어에서는 별도 보호 없음.

---

## API7: Server Side Request Forgery (SSRF)

사용자 입력이 서버의 outbound HTTP / TCP 호출 destination 으로 흘러가는가.

**현재 통제**

- 워커 콜백 URL (`CALLBACK_URL` env) 은 **서버 측 config** (`gwp.kubernetes.callback-url`)
  에서 옴 — `application.yml` / helm values 에 fixed string 으로 하드코딩. 사용자 입력
  으로 override 되는 path 없음.
- presigned URL 도 storage adapter 가 자기 storage (S3 / MinIO) 에 대해 발급. 사용자
  입력 `inputUri` / `resultUri` 는 `s3://bucket/key` scheme 정규식으로 강제 (이번 sweep
  에서 `resultUri` 도 같은 패턴 추가, 아래 API10 참고).
- K8s API 호출 destination 은 fabric8 client 의 in-cluster config 또는 명시적 kubeconfig.
  사용자 입력으로 cluster target 을 바꾸는 path 없음.

**남는 위험**

- 미래에 워커가 사용자 제공 URL 로 다운로드하는 기능을 추가한다면 (예: `inputUri` 에
  HTTPS scheme 허용 등) SSRF 표면이 새로 열림. 그 시점에 별도 검토 필요. 현재는
  `s3://` 만 허용하므로 해당 없음.

---

## API8: Security Misconfiguration

K8s API token / CALLBACK_SECRET 평문 commit / RBAC 과다 / health endpoint 노출.

**현재 통제**

- `CALLBACK_SECRET` — [`KubernetesJobDispatcher#buildEnv`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/adapter/kubernetes/KubernetesJobDispatcher.java)
  가 워커 Pod env 에 주입할 때 `valueFrom.secretKeyRef` 로 `gwp-callback-secret` Secret
  을 참조. 평문 fallback (`CALLBACK_SECRET_FALLBACK`) 은 dev 편의용이며 운영에서는
  Secret 이 존재하므로 secretKeyRef 가 우선. helm chart 의 `callback.secret` default
  (`dev-secret-change-me`) 는 prod values 또는 ExternalSecrets / SealedSecrets 로 override
  하는 것이 의도된 사용.
- DB 자격증명 — `helm/.../templates/secret.yaml` 는 `existingSecret` 가 비어 있을 때만
  chart 가 직접 Secret 을 만든다. 운영에서는 ExternalSecrets / SealedSecrets 가 만든
  Secret 을 `existingSecret` 으로 바인딩 — 평문 values 가 git 에 박히지 않게.
- RBAC ([`helm/.../templates/role.yaml`](../../helm/gpu-job-orchestrator/templates/role.yaml))
  — Role 두 개로 분리: `gwp-jobs` namespace 의 batch/Job + pods (verb 화이트리스트), 자기
  namespace 의 coordination/Lease. 다른 namespace 권한 폭증 X.
- `/actuator/prometheus` 는 인증 우회 — 외부 노출은 [NetworkPolicy](../../helm/gpu-job-orchestrator/templates/networkpolicy.yaml)
  로 monitoring namespace 의 Prometheus Pod 만 ingress 허용.
- `/actuator/health/*` 는 인증 우회 — K8s liveness / readiness probe 요구사항.
- `/internal/*` 는 Spring Security 에서 permitAll 이지만 별도로 `X-GWP-Callback-Secret`
  헤더 검증 + ingress 차단 (nginx server-snippet `^/internal/` → 404) + NetworkPolicy
  (워커 namespace 만 ingress 허용) 으로 3중 방어.
- `X-GWP-Callback-Secret` 검증은 [`InternalCallbackController`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/api/InternalCallbackController.java)
  안에서 `MessageDigest.isEqual` 로 constant-time 비교 — timing-attack 면역.

**본 sweep 에서 보강한 항목 ✦**

- 이전 ingress paths 에 `/api/v1/dag`, `/api/v1/admin`, `/api/v1/callbacks` 가 prefix 로
  열려 있었으나 실제 컨트롤러는 존재하지 않음. 잘못된 noise 가 prod 외부에 노출됨.
  → 실제 구현된 경로 (`/api/v1/jobs`, `/api/v1/cost`, `/api/v1/preemption-history`) 만
  남기도록 [`values.yaml`](../../helm/gpu-job-orchestrator/values.yaml) /
  [`values-prod.yaml`](../../helm/gpu-job-orchestrator/values-prod.yaml) 정리. 자세한
  내용은 아래 API9.

**남는 위험**

- helm `callback.secret` default 가 평문 `dev-secret-change-me`. prod 미설정 사고
  방지를 위해 admission policy (Kyverno) 또는 helm validation 으로 default 차단을
  강제하는 것이 후속.

---

## API9: Improper Inventory Management ✦

배포된 endpoint 와 *문서 / 라우팅 설정* 의 어긋남.

**현재 통제**

- Real endpoints (controller `@RequestMapping`):

  | Controller | Path | Notes |
  | --- | --- | --- |
  | `JobController` | `/api/v1/jobs/**` | submit, get, list, cancel, result-url |
  | `CostController` | `/api/v1/cost/**` | jobs/{id}, owners/{owner}, summary, top-spenders |
  | `PreemptionController` | `/api/v1/jobs/{id}/preemption-history`, `/api/v1/preemption-history` | per-job + admin timeline |
  | `InternalCallbackController` | `/internal/jobs/{id}/status` | 워커 콜백 — `@Hidden` (OpenAPI 비노출) |

- OpenAPI 스펙은 `/v3/api-docs` (springdoc) — 컨트롤러에 박힌 어노테이션과 자동 동기화.
  Internal endpoint 는 `@Hidden` 로 명시적으로 제외 — public 스펙에 누수되지 않음.
- `/internal/*` 은 ingress 단에서 차단 (`server-snippet: location ~ ^/internal/ { return
  404; }`) + NetworkPolicy 로 워커 namespace 만 ingress 허용. 외부에 *어떤 응답 코드도*
  돌아오지 않는다.

**본 sweep 에서 보강한 항목 ✦**

- helm `values.yaml` / `values-prod.yaml` 의 `ingress.hosts.paths` 가 `/api/v1/dag`,
  `/api/v1/admin`, `/api/v1/callbacks` 같은 *비-존재 path* 를 prefix 로 등록 + 실제 구현된
  `/api/v1/cost`, `/api/v1/preemption-history` 가 빠져 있었다. 결과:
  - 비-존재 path 는 ingress 룰만 있고 backend 가 404 — 외부에서 보면 마치 path 가 있을
    것처럼 노이즈. 보안 결함은 아니지만 attack surface 의 *문서가 잘못된* 상태.
  - 진짜 cost / preemption-history endpoint 는 ingress 화이트리스트에서 누락되어 prod
    배포 시 외부에서 도달 불가능 — *기능 자체가 안 보이는* 운영 사고.
  → 실제 컨트롤러 path 와 1:1 맞도록 정정. ingress.yaml 의 주석도 동기화.
- ADR `0010-disaster-recovery-strategy.md` 외 ADR 신규 추가 없음 (제약 — *ADR 신규 X*)
  — 인벤토리 매핑은 이 문서가 단일 source.

**남는 위험**

- OpenAPI 스펙은 자동 생성이지만 *외부 공개 환경* 에서 SwaggerUI (`/swagger`) 를 노출
  하면 attack surface 가 그대로 드러남. helm `values-prod.yaml` 의 ingress paths 에
  `/swagger` 가 빠져 있어 외부 도달 불가 — 의도된 기본값.

---

## API10: Unsafe Consumption of APIs ✦

워커 콜백 payload 를 그대로 신뢰하는가. (워커 = 우리가 띄운 컨테이너지만, 손상 / 적대적
이미지 가능성을 가정.)

**현재 통제**

- 1차 인증: `X-GWP-Callback-Secret` 헤더 — `MessageDigest.isEqual` constant-time 비교.
  Secret 미일치 시 401.
- 2차 NetworkPolicy: helm `networkpolicy.yaml` 가 워커 namespace (`gwp-jobs`) 의 Pod 만
  `:8080` 으로 ingress 허용. 외부에서 직접 콜백 위조 불가능.
- 3차 ingress: nginx server-snippet 으로 `^/internal/` 은 404 — 외부 인터넷에서 워커
  콜백 path 가 보이지 않음.
- payload 검증: [`StatusCallbackRequest`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/api/dto/StatusCallbackRequest.java)
  는 `@NotNull JobStatus status`, `@Size(max=1024) String resultUri`,
  `@Size(max=2048) String errorMessage` 로 길이 / 타입 한계 명시.
- lifecycle: [`JobLifecycleService#updateStatusFromCallback`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/application/JobLifecycleService.java)
  는 terminal 잡에 대한 콜백을 즉시 ignore. 비정상 status (RUNNING / SUCCEEDED /
  FAILED 외) 는 `IllegalArgumentException` → 400. transition table 사이드카가
  허용된 전이만 통과시킴 (ADR-0022).

**본 sweep 에서 보강한 항목 ✦**

- `StatusCallbackRequest.resultUri` 에 `^$|^s3://[a-z0-9.\-]+/.+` 패턴 추가. 예전엔
  길이 상한만 있어 워커가 `https://attacker.example/x`, `file:///...`, `gopher://...`
  같은 임의 URL 을 박을 수 있었음. 결과 다운로드 흐름에서 `PresignedUrlProvider` 가
  비-S3 입력을 받게 되면 향후 S3 presigner 구현에서 예상치 못한 동작이 발생할 수 있어
  trust boundary 양쪽 ([`JobSubmissionRequest.inputUri`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/api/dto/JobSubmissionRequest.java)
  와 동일한 패턴) 에서 같은 검증을 적용 — defense-in-depth.

**남는 위험 / 후속 안건**

- 현재 콜백은 *shared secret* — 워커가 secret 을 보유하므로 자기 jobId 가 아닌 *다른*
  jobId 로도 status 콜백을 발사 가능 (시스템 모델상 워커 ↔ Job 의 1:1 바인딩이 K8s
  pod label 로만 표현되고, 콜백 본문이 jobId 와 secret 만으로 검증).
- 더 강한 모델은 jobId-bound HMAC: orchestrator 가 dispatch 시 `HMAC(secret, jobId +
  nonce)` 를 워커별 토큰으로 발급, 콜백 헤더에 동봉. 워커가 다른 jobId 의 콜백을
  발사하면 검증 실패. 트레이드오프: orchestrator 가 콜백 본문의 jobId 와 토큰의 jobId
  를 일치 검사 필요 → 추가 한 줄 코드. 본 sweep 에서는 *새 기능 X* 제약으로 미구현 —
  후속 ADR 후보.
- replay 보호 (nonce / timestamp / single-use) 도 같은 결의 후속. 현재는 lifecycle
  invariant (terminal 잡 콜백 ignore) 가 *부분* 방어선 역할.

---

## 본 sweep 변경 요약

| 항목 | 위치 | 변경 |
| --- | --- | --- |
| API4 | [`JobSubmissionRequest.java`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/api/dto/JobSubmissionRequest.java) | `parentJobIds` 에 `@Size(max=16)` |
| API4 | [`application.yml`](../../orchestrator-api/src/main/resources/application.yml) | `spring.data.web.pageable.max-page-size: 100` |
| API10 | [`StatusCallbackRequest.java`](../../orchestrator-api/src/main/java/com/example/gwp/orchestrator/api/dto/StatusCallbackRequest.java) | `resultUri` 에 `s3://` 패턴 강제 |
| API9 | [`values.yaml`](../../helm/gpu-job-orchestrator/values.yaml) / [`values-prod.yaml`](../../helm/gpu-job-orchestrator/values-prod.yaml) | ingress paths 를 실제 컨트롤러 경로 (jobs / cost / preemption-history) 와 1:1 정렬, 비-존재 path 제거 |

본 매핑은 보안 점검을 *시점 스냅샷* 으로 남기는 것이 목적이며, 운영 SLA 가 아닌 학습 /
포트폴리오 자료입니다. 외부 제보 채널은 [SECURITY.md](../../SECURITY.md) 참고.
