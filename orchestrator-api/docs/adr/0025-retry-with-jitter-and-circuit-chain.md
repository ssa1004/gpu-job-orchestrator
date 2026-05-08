# ADR-0025: Resilience4j Retry — Exponential Backoff with Jitter + CircuitBreaker Chain

## 상태
적용

## 배경

K8s API server 호출 (job 생성 / 삭제) 은 transient 오류가 자주 난다:

- **leader election 진행 중** — kube-apiserver 가 일시적으로 503 응답 (수 초).
- **etcd compaction / leader 전환** — 502 / 504 (수 초~수십 초).
- **API server overload** — 429 throttling.
- **network blip** — connect timeout / connection reset (TLS handshake 단계).

이런 오류는 *몇 초 후 재시도* 하면 통과한다. 단순 retry 만으로 풀리는 건데,
순진하게 짜면 두 종류 사고가 일어난다.

### 사고 시나리오 1: 순진한 retry (즉시 + 고정 간격)

100 Pod 가 동시에 503 을 받고 똑같이 500ms 후 retry.
broker 입장에서는 *한 번 더 spike* — 회복 중에 또 부하가 몰린다 (thundering herd).
회복이 더 늦어지고, 모든 Pod 의 retry 가 다시 503 → 또 한 번 더 spike. 무한 cascade.

### 사고 시나리오 2: Retry 가 CircuitBreaker 의 안쪽

```
client → CircuitBreaker → Retry → backend
```

이 순서면 retry 한 번이 회로 호출 1회로 카운트. sliding-window 가 20인데 max-attempts
가 4이면, 한 번의 *논리적 호출* 이 window 의 4 칸을 잡아먹는다 → 회로의 false-positive
OPEN. 또, backend 가 hang 한 상태에서 retry 가 timeout 마다 재시도하면, 회로가
OPEN 으로 전이될 신호 (응답 없음) 가 도달하기 전에 max-attempts 를 다 써버림 →
backend hang 의 신호가 회로에 못 도달.

## 결정

### 1) Exponential backoff with full jitter

Retry 의 wait 전략:

```yaml
wait-duration: 500ms
exponential-backoff-multiplier: 2.0
randomized-wait-factor: 0.5
```

수식: `wait = base * 2^attempt * (1 ± 0.5*random)`

- 1차 retry: 250~750ms
- 2차 retry: 500~1500ms
- 3차 retry: 1000~3000ms

100 Pod 가 동시에 503 을 받아도, retry 시점이 위 구간에 균일 분포로 *흩어져* broker
입장에서 한 번에 몰리지 않음. 회복 중인 backend 에 일정한 부하가 천천히 들어감.

### 1.1) jitter 의 세 변종 (참고)

AWS 의 2015 architecture blog 에서 네 변종 비교:

- **No jitter** — `base * 2^attempt`. thundering herd 그대로.
- **Equal jitter** — `(base * 2^attempt)/2 + random(0, (base * 2^attempt)/2)`.
  hard floor 절반이 보장 → 너무 빠른 retry 차단. 분산 효과는 절반.
- **Full jitter** — `random(0, base * 2^attempt)`. 분산 최대. 우리가 채택.
- **Decorrelated jitter** — `random(base, prev * 3)`. attempt 간 상태 유지 — 구현 복잡.

분산 효과 측면에서 full jitter 가 simulator 에서 가장 깔끔한 결과 (AWS blog 의 그래프).
Resilience4j 의 `randomized-wait-factor` 가 full jitter 와 매우 유사한 동작.

### 2) Decorator chain — Retry 가 *바깥쪽*

```
client → Retry → CircuitBreaker → backend
```

Resilience4j 권장 순서. 이유:

- **회로 OPEN 시 retry 가 fast-fail** — 회로가 OPEN 인 상태에서 retry 시도 1회는
  즉시 `CallNotPermittedException` 으로 떨어짐. backend hang 시간을 기다리지 않음.
  max-attempts 4 회가 *논리적 호출 1회* 이내에 끝남.
- **회로의 실패율 집계가 의미 있음** — 회로는 *retry exhausted 후의 마지막 결과* 만
  카운트. client 가 여러 번 시도한 *논리적 호출* 단위로 실패율 집계 → 임계값 (50%)
  이 의도대로 동작.
- **자동 복구의 일관성** — backend 회복 시 회로가 HALF_OPEN → CLOSED 로 전이하는데,
  retry 가 그 사이 fast-fail 만 받아서 돌고 나면, 다음 *논리적 호출* 에서는 회로가
  CLOSED 라 정상 통과.

`ResilientJobDispatcher` 의 `decorate()` 가 Resilience4j 의 `Decorators.ofCheckedSupplier`
를 써서 위 순서로 chain 을 쌓는다 — withRetry() 가 마지막 호출이라 가장 바깥쪽.

### 3) retry 대상 예외 분류

`RetryableExceptionPredicate` 가 status code 별로 분류:

| status code | retry | 근거 |
|---|---|---|
| 0 (network) | yes | connect timeout / TLS / connection reset — 다음 시도에서 통과 가능 |
| 429 (throttle) | yes | jitter 가 자연스럽게 풀어줌 |
| 5xx | yes | server-side transient |
| 4xx (429 외) | no | 영구 실패 — retry 해도 같은 결과 |

application.yml 의 `retry-exceptions` 만으로는 'KubernetesClientException 중
4xx 는 제외' 같은 부분집합 표현이 불가능. predicate 가 후처리 필터.

### 4) 시도 로깅 + 메트릭

Retry 의 `EventPublisher.onRetry` 에 listener 등록 — 매 시도마다 attempt 번호 +
waited duration + cause 를 한 줄 로그. 운영 가시성:

```
WARN  k8s retry attempt 2 after 750 ms — cause=KubernetesClientException: 503
```

Resilience4j 가 자동 export 하는 메트릭:

- `resilience4j_retry_calls{kind=successful_with_retry}` — retry 후 성공
- `resilience4j_retry_calls{kind=successful_without_retry}` — 첫 시도 성공
- `resilience4j_retry_calls{kind=failed_with_retry}` — retry exhausted 후 실패
- `resilience4j_retry_calls{kind=failed_without_retry}` — predicate 거절

이 4개의 합으로 *모든 호출* 이 분류된다. Grafana 패널에서 successful_with_retry /
total = transient 발생률, failed_with_retry / total = retry 가 못 풀어낸 비율.

### 5) Kafka producer 와의 관계

Kafka producer 자체가 `retries=5` + `enable.idempotence=true` 로 broker 에 가는
*프로토콜 단계* 의 retry 를 한다. 우리가 추가한 Resilience4j retry 는 그 *바깥*
의 layer — connection-level 오류 (broker 미응답, idle timeout 등) 까지 한 겹 더
잡는다. 둘이 직교.

## 왜 over-spec 아닌가

이건 *기본 위생*. K8s API 호출하는 어떤 controller 도 이 패턴 안 쓰는 곳이 없음
(controller-runtime, kube-controller-manager 도 같은 exponential backoff). 안 하면:

- 시나리오 1 (thundering herd) — 일시적 backend 부하가 self-amplifying outage 로 증폭.
- 4xx (영구 실패) 도 retry 하면 → 의미 없는 호출 4배, audit log 노이즈, throttling 오인.
- Retry 가 회로 안쪽이면 (시나리오 2) — backend hang 시 회로 OPEN 신호가 도달하기
  전 retry budget 소진 → 회로의 의미가 사라짐.

추가 비용:
- 코드 ~80 라인 (Predicate + Decorator chain + retry bean wiring)
- yml ~25 라인 (retry config + instances)
- 단위 테스트 ~150 라인 (success / 4xx / 5xx / exhausted / fast-fail-when-open 5 case)

## 다시 검토할 시점

- 다른 외부 의존성 (S3, OAuth provider) 추가 시 — 같은 패턴으로 별 retry instance 등록.
- Kafka 에 대해서도 *application 레벨* retry 가 의미 있는지 검토 (현재는 producer 레벨
  retry 만). idempotence + transactional producer 가 켜져 있으니 일단 보류.
- distributed tracing 과 통합 — 매 retry 시도가 별 span 으로 나오게 하는 게
  trace 분석에 유용할 수 있음. Micrometer Tracing 의 Resilience4j integration 검토.
- bulkhead 추가 — retry / circuit 와 함께 *동시 호출 수 제한* 도 표준 세트.

## 참고 자료

- AWS Architecture Blog (2015) — Exponential Backoff And Jitter
  https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
- Resilience4j docs — Retry / CircuitBreaker / Decorators
  https://resilience4j.readme.io/docs/getting-started-3
- ADR-0023 (3 probe) — readiness 가 회로 OPEN 신호로 toggle 되는 메커니즘.
- 직전 라운드 ADR (resilience: K8s 디스패처 / Kafka relay 에 circuit breaker) — 이 ADR 의 전제.
