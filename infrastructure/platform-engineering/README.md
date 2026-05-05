# Platform Engineering

플랫폼 팀이 제공하는 self-service 도구. 개발팀이 인프라 팀에 티켓 없이도 GPU namespace
를 신청 / 신규 서비스를 부트스트랩 할 수 있게 한다.

```
platform-engineering/
├── backstage/      Backstage software template (서비스 templates)
└── crossplane/     XRD / Composition (GPU namespace self-provisioning)
```

## 운영 패턴

```
[ 개발팀 ]
   │  Backstage portal 에서 "GPU Namespace 신청" 클릭
   ▼
[ Backstage ]
   │  GitOps repo 에 PR 생성 (namespace 매니페스트 + Crossplane Claim)
   ▼
[ 플랫폼팀 검토 ]
   │  PR 승인 → merge
   ▼
[ ArgoCD ]
   │  Crossplane Claim sync
   ▼
[ Crossplane ]
   ProviderConfig (AWS / K8s) 호출 → namespace + ResourceQuota + RBAC + IRSA Role
```

플랫폼 팀은 매번 매니페스트를 수기로 만들지 않고 정책만 관리. 개발팀은 ticket 대신
Backstage 폼만 채우면 됨.
