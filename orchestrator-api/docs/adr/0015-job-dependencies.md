# ADR-0015: Job Dependencies (DAG) — 워크플로우 자동 진행

## 상태
적용

## 배경

ML / 데이터 파이프라인의 흔한 패턴은 다음과 같다.

- 데이터 전처리 → 학습 → 평가 → 배포 (순차 chain)
- 데이터 split → 모델 A 학습 / 모델 B 학습 / 모델 C 학습 → 앙상블 (fan-out / fan-in)
- 체크포인트 → 재학습 fine-tune (조건부 시작)

매번 사용자가 "잡 A 끝났는지 polling 후 잡 B 제출" 하면:
- 운영 부담 (스크립트 / cron 으로 직접 polling)
- 잘못된 시점에 제출 (parent 가 실패했는데 child 가 시작)
- 사용자가 중간에 자리 비우면 워크플로우 멈춤

표준 해법: **DAG dependency** (Directed Acyclic Graph — 방향성 있고 순환 없는 그래프
모양으로 잡끼리 의존 관계를 표현). Airflow / Argo Workflows / Kubeflow / Prefect 모두
같은 모델.

## 결정

### 도메인 모델

```
Job 에 새 status: WAITING_DEPS — parent 들 끝나길 대기 중
JobDependency  — child × parent (many-to-many) edge entity (잡과 잡 사이의 간선)
```

```sql
CREATE TABLE job_dependencies (
    child_job_id, parent_job_id,
    UNIQUE (child_job_id, parent_job_id),
    CHECK (child_job_id <> parent_job_id)
);
```

### Cycle detection — 제출 시점

새 잡 제출 시 영속화 전에 검증한다. cycle (잡 A→B→C→A 처럼 끝없이 도는 의존 관계) 이
있으면 `DependencyCycleException` 으로 거절. 한 번 영속된 그래프에는 cycle 이 절대 생기지
않도록 보장한다.

- **알고리즘**: `DependencyGraph.detectCycle(graph)` — DFS (Depth-First Search,
  그래프를 깊이 우선으로 순회) + WHITE/GRAY/BLACK 3색 마킹. GRAY 인 노드를 다시 만나면
  현재 경로가 자기 자신으로 돌아왔다는 뜻이라 cycle 이다. 시간 복잡도 O(V+E).
- **scope — 도달 가능한 부분 그래프만**: 새 잡은 leaf (어떤 child 의 parent 도 아직
  아님). 따라서 새 잡이 cycle 의 종점이 되려면 누군가가 새 잡의 parent 중 하나의
  조상에 있어야 한다. 새 잡의 parents 부터 BFS 로 거슬러 올라가며 닿는 노드만 검사하면
  충분하다. 무관한 그래프는 메모리에 안 올린다 (잡 수가 수만+ 일 때 핵심).

### Cascade 정책 (parent 의 결과가 child 에게 자동으로 번지는 정책)

`DependencyResolutionService` 가 두 가지 trigger 로 호출된다.

1. **`onParentTerminal(parentJobId)`** — `JobLifecycleService` 가 parent 종착 직후 호출.
   같은 트랜잭션 안에서 child 를 promote 하거나 cancel 한다. parent commit 과 원자적.

2. **`scanWaitingJobs()`** — `DependencyScanScheduler` 가 매 분 호출.
   lifecycle event 유실 / parent 가 child 보다 먼저 SUCCEEDED 된 race (경쟁 상태) 를
   보강한다. idempotent (몇 번 실행해도 결과가 같다).

```
parent SUCCEEDED + child 의 모든 parent 도 SUCCEEDED  →  child WAITING_DEPS → QUEUED
parent FAILED 또는 CANCELLED                          →  child 자동 CANCELLED (cascade-cancel)
parent PREEMPTED                                       →  child 그대로 WAITING (preempt 는 재투입 가능)
```

### Race 조건 처리

- Parent 가 child 보다 먼저 SUCCEEDED: child 제출 시점에 `allParentsAlreadySucceeded`
  검사 → 즉시 promote + dispatch.
- 동시에 여러 parent 가 SUCCEEDED: `tryResolveChild` 가 idempotent — 모든 parent 가
  SUCCEEDED 될 때만 promote, 그 외엔 no-op (아무 것도 안 한다).
- cascade-cancel 과 사용자 cancel 의 race: `markReadyToQueue` 가 status invariant
  (불변 조건) 검사를 해서 이미 다른 스레드가 처리한 경우 `IllegalJobTransitionException`
  으로 빠진다. 다음 scan 에서 다시 시도한다.

### 왜 명시 호출 (vs Spring ApplicationEvent)

`DependencyResolutionService.onParentTerminal` 은 lifecycle service 가 직접 호출한다 —
같은 트랜잭션 경계 안에서 처리하기 위함이다.

ApplicationEvent + `@TransactionalEventListener(AFTER_COMMIT)` (트랜잭션 commit 이후
비동기로 발행되는 Spring 의 이벤트 리스너) 는 child 처리가 별도 트랜잭션이라 race window
(race 가 발생할 수 있는 시간 창) 가 생긴다. parent commit 후 child promote 사이에 사용자
cancel 이 끼어드는 식. 명시 호출이 트랜잭션 경계를 분명히 한다.

### REST

```
POST /api/v1/jobs
{
  "inputUri": "s3://...",
  "image": "trainer:1",
  "gpuCount": 4,
  "parentJobIds": ["uuid-of-preprocess-job"]    // 비어 있으면 즉시 dispatch
}
```

응답에서 status 가 `WAITING_DEPS` 면 parent 끝날 때까지 대기 중.

## 대안 검토

- **Airflow / Argo Workflows 도입** — production-grade DAG runner. 거부.
  - 클러스터에 새 controller 설치 / DAG 정의를 YAML 로 따로 / 학습 비용
  - 우리 도메인 (Job aggregate) 과의 매핑 비용
  - 본 프로젝트 규모 (수백 잡/일) 면 자체 구현이 가벼움
- **Workflow 정의를 YAML 로** — Argo 처럼 DAG 전체를 한 번에 제출.
  거부. 사용자가 런타임에 "이 잡 끝나면 추가로 새 잡 chain" 하는 dynamic workflow 가 더 흔함.
  edge 단위 제출이 더 유연.
- **Polling-based child trigger** — cron 만 (lifecycle hook 없이) 사용.
  거부. 1분 lag — 사용자 perception 나쁨. lifecycle hook + 보강 cron 두 layer 가 robust.
- **Cascade-cancel 안 함** — parent FAILED 여도 child 는 그대로 WAITING.
  거부. parent 결과를 input 으로 쓰는 child 는 영원히 시작하지 못한다. 사용자가 명시적으로
  cancel 하기 전까지 quota 만 계속 차지한다. 자동 cascade-cancel 이 안전한 기본값.

## 결과

- 사용자 / 외부 시스템 polling 부담 제거 — submit + 의존성 명시만으로 워크플로우 자동 진행
- 한 트랜잭션 안의 cascade-cancel — parent commit 과 child 처리가 원자적
- 보강 scan 으로 lifecycle event 유실 / race 모두 cover
- 사이클 영원히 차단 — DB 영속 전 detection
- (단점) 보강 scan 은 매 분 → 유실 시 최대 1분 lag (지연)
- (단점) DependencyGraph 가 전체 edge 를 로드한다. 잡 수가 수만+ 되면 partial scan
  (변경된 부분만 부분 검사) 으로 분리 필요.
- (단점) 자동 cascade-cancel 정책이 항상 옳진 않음 — parent 실패해도 child 는 진행하는
  옵션은 후속 작업.

## 후속 후보

- `DependencyMode`: `AFTER_SUCCESS` / `AFTER_ANY_TERMINAL` / `AFTER_ALL_REGARDLESS`
  (Airflow 의 trigger rule — parent 결과에 따라 child 를 어떻게 트리거할지 정의 — 참고)
- DAG 시각화 API — 운영 화면에서 그래프로 표시
- Workflow template — 같은 chain 을 N번 재사용 (parametrized — 매번 다른 인자로 같은
  파이프라인 실행)
- Partial cycle detection — 전체 edge 로드 대신 새 edge 만 + neighborhood (인접 노드만)
- Cascade-cancel 도 audit log (변경 추적 기록) 에 기록

## 용어 풀이 (쉽게)

- **DAG (방향성 비순환 그래프)** — 작업들을 "이거 끝나야 저거 시작" 화살표로 잇되, 돌고 도는 순환이 없는 흐름도. 요리 레시피처럼 앞 단계가 끝나야 다음 단계로 가는 순서 그림.
- **fan-out / fan-in** — fan-out은 하나의 작업이 끝나면 여러 작업으로 갈라져 동시에 도는 것(데이터 나눠 모델 A·B·C 학습), fan-in은 그 여러 갈래를 다시 하나로 모으는 것(앙상블).
- **cycle detection (순환 검사)** — A→B→C→A처럼 끝없이 도는 의존 관계가 생기지 않게 미리 막는 것. 순환이 있으면 어느 작업도 영원히 시작 못 한다.
- **cascade-cancel (연쇄 취소)** — 앞 작업(parent)이 실패하면 그 결과를 기다리던 뒤 작업(child)들도 줄줄이 자동 취소하는 것. 도미노처럼 한 칸이 쓰러지면 뒤가 따라 쓰러진다.
- **idempotent (멱등)** — 같은 처리를 여러 번 돌려도 결과가 한 번 한 것과 똑같은 성질. 보강 스캔이 중복 실행돼도 사고가 안 난다.
