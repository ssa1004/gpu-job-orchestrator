# ADR-0022: Job 라이프사이클 State Machine — 도메인 메서드 보강 사이드카

## 상태
적용

## 배경

`Job` 애그리거트는 `markRunning`, `markSucceeded`, `markFailed`, `markCancelled`,
`markPreempted`, `markReadyToQueue`, `markDispatched` 등 7~8 개의 mark* 메서드로
상태 전이를 수행한다. 각 메서드가 자기 source 상태를 if-else 로 검증하고
{@code IllegalJobTransitionException} 으로 reject — 도메인 무결성은 유지되지만,
*전체 라이프사이클의 catalog* 는 코드 사이에 흩어져 있다.

운영 / 신규 개발자 / docs 측에서 자주 일어나는 질문:

1. *"WAITING_DEPS 에서 어떤 event 가 발생 가능?"* — 코드 grep 후 7곳을 다 읽어야 답.
2. *"새 transition (예: timeout retry — DISPATCHING → QUEUED) 을 추가하려면 어디 손대?"* — Job
   클래스의 if-else 가지를 늘려야. 도메인 객체가 점점 워크플로우 엔진 비슷해진다.
3. *"라이프사이클 다이어그램이 코드와 동기화되나?"* — 손으로 그린 PNG 가 항상 stale.

표준 해법: workflow engine. **Temporal / Cadence** 가 무거운 쪽, **Spring StateMachine**
이 가벼운 쪽. 우리는 *지금 단계의 복잡도* 를 보고 둘 다 도입 안 하고, *minimal transition
table* 을 자체 구현으로 두었다.

## 결정

### 1) Transition Table 을 데이터로 표현

`JobLifecycleStateMachine` 이 `(source, event) → target [+ guard + action]` tuple 의 list 로
모든 활성 라이프사이클 전이를 catalog 화. `JobLifecycleStateMachineFactory.build()` 가
공식 정의 — 운영자 / 신규 개발자가 한 곳에서 라이프사이클 전체를 본다.

기존 도메인 메서드는 *그대로 보존* — 이 머신은 *외부 검증 + 기록 + 시각화* 의 사이드카.

### 2) 두 방어선 (도메인 + 머신)

`JobLifecycleService` 가 도메인 메서드 호출 *직전* 에 `lifecycleStateMachine.fire(...)` 로
같은 전이가 머신 측에서도 정의되어 있는지 검증.

```
service.updateStatusFromCallback(...)
  ├── lifecycleStateMachine.fire(QUEUED, RUN, job)   ← workflow 어휘 측 검증
  └── job.markRunning(clock)                          ← 도메인 무결성 측 검증
```

둘이 어긋나면 `DomainStateMachineConsistencyTest` 가 매 빌드마다 fail.

### 3) Guard — Preempt 정책 검증

`PREEMPT` 이벤트는 `PreemptionPolicy.PREEMPTABLE` 인 잡만 허용. 머신의 transition 에
guard 등록 → context (`Job` 인스턴스) 의 정책을 검사. 도메인의 `markPreempted` 도 같은
방어선이지만, 머신 단계에서 reject 하면 *왜 거절했는지* 가 audit log 에 명확히 박힘.

### 4) Mermaid 다이어그램 자동 생성 (`MermaidStateDiagram`)

머신의 transition table → Mermaid stateDiagram-v2 텍스트. ADR / docs / README 에 박을
수 있는 *코드와 동기화된* 다이어그램.

```mermaid
stateDiagram-v2
    state SUCCEEDED
    state FAILED
    state CANCELLED
    state PREEMPTED
    WAITING_DEPS --> QUEUED: DEPENDENCIES_RESOLVED
    WAITING_DEPS --> CANCELLED: DEPENDENCIES_BROKEN
    QUEUED --> DISPATCHING: DISPATCH
    QUEUED --> RUNNING: RUN
    DISPATCHING --> RUNNING: RUN
    QUEUED --> SUCCEEDED: SUCCEED
    DISPATCHING --> SUCCEEDED: SUCCEED
    RUNNING --> SUCCEEDED: SUCCEED
    WAITING_DEPS --> SUCCEEDED: SUCCEED
    QUEUED --> FAILED: FAIL
    DISPATCHING --> FAILED: FAIL
    RUNNING --> FAILED: FAIL
    WAITING_DEPS --> FAILED: FAIL
    QUEUED --> CANCELLED: CANCEL
    DISPATCHING --> CANCELLED: CANCEL
    RUNNING --> CANCELLED: CANCEL
    WAITING_DEPS --> CANCELLED: CANCEL
    QUEUED --> PREEMPTED: PREEMPT (guard)
    DISPATCHING --> PREEMPTED: PREEMPT (guard)
    RUNNING --> PREEMPTED: PREEMPT (guard)
    WAITING_DEPS --> PREEMPTED: PREEMPT (guard)
```

## 왜 Spring StateMachine 라이브러리를 안 쓰는가

Spring StateMachine 은 다음을 풍부하게 제공:

- builder DSL, hierarchical states, parallel regions, history states
- StateMachinePersister + JPA — 머신 instance 를 DB 에 저장 / 복구
- listener / interceptor 체인, AOP

우리 시나리오에서 *지금 필요한 것* 은 transition table 의 데이터 표현 + 외부 검증뿐.
hierarchical / parallel / history 는 over-spec. 라이브러리 도입 시 비용:

- 학습 곡선 (DSL / lifecycle / configurer 패턴)
- 의존성 그래프 비대 (Spring Statemachine + extensions ~수 MB)
- 기존 도메인 메서드 / JPA Job 엔티티와의 통합 추가 작업

자체 구현 50 라인 + 단위 테스트 200 라인이 *현재의 ROI* 측에서 우위. transition 수가
30+ 가 되거나 hierarchical / parallel 이 필요해지는 시점에 라이브러리 마이그레이션.

## 왜 Temporal / Cadence 도 안 쓰는가

Temporal 은 *durable workflow execution* — workflow 자체가 worker process 에 의해 실행되고,
중간 상태가 자동으로 persist 된다. 제 가치는 *long-running workflow* (수 분 ~ 수 일):

- 외부 시스템 호출의 retry / timeout 자동 관리
- workflow 가 server crash 후 자동 재개
- saga / compensation 패턴

우리 잡의 라이프사이클은 외부 콜백 (워커 → orchestrator) 에 의해 *push-driven* 으로 진행.
orchestrator 가 workflow 를 *직접 실행* 하는 게 아님. Temporal 의 강점이 우리 모델과
부딪힘 — 우리는 *콜백 받아 상태만 갱신* 하는 thin model. 도입 비용 (Temporal cluster 운영)
대비 ROI 낮음.

언제 Temporal 검토:
- 잡 자체가 *오케스트레이션* 의 단위가 됐을 때 (예: hyperparameter sweep — 100 child job
  자동 spawn / 결과 aggregate / report 발행).
- 외부 의존 (S3 / SageMaker / Slack) 호출 retry / saga 가 필요할 때.
- 잡 lifecycle 이 *수 일* 이상 살아있고 orchestrator 가 그 사이 재시작될 가능성이 높을 때.

## 다시 검토할 시점

- transition 이 30+ 개 또는 hierarchical state (예: `RUNNING.preprocessing` /
  `RUNNING.training` / `RUNNING.uploading` 같은 sub-state) 가 필요해지면 Spring StateMachine.
- workflow 자체가 *오래 살고 자체 실행* 이 필요하면 Temporal.
- 다른 도메인 객체 (User / Quota / Invoice) 도 라이프사이클이 복잡해지면 머신 패턴을
  공통 추상으로 (genericized `LifecycleStateMachine<S, E>`).

## 참고 자료

- Spring StateMachine — https://spring.io/projects/spring-statemachine
- Temporal architecture — https://docs.temporal.io/concepts/what-is-a-workflow
- ADR-0014 (priority + preemption) — preempt transition 의 도메인 모델
- ADR-0015 (job dependencies) — DEPENDENCIES_RESOLVED / DEPENDENCIES_BROKEN 의 출처
