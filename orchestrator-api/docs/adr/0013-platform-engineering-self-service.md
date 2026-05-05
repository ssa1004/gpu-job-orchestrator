# ADR-0013: Platform Engineering — Backstage + Crossplane 으로 self-service

## 상태
적용

## 배경

플랫폼 팀의 시간이 ticket 처리 (namespace 만들어주세요 / RBAC 추가해주세요 / 쿼터 늘려
주세요) 에 30%+ 소비. 개발팀도 ticket 응답 대기로 작업 중단. ticket 의 90% 가 같은
패턴 (GPU namespace + 쿼터 + 팀 RBAC).

대안:
- **티켓 그대로** — scaling 안 됨
- **wiki 문서로 셀프 서비스** — 매뉴얼 따라가다 실수 (보안 정책 누락 등)
- **kubectl 권한 위임** — 보안 위험 (실수로 prod 영향)
- **IDP (Internal Developer Platform) 구축** — 본 ADR 의 결정

## 결정

**Backstage + Crossplane** 조합.

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

- 개발팀 진입점 — 사용자 경험 통일 (validation, autocompletion, audit log)
- Software Template 으로 정형화 — `gpu-namespace`, `new-microservice`, `data-pipeline` 등
- GitOps repo PR 자동 생성 — 머지 전 플랫폼 팀 검토 단계 보장

### Crossplane 의 역할

- "GpuNamespace" 같은 도메인-친화적 abstraction (XRD)
- 1 Claim → 5~10 K8s 리소스 자동 생성 (Composition)
- ProviderConfig 로 클러스터 / 클라우드 모두 추상화 (multi-cloud 가능)
- 변경 추적 가능 (Crossplane resource 도 K8s API 로 관리)

### 대안 검토

- **Helm chart + 수동 설치** — 사용자가 helm CLI 알아야 함, GitOps 일관성 깨짐
- **Terraform** — 상태 관리 분리 (state file), Crossplane 처럼 K8s 통합 안 됨
- **Argo Workflows** — workflow 자체엔 좋으나 abstraction 표현이 약함
- **Cluster API + ClusterClass** — 클러스터 단위만 (namespace 단위는 부적합)

## 결과

- 플랫폼 팀 ticket 부담 30%+ 감소 (예상)
- 개발팀 자가 진단 가능 — Backstage 화면에서 실시간 status
- 매니페스트 일관성 — Crossplane Composition 이 표준 적용
- 변경 audit log — GitOps PR / merge 로 추적
- (단점) 초기 구축 비용 — Backstage / Crossplane 학습 곡선
- (단점) Backstage 자체 운영 부담 — DB / 인증 / plugin 관리
- (단점) abstraction leak — 복잡한 요구사항 (예: 특정 IAM 정책) 은 결국 ticket 으로

## 진행 단계

1. **Pilot** — 한 팀 먼저 Backstage + GpuNamespace claim 사용
2. **자주 신청되는 패턴 추가** — new-microservice template, data-pipeline template
3. **observability 통합** — Backstage 에 Grafana / SLO / alert 직접 표시
4. **TechDocs** — ADR 들이 Backstage 에 자동 색인되어 검색 가능
