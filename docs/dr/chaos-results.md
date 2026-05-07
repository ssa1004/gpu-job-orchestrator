# Chaos Engineering 결과 누적

[`infrastructure/chaos/`](../../infrastructure/chaos/) 의 실험 결과를 매번 기록합니다.
가설이 깨지면 코드 / 설정 수정 후 재실험.

## 결과 표

| 일자 | 실험 | 가설 | 결과 | 5xx rate | p95 latency | 후속 조치 |
|---|---|---|---|---|---|---|
| 2026-05-15 | 01 pod-kill | 다운타임 0 | 통과 | 0% | 평소 + 30ms | - |
| 2026-05-15 | 02 postgres-delay | p95 SLO 유지 | 부분 실패 | 0% | 380ms (SLO 300ms 초과) | HikariCP idle timeout 축소 |
| 2026-05-22 | 02 postgres-delay (재) | 동일 | 통과 | 0% | 290ms | - |
| 2026-05-29 | 03 kafka-partition | Outbox 자동 회복 | 통과 | 0% | 평소 | publish lag 5분 동안 누적, 1분 내 회복 |
| 2026-06-12 | 04 worker-cpu-stress | 콜백 재시도 성공 | 통과 | - | - | 재시도 평균 2.3회 |
| 2026-06-12 | 05 gpu-node-drain | Job 자동 재제출 | 부분 실패 | - | - | 재제출 로직 timeout 미구현. ADR-FUTURE 추가 |

## 실패 사례 분석

### 02 postgres-delay (2026-05-15)

**상황**: 200ms 지연 주입 직후 p95 (100건 중 95번째 응답 시간) 가 380ms 까지 올라감
(SLO 300ms 초과).

**원인**: HikariCP `idle-timeout` (놀고 있는 커넥션을 정리하는 시간) 이 너무 길어 idle
connection 이 stale (오래되어 끊어진 채로) 유지됨.

**조치**: `idle-timeout: 600s → 60s` 로 변경. 재실험에서 SLO 안에 들어옴.

### 05 gpu-node-drain (2026-06-12)

**상황**: 노드 강제 drain (노드 비우기) 시 워커 Pod 가 graceful shutdown (정리 후 깔끔한
종료) 안 되고 즉시 죽음. orchestrator 는 SUCCEEDED / FAILED 콜백을 받지 못해 Job 이
RUNNING 상태로 30분간 유지됨.

**원인**: timeout 감시 작업이 미구현 (ADR-0003 의 follow-up 항목).

**조치**: Job timeout 감시 작업 (Spring Scheduling + ShedLock — 여러 인스턴스 중 하나만
스케줄을 돌리도록 잠금) 추가 예정. `RUNNING` 이 (timeout × 1.5) 넘으면 K8s 상태 자동 동기화.
