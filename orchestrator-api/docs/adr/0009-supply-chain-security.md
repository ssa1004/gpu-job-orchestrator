# ADR-0009: 공급망 보안 — SBOM + Cosign + Kyverno

## 상태
적용

## 배경

컨테이너 이미지가 운영에 들어가는 경로 (CI 빌드 → registry → K8s deploy) 의 어느 단계
에서도 공격이 가능:

- CI 빌드 도중 의존성 swap (typosquatting, dependency confusion)
- registry 에서 이미지 변조 (compromised registry)
- K8s deploy 시 잘못된 image 사용 (사람의 실수, 또는 인사이드 공격)

대표적 incident: SolarWinds, Codecov, npm event-stream. 단순 vulnerability scan
(Trivy 등) 만으로는 부족.

## 결정

3-stage 공급망 보안:

### 1. SBOM 생성 (Syft, CycloneDX 형식)

매 이미지 빌드 시 SBOM (Software Bill of Materials) 생성. 어떤 라이브러리 / 어떤 버전이
들어갔는지 명세.

### 2. Cosign attestation (in-toto)

SBOM 을 이미지에 attest. Sigstore Rekor 에 transparency log 기록.

```bash
cosign attest --type cyclonedx \
  --predicate sbom.cdx.json \
  registry/image@digest
```

서명은 GitHub OIDC 의 keyless 모드 — secret 없이 GitHub Actions 신원으로 서명.

### 3. Kyverno admission policy (`require-image-signature.yaml`)

K8s admission webhook 에서 검증. 서명되지 않은 이미지의 Pod 생성을 차단.

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
- 향후 `cosign verify-attestation` 을 Argo CD presync hook 에 추가 가능

## 결과

- registry 가 변조되어도 Kyverno 가 차단 (서명 attestor 가 일치하지 않음)
- "운영에 들어간 이미지가 이 commit / 이 CI 에서 만들어졌는지" 증명 가능
- SOC2 / SLSA L3 같은 컴플라이언스 요구사항 충족 가능
- (단점) Cosign 키 관리 부담은 keyless OIDC 로 회피했지만 verifier (Kyverno) 가
  Sigstore Rekor 에 의존 — 외부 서비스 장애 시 deploy 막힘. fallback 정책 필요
- (단점) Pod 생성 시 verify webhook 호출이 약 100~300ms 추가 — autoscaling 시 latency
