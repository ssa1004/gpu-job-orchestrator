# 0006 — 도메인 서비스 4-way 분리 + Cache self-invocation 해결

- **Status:** Accepted
- **Date:** 2026-05-04 (refactor)

## Context

초기 구현은 단일 `JobService` (190 lines, 7 dependencies) 가 submit / cancel / callback /
get / list / resultUrl + ownership 검사까지 모두 담당했다. 두 가지 구체적 문제가 있었다.

1. **God service** — SRP (Single Responsibility Principle, 한 클래스는 한 가지 이유로만
   바뀌어야 한다는 원칙) 위반. JobService 를 수정하려면 7개 의존성 모두 영향. 단위 테스트
   setup 비용 ↑.
2. **Cache self-invocation 버그** — `getOwned(id, ...)` 가 같은 클래스의 `@Cacheable
   get(id)` 를 호출. Spring AOP (Aspect-Oriented Programming — `@Transactional`,
   `@Cacheable` 같은 부가 기능을 메서드 호출 앞뒤에 끼워 넣는 Spring 의 메커니즘) 는
   외부에서 프록시 객체를 거쳐 들어오는 호출만 가로챈다 — self-invocation (같은 클래스
   안에서 `this.method()` 로 부르는 호출) 은 프록시를 우회 → **`@Cacheable` 가 동작하지
   않음.** Redis cache-aside (캐시 → 미스 시 DB 조회 후 캐시 채움 패턴) 가 ADR 과
   README 에서 강조한 성능 패턴인데 실제로는 한 번도 캐시 hit 가 발생하지 않는
   silent bug (티 안 나는 버그).

## Decision

`JobService` 를 책임 단위로 4개 컴포넌트로 분리.

| Component | 책임 | 의존성 수 |
|---|---|---|
| `JobSubmissionService` | submit (쿼터 → DB → K8s 디스패치 → Outbox → 메트릭) | 7 |
| `JobLifecycleService` | updateStatusFromCallback / cancel | 5 |
| `JobQueryService` | get (`@Cacheable`) / list / resultUrl | 2 |
| `JobAccessControl` | ownership 검증 후 위 컴포넌트들에 위임 | 2 |

이 분리는 cache 버그도 동시에 해결한다 — `JobAccessControl.getOwned()` 가
`JobQueryService.get()` 을 호출할 때 **다른 빈** 의 메서드를 호출하므로 Spring AOP
프록시를 거친다 → `@Cacheable` 정상 동작.

## Consequences

### 긍정
- 각 서비스의 의존성/책임이 명확. 단위 테스트 setup 단순화 (5개 service 단위 테스트로 분리).
- Cache 가 실제로 동작 (특히 hot job 의 GET /jobs/{id} 요청)
- 새 책임 추가 시 (예: 통계 / 정리 작업) 어느 service 로 갈지 명확
- Controller 가 2~3 개 service 에 의존하지만 각자 단순한 메서드만 호출

### 부정
- 클래스 수 증가 (1 → 4)
- ownership 검사가 서비스가 아닌 `JobAccessControl` 에 분산. Controller 가 access vs raw 메서드를 구분해서 호출해야 함.

### 대안
- **단일 JobService 유지 + ApplicationContext 자기 참조 주입** (`@Lazy JobService self`):
  자기참조 주입으로 self-invocation 우회 가능하지만 코드가 어색해지고 god-service 문제는 그대로.
- **Spring `@CacheableMethod` proxy 별도 빈으로 만들기:** 기술적으로 가능하지만 책임 분리의 자연스러운 부산물로
  cache 가 동작하는 본 구조가 더 깨끗.

## 흐름

```
HTTP POST /jobs/{id}/cancel
        │
        ▼
JobController.cancel(...)              ← @AuthenticationPrincipal Jwt 로 owner 추출
        │
        ▼
JobAccessControl.cancelOwned(id, owner, isAdmin)
        │
        ├── jobQueryService.get(id)    ← AOP proxy 통과 → @Cacheable hit / miss
        │   ensureOwnership(...)
        │
        └── jobLifecycleService.cancel(id)   ← AOP proxy 통과 → @CacheEvict 적용
                │
                └── (트랜잭션 안에서 DB UPDATE + Outbox INSERT + 메트릭)
```

## 검증

`JobAccessControlTest` + `JobQueryServiceTest` (간접) — admin 우회, 다른 owner 거부, 캐시 hit 흐름 검증.
