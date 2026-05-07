# ADR-0013: Platform Engineering — Backstage + Crossplane 으로 self-service

## 상태
적용

## 배경

플랫폼 팀의 시간이 ticket 처리 (namespace 만들어주세요 / RBAC — Role-Based Access
Control, 어떤 사용자 / SA 가 어떤 K8s API 를 호출할 수 있는지 정의 — 추가해주세요 /
쿼터 늘려주세요) 에 30%+ 소비. 개발팀도 ticket 응답 대기로 작업 중단. ticket 의 90%
가 같은 패턴 (GPU namespace + 쿼터 + 팀 RBAC).

대안:
- **티켓 그대로** — scaling 안 됨
- **wiki 문서로 셀프 서비스** — 매뉴얼 따라가다 실수 (보안 정책 누락 등)
- **kubectl 권한 위임** — 보안 위험 (실수로 prod 영향)
- **IDP (Internal Developer Platform — 사내 개발자가 직접 인프라를 신청·생성할 수 있는
  포털) 구축** — 본 ADR 의 결정

## 결정

**Backstage** (Spotify 가 만든 오픈소스 개발자 포털) **+ Crossplane** (K8s 매니페스트로
클라우드 인프라까지 선언적으로 관리하는 도구) 조합.

```
[ 개발팀 ]
   │  Backstage portal 의 form 작성
   ▼
[ Backstage scaffolder ]
   │  GitOps repo 에 PR 생성
   ▼
[ 플랫폼 검토 + merge ]
   ▼
[ ArgoCD ]
   │  Crossplane Claim sync
   ▼
[ Crossplane ]
   namespace + ResourceQuota + LimitRange + RoleBinding + IRSA Role 생성
```

### Backstage 의 역할

- 개발팀 진입점 — 사용자 경험 통일 (validation, autocompletion, audit log — 누가 언제
  무엇을 신청했는지 기록)
- Software Template (정형화된 신청서 양식 — 입력값과 처리 절차가 미리 정의됨) 으로 정형화 —
  `gpu-namespace`, `new-microservice`, `data-pipeline` 등
- GitOps repo PR 자동 생성 — 머지 전 플랫폼 팀 검토 단계 보장

### Crossplane 의 역할

- "GpuNamespace" 같은 도메인-친화적 abstraction (XRD = Composite Resource Definition,
  사내 개발자가 다루기 쉬운 추상 리소스 타입을 정의)
- 1 Claim (XRD 의 인스턴스 하나, 사용자가 "GpuNamespace 하나 주세요" 라고 요청한 매니페스트)
  → 5~10 K8s 리소스 자동 생성 (Composition — XRD 가 어떤 실제 리소스로 펼쳐지는지 규칙)
- ProviderConfig (클러스터 / 클라우드 자격 증명 묶음) 로 클러스터 / 클라우드 모두 추상화
  (multi-cloud 가능)
- 변경 추적 가능 (Crossplane resource 도 K8s API 로 관리)

### 대안 검토

- **Helm chart + 수동 설치** — 사용자가 helm CLI (K8s 패키지 매니저) 알아야 함, GitOps
  일관성 깨짐
- **Terraform** — 상태 관리 분리 (state file 을 별도 저장소에 두어야 함), Crossplane
  처럼 K8s API 와 통합 안 됨
- **Argo Workflows** — workflow 자체엔 좋으나 abstraction 표현이 약함
- **Cluster API + ClusterClass** — 클러스터 단위만 (namespace 단위는 부적합)

## 결과

- 플랫폼 팀 ticket 부담 30%+ 감소 (예상)
- 개발팀 자가 진단 가능 — Backstage 화면에서 실시간 status
- 매니페스트 일관성 — Crossplane Composition 이 표준 적용
- 변경 audit log — GitOps PR / merge 로 추적
- (단점) 초기 구축 비용 — Backstage / Crossplane 학습 곡선
- (단점) Backstage 자체 운영 부담 — DB / 인증 / 플러그인 관리
- (단점) 추상화 누수 (abstraction leak) — 복잡한 요구사항 (예: 특정 IAM 정책) 은
  추상화로 다 못 가리고 결국 ticket 으로

## 진행 단계

1. **Pilot** — 한 팀 먼저 Backstage + GpuNamespace claim 사용
2. **자주 신청되는 패턴 추가** — new-microservice template, data-pipeline template
3. **observability 통합** — Backstage 에 Grafana / SLO / alert 직접 표시
4. **TechDocs** (Backstage 의 문서 플러그인 — 마크다운을 자동으로 색인 / 검색 가능) —
   ADR 들이 Backstage 에 자동 색인되어 검색 가능
