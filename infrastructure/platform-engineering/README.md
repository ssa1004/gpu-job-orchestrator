# Platform Engineering

플랫폼 팀이 제공하는 self-service (개발자가 직접 신청·생성하는) 도구. 개발팀이 인프라
팀에 티켓 없이도 GPU namespace 를 신청 / 신규 서비스를 부트스트랩 (초기 설치) 할 수
있게 한다.

```
platform-engineering/
├── backstage/      Backstage software template (Backstage = Spotify 가 만든 오픈소스
│                   개발자 포털, software template 은 미리 정의된 신청서 양식)
└── crossplane/     XRD / Composition (Crossplane = K8s 매니페스트로 인프라까지 선언적
                    으로 관리하는 도구. XRD = 사내용 추상 리소스 타입 정의,
                    Composition = XRD 가 어떤 실제 리소스로 펼쳐지는지 규칙 — GPU
                    namespace 셀프 프로비저닝에 사용)
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
   ProviderConfig (AWS / K8s 자격 증명 묶음) 호출 → namespace + ResourceQuota
   (namespace 단위 자원 한도) + RBAC (Role-Based Access Control — 누가 어떤 K8s API
   를 호출할 수 있는지 정의) + IRSA Role (IAM Roles for Service Accounts — Pod 가
   AWS API 를 호출할 때 쓰는 IAM 역할)
```

플랫폼 팀은 매번 매니페스트를 수기로 만들지 않고 정책만 관리. 개발팀은 ticket 대신
Backstage 폼만 채우면 됨.
