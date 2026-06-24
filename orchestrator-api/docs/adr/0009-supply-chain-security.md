# ADR-0009: 공급망 보안 — SBOM + Cosign + Kyverno

## 상태
적용

## 배경

컨테이너 이미지가 운영에 들어가는 경로 (CI 빌드 → 이미지 레지스트리 → K8s deploy) 의
어느 단계에서도 공격이 가능:

- CI 빌드 도중 의존성 swap (typosquatting — 진짜 패키지명과 비슷한 이름의 가짜 패키지로
  속임수, dependency confusion — 같은 이름의 사내 패키지를 외부 저장소에서 끌어오게
  유도하는 공격)
- 레지스트리에서 이미지 변조 (compromised registry — 레지스트리 자체가 뚫린 경우)
- K8s deploy 시 잘못된 image 사용 (사람의 실수, 또는 인사이드 공격)

대표적 incident: SolarWinds, Codecov, npm event-stream. 단순 vulnerability scan
(Trivy 같은 취약점 스캐너) 만으로는 부족.

## 결정

3 단계 공급망 보안:

### 1. SBOM 생성 (Syft, CycloneDX 형식)

매 이미지 빌드 시 SBOM (Software Bill of Materials — 이미지에 들어간 부품 목록) 생성.
어떤 라이브러리 / 어떤 버전이 들어갔는지 명세. CycloneDX 는 SBOM 표준 포맷 중 하나.

### 2. Cosign attestation (in-toto)

이미지 자체에 SBOM 을 attest — 메타데이터에 서명을 묶어 함께 푸시한다. in-toto 는 공급망
attestation 의 표준 포맷 (predicate / subject 구조). Sigstore Rekor (서명 기록을
변조 불가하게 공개 저장하는 transparency log) 에 기록 → 누가·언제·어떤 이미지에
서명했는지 사후 검증 가능.

```bash
cosign attest --type cyclonedx \
  --predicate sbom.cdx.json \
  registry/image@digest
```

서명은 GitHub OIDC 의 keyless 모드를 사용한다. 흐름은 다음과 같다.

1. CI 워크플로우가 GitHub Actions 의 OIDC token 을 발급받음 (workflow 신원 증명).
2. 그 token 으로 Sigstore Fulcio 가 단기 (10분) 인증서 발급.
3. Cosign 이 그 인증서로 서명 → Rekor 에 영구 기록.
4. 인증서가 곧 만료돼도 Rekor 의 서명 기록은 영구 보존 → 검증은 언제나 가능.

별도 키 파일 / Vault 가 필요 없음. 키 유출 위험 ↓, 운영 부담 ↓.

### 3. Kyverno admission policy (`require-image-signature.yaml`)

K8s admission webhook (Pod 생성 직전에 정책을 검사하는 훅) 에서 검증. 서명되지 않은
이미지의 Pod 생성을 차단.

```yaml
verifyImages:
  - imageReferences: ["ghcr.io/ssa1004/*"]
    attestors:
      - keyless:
          subject: "https://github.com/ssa1004/*"
          issuer: "https://token.actions.githubusercontent.com"
```

추가 보강:
- Trivy SBOM-based scan (이미 빌드된 이미지뿐 아니라 SBOM 자체로도 검사)
- 향후 `cosign verify-attestation` 을 Argo CD presync hook (sync 직전 실행되는 검증 훅)
  에 추가 가능

## 결과

- 레지스트리가 변조되어도 Kyverno 가 차단 (서명 attestor — 누가 서명했는지 검증하는
  설정 — 가 일치하지 않음)
- "운영에 들어간 이미지가 이 commit / 이 CI 에서 만들어졌는지" 증명 가능
- SOC2 (서비스 조직의 보안·가용성 통제 인증) / SLSA L3 (공급망 무결성 단계 정의 중
  L3 — 빌드 격리·서명 필수 단계) 같은 컴플라이언스 요구사항 충족 가능
- (단점) Cosign 키 관리 부담은 keyless OIDC 로 회피했지만 verifier (Kyverno) 가
  Sigstore Rekor 에 의존 — 외부 서비스 장애 시 deploy 막힘. fallback 정책 필요
- (단점) Pod 생성 시 verify webhook 호출이 약 100~300ms 추가 — autoscaling 시 latency
  영향

## 용어 풀이 (쉽게)

- **공급망 보안 (supply chain security)** — 코드가 빌드되어 운영에 올라가기까지의 모든 길목(빌드→저장소→배포)에 가짜·변조 부품이 끼어들지 못하게 지키는 것. 택배가 출고에서 문 앞까지 오는 동안 누가 내용물을 바꿔치기 못 하게 봉인하는 셈.
- **keyless 서명 (OIDC 기반 서명)** — 비밀 열쇠 파일을 직접 보관하지 않고, CI가 "나 GitHub의 이 워크플로우 맞아요"라는 단기 신분증을 그때그때 발급받아 서명하는 방식. 열쇠를 금고에 둘 필요가 없으니 잃어버릴 일도 없다.
- **transparency log (Rekor)** — 누가·언제·무엇에 서명했는지를 지울 수 없게 공개로 쌓아두는 장부. 나중에 인증서가 만료돼도 "그때 분명히 서명했다"를 증명할 수 있다.
- **admission webhook (Kyverno)** — Pod가 클러스터에 들어오기 직전 문지기가 "이 이미지 서명 됐어?"를 검사해 안 됐으면 입장을 막는 검문소.
