# 0007 — Clock 주입 + UTC 일관성

- **Status:** Accepted
- **Date:** 2026-05-04 (refactor)

## Context

초기 구현은 도메인 곳곳에서 `Instant.now()` 직접 호출.

문제:
1. **테스트 결정성 부재** — `Instant.now()` 가 호출 시점마다 다른 값. timestamp 검증 어려움.
2. **시간대 불일치 위험** — `Instant.now()` 는 UTC 기준이지만, JVM 또는 컨테이너 시간대 설정에 따른
   부수효과 가능 (`Date`, `LocalDateTime` 등이 섞이면 위험).

## Decision

`java.time.Clock` 빈을 `ClockConfig` 에서 정의 (`Clock.systemUTC()`) 하고 도메인/서비스에서 주입.

```java
@Configuration
public class ClockConfig {
    @Bean public Clock clock() { return Clock.systemUTC(); }
}
```

도메인 메서드는 Clock 을 명시적으로 받음:
```java
public void markRunning(Clock clock) {
    this.startedAt = clock.instant();
    ...
}
```

서비스는 생성자 주입:
```java
@RequiredArgsConstructor
public class JobLifecycleService {
    private final Clock clock;
    ...
}
```

## Consequences

### 긍정
- **테스트 결정성** — 단위 테스트에서 `Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), UTC)` 주입으로
  timestamp 검증 가능. 모든 도메인 단위 테스트가 이 패턴 사용.
- **시간대 단일 진실원** — 모든 `clock.instant()` 호출은 UTC. DB 의 `hibernate.jdbc.time_zone=UTC` 와 일치.
- **명시성** — 메서드 시그니처에 `Clock` 이 보이므로 시간 의존성이 코드 리뷰에서 가시화.
- **테스트 슬립 / TimeWindow 시뮬레이션 용이** — 향후 timeout watcher 같은 시간 기반 컴포넌트 추가 시
  `Clock.offset(...)` 으로 시간 조작 가능.

### 부정
- 메서드 시그니처가 약간 길어짐 (`markRunning()` → `markRunning(clock)`)
- Java 8 이전 코드 스타일에 익숙한 리뷰어에게 잠시 낯설 수 있음

### 대안
- **`Instant.now()` 그대로 + Mockito.mockStatic** — 가능하지만 mock 정리 복잡, 동시성 테스트에서 누수 위험
- **자체 `TimeProvider` 인터페이스** — 표준 `Clock` 이 이미 같은 역할 수행. 차별점 없음
