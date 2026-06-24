# ADR-0011: Chaos Engineering 정기 실시

## 상태
적용

## 배경

복원력 있는 시스템 설계를 했다고 해서 실제로 복원되는지는 별개. 운영 환경에서만 드러나는
문제 (네트워크 inter-connect 동작, 캐시 warm-up — 캐시가 비어 있다가 채워지는 동안의
성능 저하, autoscaler 반응 시간 등) 를 사전에 발견할 유일한 방법은 의도적 장애 주입
(chaos engineering).

대안 검토:
- **사후 회고만 의존**: 실제 장애가 나야 학습. 비용 큼
- **staging 에서 chaos**: 트래픽 패턴이 운영과 달라 검증 가치가 낮음
- **운영 환경 chaos (Game Day — 정기적으로 운영에서 일부러 장애를 만들고 대응 훈련하는
  방식)**: Netflix Chaos Monkey 가 시작점. 본 ADR 의 결정

## 결정

Chaos Mesh (K8s 용 chaos 도구, CNCF graduated — Cloud Native Computing Foundation 의
graduated 등급 프로젝트) 도입. 5개 실험 정의:

| 실험 | 가설 | 빈도 |
|---|---|---|
| Pod kill | PDB (PodDisruptionBudget — 동시 종료 가능 Pod 제한) 가 다운타임 0 보장 | 주 1회 |
| DB network delay 200ms | p95 SLO 유지 | 월 1회 |
| Kafka partition 격리 | Outbox 자동 회복 | 월 1회 |
| Worker CPU stress 90% | 콜백 재시도 성공 | 분기 1회 |
| GPU 노드 drain (강제 비우기) | Job 자동 재제출 | 분기 1회 |

운영 원칙:
- 새벽 시간대 (KST 03~06) 만 실행
- 사전 Slack 공지 자동
- error rate > 5% 또는 p99 > 5s (100건 중 99번째로 느린 요청이 5초 초과) 시 자동 abort
- 결과는 [`docs/dr/chaos-results.md`](../../../docs/dr/chaos-results.md) 에 누적

## 결과

- 가설이 깨진 사례를 미리 발견 (예: HikariCP idle timeout 600s → 60s 변경 필요. HikariCP
  는 Spring 의 기본 DB 커넥션 풀, idle timeout 은 사용 안 하는 커넥션을 정리하는 시간)
- DR runbook 의 실측 RTO / RPO 가 분기별로 갱신
- 새 기능 추가 시 "이 기능이 추가되면 어떤 chaos test 가 필요한가" 가 design 단계에서 논의됨
- (한계) Game Day 운영 부담. 분기 1회 + 자동 매주 1회로 균형
- (한계) 일부 시나리오는 운영 환경에서만 의미 있음 — staging 에서 사전 검증 후 prod 적용

## 용어 풀이 (쉽게)

- **chaos engineering (혼돈 공학)** — 진짜 장애가 나기 전에 일부러 Pod를 죽이거나 네트워크를 느리게 만들어 "우리 시스템이 정말 버티나"를 미리 시험해보는 것. 소방훈련을 실제 불이 나기 전에 해보는 셈.
- **SLO (Service Level Objective)** — "응답의 95%는 2초 안에" 처럼 스스로 지키기로 정한 품질 목표 수치. 이 선을 넘으면 경보가 울린다.
- **abort 조건 (자동 중단)** — 훈련 도중 에러가 5%를 넘는 등 실제 사용자에게 피해가 커지면 실험을 즉시 멈추는 안전장치. 훈련이 진짜 사고로 번지지 않게 하는 비상 정지 버튼.
