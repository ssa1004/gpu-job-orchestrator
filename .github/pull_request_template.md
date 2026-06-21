<!--
PR 제목은 Conventional Commits 형식을 따른다: feat: / fix: / refactor: / test: / docs: / ci: / build: / chore:
-->

## 변경 요약

<!-- 무엇을, 왜 바꿨는지 2~3줄. 관련 이슈가 있으면 `Closes #123`. -->

## 변경 유형

- [ ] feat — 새 기능
- [ ] fix — 버그 수정
- [ ] refactor — 동작 변화 없는 구조 개선
- [ ] test — 테스트 추가 / 보강
- [ ] docs — 문서
- [ ] ci / build — 파이프라인 / 빌드 / 컨테이너 / IaC
- [ ] chore — 기타

## 체크리스트

- [ ] 로컬에서 `./gradlew test` (orchestrator-api) 또는 `go test ./...` (worker) 통과
- [ ] 한 PR = 하나의 논리적 변경 (관련 없는 변경 섞지 않음)
- [ ] 빌드 산출물(`build/`, `*.tgz`, 렌더된 매니페스트)을 커밋하지 않음
- [ ] 비밀값 / 사내 정보 / 회사 이메일을 포함하지 않음

## IaC / CI 변경이라면

<!-- Helm / 워크플로우 / Dockerfile 을 건드린 PR 만 해당. 아니면 이 섹션 삭제. -->

- [ ] `helm lint` (values.yaml + values-prod.yaml) 통과
- [ ] `helm template ... | kubeconform -strict -ignore-missing-schemas` 통과 (dev + prod)
- [ ] `actionlint .github/workflows/*.yml` 통과
- [ ] 새 external `uses:` 는 40-hex 커밋 SHA 로 핀 (`@<sha> # vTAG`)
- [ ] `hadolint` 통과 (Dockerfile 변경 시)

## 검증 방법

<!-- 리뷰어가 재현할 수 있는 명령 / 단계. 실행한 검증 명령과 결과를 적는다. -->
