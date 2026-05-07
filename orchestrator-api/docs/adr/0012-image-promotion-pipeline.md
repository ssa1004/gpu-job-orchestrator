# ADR-0012: Image Promotion Pipeline (dev → staging → prod)

## 상태
적용

## 배경

ArgoCD GitOps (Git 저장소를 진실로 보고 K8s 클러스터 상태를 거기에 맞추는 방식) 가
적용되어 있지만 "어느 환경에 어떤 image tag 가 배포되어 있는가" 의 관리가 모호. 단순 case:

- CI 가 main push 시 image build + push → 같은 tag 가 모든 환경에 적용 → 위험
- 또는 CI 가 임의로 prod 까지 자동 배포 → governance (변경 검토·승인 절차) 부재

## 결정

ArgoCD `ApplicationSet` (여러 ArgoCD Application 을 한꺼번에 선언적으로 만드는 리소스)
으로 환경 list (dev / staging / prod) 를 선언하고, 각 환경의 image tag 는 **별도 GitOps
repo 의 overlay 폴더** 에서 kustomize (환경별 차이를 patch 로 표현하는 K8s 매니페스트
구성 도구) 로 관리. 환경 간 promotion (낮은 환경에서 검증된 이미지를 높은 환경으로
승격) 은 CI 가 명시적으로 GitOps repo 의 다음 환경 overlay tag 를 갱신.

```
 main push
    ↓
 CI: image build + push + Cosign sign
    ↓
 CI: GitOps/overlays/dev/orchestrator-api/kustomization.yaml 의 image tag 갱신
    ↓
 ArgoCD: dev 클러스터에 sync (자동)
    ↓
 dev e2e + smoke test 통과
    ↓
 CI: GitOps/overlays/staging/... 갱신
    ↓
 ArgoCD: staging sync
    ↓
 (수동 승인)
    ↓
 CI: GitOps/overlays/prod/... 갱신
    ↓
 ArgoCD: prod sync (Argo Rollouts canary)
```

### 핵심 설계

1. **단일 image, 여러 tag** — 한 번 빌드된 image 가 dev / staging / prod 에 동일하게 사용.
   환경 간 차이는 ConfigMap / Secret / replica 수만.
2. **GitOps repo 가 진실** — ArgoCD 는 GitOps repo 만 보고, image tag 는 commit log 로
   추적 가능.
3. **prod 는 수동 승인** — `autoSync: false` (자동 동기화 끔) + `requiresApproval: "true"`
   annotation. ArgoCD UI 에서 운영자가 직접 sync 버튼을 눌러야 배포.
4. **dev 는 PR 별 preview 가능** — ApplicationSet 의 Pull Request generator 추가하면 PR
   마다 namespace 띄움 (PR 미리보기 환경, cost 증가 주의).

### 대안 검토

- **Argo Image Updater** — 이미지 레지스트리를 직접 polling 해서 자동 업데이트. dev
  환경에는 쓰지만 prod 까지 자동 적용은 governance 부재. 본 결정에서는 dev 만 사용
- **Flux Image Automation** — Flux 는 ArgoCD 의 대안 GitOps 도구. 둘 다 도입하면 부담
- **단일 환경 (환경마다 별도 repo)** — 환경별 매니페스트 중복 큼

## 결과

- 어느 환경에 어떤 commit 의 image 가 떠있는지 git 으로 추적 가능
- prod 배포는 staging 검증 후 수동 승인 필요 — 사고 위험 감소
- ApplicationSet 으로 환경 추가 / 제거가 매니페스트 한 줄 수정으로 가능
- (단점) GitOps repo 의 commit 빈도가 높아짐 — squash (여러 commit 합치기) 또는 별도
  bot account 사용
- (단점) PR 별 preview 환경은 cost 증가 — TTL (자동 만료 시간) 자동 정리 필요
