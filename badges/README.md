# badges/

CI 가 생성하는 status badge 의 정적 호스팅 위치입니다.

- `jacoco.svg` — orchestrator-api 의 JaCoCo 라인 커버리지 badge. `main` 브랜치로 push 될
  때마다 `ci.yml` 의 `orchestrator-api` job 이 `jacocoTestReport` 의 CSV 를 읽어
  [`cicirello/jacoco-badge-generator`](https://github.com/cicirello/jacoco-badge-generator)
  로 다시 그린 뒤 되커밋합니다 (`chore(ci): update coverage badge [skip ci]`).

루트 README 의 coverage badge 가 이 파일을 참조합니다. 최초 CI 실행 전에는 `pending`
상태로 시작하며, 첫 `main` 빌드 이후 실제 커버리지 수치로 교체됩니다. 커버리지 수치를
손으로 적지 않는 이유는, 실제 측정값과 어긋난 badge 는 신뢰를 떨어뜨리기 때문입니다.
