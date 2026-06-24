# ADR-0023: K8s liveness / readiness / startup probe 3종 분리

## 상태
적용

## 배경

K8s 위에 올린 Pod 의 가용성 신호는 세 가지 — liveness, readiness, startup. 셋의 의미가
다른데 같은 endpoint 로 묶으면 운영에서 곧바로 문제가 된다.

### 사고 시나리오 1: liveness 에 DB ping 을 넣는다

- DB 가 5초 끊기는 일시 장애 발생.
- 모든 Pod 의 livenessProbe 가 fail → K8s 가 모든 Pod 를 동시에 재시작.
- 재시작 후 모든 Pod 가 동시에 DB 첫 connection 을 잡으려 시도하며 부하가 몰린다.
  회복 중인 DB 에 추가 부하가 들어와 회복이 더 늦어진다.
- 사용자 입장에서 5초 끊김이 5분 outage 로 증폭된다.

### 사고 시나리오 2: probe 는 한 개만 (= readiness 만)

- Spring 시작이 느려 (Flyway + JIT warmup 합쳐 60초) readiness 가 한참 unready.
- liveness 도 같은 endpoint 라 fail → K8s 가 시작도 못 한 Pod 를 재시작 → 영원히
  못 뜸 (CrashLoopBackOff).

### 사고 시나리오 3: probe 가 한 개도 없다

- JVM deadlock — 모든 HTTP 스레드가 잠겨 응답 없음. 프로세스는 살아있지만 트래픽
  못 처리. K8s 는 Pod 가 살아 있다고 판단해 트래픽 계속 보냄 → 100% 5xx.

## 결정

### 1) probe 3종 모두 명시

| probe | 답하는 질문 | fail 시 K8s 동작 |
|---|---|---|
| **liveness** | 프로세스가 살아있는가 | Pod 재시작 |
| **readiness** | 트래픽 받을 준비 됐는가 | Service endpoint 에서 제외 (재시작 X) |
| **startup** | 시작 중인가 | Pod 재시작 (단, startup 성공할 때까지 다른 probe disabled) |

### 2) 각 probe 의 endpoint

```yaml
startupProbe:    /actuator/health/readiness
readinessProbe:  /actuator/health/readiness
livenessProbe:   /actuator/health/liveness
```

`management.endpoint.health.probes.enabled=true` + `management.health.livenessstate.enabled=true`
+ `management.health.readinessstate.enabled=true` 로 Spring Boot 3 의
`ApplicationAvailability` API 를 활성화. `/actuator/health/liveness` 와
`/actuator/health/readiness` 가 자동 노출된다.

### 3) liveness 에는 외부 의존성을 보지 않는다

기본 Spring `LivenessStateHealthIndicator` 는 ApplicationContext 가 살아있고
`LivenessState=CORRECT` 일 때만 200. DB / Kafka / Redis 같은 외부 의존성은 일부러 안
본다. liveness fail 의 정당한 원인은 다음 세 가지뿐이다.

- ApplicationContext refresh 실패 (Spring 이 자동 BROKEN 으로 publish)
- JVM deadlock 으로 actuator 자체도 응답 없음 (K8s 가 timeout 으로 자동 감지)
- OOM 후 좀비 (마찬가지로 timeout)

### 4) readiness 는 critical 외부 의존성만 본다

`ApplicationReadinessCoordinator` 가 Resilience4j 의 회로 상태를 readiness 신호로
매핑한다.

- **Kafka circuit OPEN** → readiness=REFUSING_TRAFFIC. broker 가 영구 down 인 상태로
  트래픽 받아봐야 outbox 만 쌓이고 사용자에게는 무응답이다.
- **K8s API circuit OPEN** → readiness=REFUSING_TRAFFIC. dispatch 자체가 fast-fail
  로 떨어지는 상태에서 새 잡 제출을 받지 않는 게 깔끔하다.

readiness 에 들어가지 않는 의존성:

- **DB connection 일시 단절** — HikariCP 가 자체 retry 로 흡수한다. 잠시 끊긴 정도로
  unready 를 publish 하면 모든 Pod 가 동시에 unready 가 되어 트래픽이 끊긴다. 회복
  후 다시 ready 로 돌아오는 churn (잦은 전환) 이 반복된다.
- **Redis 일시 끊김** — Redis 는 cache. cache miss 로 떨어져도 path 가 살아 있다.

### 5) startup 의 시간값

```yaml
startupProbe:
  initialDelaySeconds: 10
  periodSeconds: 5
  failureThreshold: 30   # 최대 ~150s
  timeoutSeconds: 3
```

Flyway 가 새 마이그레이션 적용 + Hibernate metadata + JIT warmup 까지 합쳐 보통 30~60초.
150초 여유면 넉넉하다. startup 이 끝나야 livenessProbe / readinessProbe 가 활성화되므로,
느린 시작 때문에 livenessProbe 가 조기 fail 시키지 않게 보호하는 효과가 있다.

### 6) 의존성 회복 시 자동 ready 복귀

`ApplicationReadinessCoordinator` 의 `recomputeReadiness()` 가 회로의 모든 state
transition 마다 호출된다. OPEN → HALF_OPEN → CLOSED 가 일어나면 자동으로 ready 로
복귀한다. 운영자가 수동으로 ready 를 publish 할 일은 없다.

## 왜 over-spec 아닌가

K8s 위에서 동작하는 서비스라면 probe 3종이 명시돼 있어야 한다. 갖추지 않으면 위 시나리오
1~3 중 하나가 반드시 일어난다 — 시간 문제일 뿐이다.

- liveness 가 DB ping 을 보면 → 시나리오 1 (DB 일시 장애 → 전체 재시작이 연쇄적으로 발생).
- probe 가 한 개면 → 시나리오 2 (느린 시작 → CrashLoopBackOff).
- probe 가 0 개면 → 시나리오 3 (JVM deadlock → 100% 5xx).

추가 코드 ~150 라인 + yml ~20 라인 + 단위 테스트 ~150 라인. 위 시나리오 한 건만
예방해도 충분히 회수된다.

## 다시 검토할 시점

- service mesh (Istio / Linkerd) 도입 시 — readiness 신호가 mesh 의 outlier
  detection 과 어떻게 연동되는지 정리 필요.
- 다중 region 배포 시 — region 별 readiness (이 region 의 DB 가 fail 인지) 를
  구분해 글로벌 LB 가 region 을 빼는 패턴.
- circuit 외에도 다른 critical 외부 의존성이 추가되면 (예: external auth
  provider) `ApplicationReadinessCoordinator` 에 신호 source 추가.

## 용어 풀이 (쉽게)

- **probe (탐침) — liveness / readiness / startup** — K8s가 Pod 상태를 묻는 세 질문. liveness "살아 있니?"(죽었으면 재시작), readiness "손님 받을 준비 됐니?"(아니면 트래픽만 잠시 끊음), startup "아직 켜지는 중이니?"(부팅 동안 앞 둘을 잠재움).
- **CrashLoopBackOff** — Pod가 켜지자마자 죽고 재시작하길 무한 반복하는 상태. 시동이 안 걸려 계속 끄고 켜기만 하는 차 같은 모습.
- **churn (잦은 깜빡임)** — 준비됨↔준비안됨이 짧은 시간에 반복돼 트래픽이 들락날락하는 불안정. 일시적 DB 끊김을 readiness에 넣으면 모든 Pod가 같이 깜빡인다.
- **circuit OPEN (회로 차단)** — 외부 호출이 계속 실패하면 두꺼비집처럼 잠시 회선을 끊어 즉시 실패시키는 상태. 여기선 이 신호를 readiness 판단에 쓴다.

## 참고 자료

- Kubernetes documentation — Pod Lifecycle / Probes
  https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#container-probes
- Spring Boot 3 Application Availability
  https://docs.spring.io/spring-boot/docs/3.3.x/reference/html/features.html#features.spring-application.application-availability
- ADR-0024 (graceful shutdown) — readiness 를 unready 로 바꾸는 시점이 SIGTERM 후
  가장 먼저 일어나야 하는 이유.
- ADR-0025 (retry + circuit breaker) — readiness 신호 source 인 회로의 동작.
