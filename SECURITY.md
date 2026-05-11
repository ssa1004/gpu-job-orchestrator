# Security Policy

본 레포는 GPU job orchestrator 백엔드 + 인프라 구성 (Terraform / Helm / GitOps) 의
**포트폴리오 / 학습 자료** 입니다. 운영 시스템이 아니므로 운영용 보안 SLA 는 없으나,
의존성 / 컨테이너 / Kubernetes manifest 의 잘못된 패턴 제보는 환영합니다.

## 보고 채널

취약점이나 보안상 문제 (시크릿 commit, manifest 의 위험한 default, dependency CVE 등) 는
**GitHub private security advisory** 로 보고해 주세요.

- 레포 → Security 탭 → "Report a vulnerability"
- 또는 메인테이너 GitHub 핸들로 직접 연락: `@ssa1004`

공개 이슈 트래커 (`Issues`) 에는 PoC / 재현 절차 / 시크릿이 포함되지 않도록 부탁드립니다.

## 대응 SLA

포트폴리오 레포 특성상 강제 SLA 는 없으나 다음을 원칙으로 합니다.

| 단계 | 목표 시간 |
| --- | --- |
| 최초 응답 (수신 확인) | 7 일 이내 |
| 영향도 / 재현 평가 | 14 일 이내 |
| 패치 또는 mitigation | 30 일 이내 (severity 에 따라 단축) |

## Scope

### In scope

- `orchestrator-api/` Spring Boot 코드 (인증 / 인가 / 입력 검증 / SQL injection 등)
- `worker/` Go 워커 (콜백 검증 / TLS / 시크릿 처리)
- `infrastructure/security/` Kyverno / Falco / External Secrets manifest 의 잘못된 default
- `helm/gpu-job-orchestrator/` chart 의 위험한 기본값 (privileged, hostNetwork 등)
- `infrastructure/ci-cd/` 의 GitHub Actions workflow snapshot — 권한 누수, 시크릿 노출,
  composite action pin 누락 등

### Out of scope

- 의도적으로 mock / dev 전용으로 표시된 구현 (예: `MockJobDispatcher`,
  `MockPresignedUrlProvider`, `application-dev.yml` 의 H2)
- ADR / 문서 / runbook 의 작성 시점 한정 가정
- `infrastructure/ci-cd/github-actions/` 의 placeholder 값 (account id `123456789012`,
  ECR registry URL 등) — README 에 환경별 교체로 명시됨

## 공급망 보안

레포 자체에 적용된 공급망 보안 통제는 README **공급망 보안** 섹션을 참고해 주세요.
요약: Trivy (이미지 / SBOM 스캔), Syft (CycloneDX SBOM), Cosign keyless 서명,
Kyverno admission policy 6건. 본 정책 문서는 그 통제들이 *어떻게 운영되는지* 가 아닌,
*외부 제보를 어떻게 받는지* 만 다룹니다.
