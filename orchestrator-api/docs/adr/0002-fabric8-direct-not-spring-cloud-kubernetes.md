# 0002 — fabric8 직접 호출 (vs Spring Cloud Kubernetes)

- **Status:** Accepted
- **Date:** 2026-04-22

## Context

오케스트레이터는 K8s 의 `batch/v1` Job 리소스를 생성/삭제해야 한다. Java 에서 K8s API 를 다루는 옵션:

1. **fabric8 kubernetes-client** — 풀 기능 클라이언트, fluent builder, in-cluster auth 자동
2. **공식 io.kubernetes:client-java** — Kubernetes 프로젝트 공식, OpenAPI 기반 자동 생성, swagger 같은 사용감
3. **Spring Cloud Kubernetes** — Spring 추상화, ConfigMap/Secret 바인딩, leader election 등 보조 기능 제공. K8s API 직접 호출은 fabric8/공식 클라이언트로 위임

## Decision

fabric8 kubernetes-client 를 직접 사용한다. Spring Cloud Kubernetes 는 도입하지 않는다.

## Consequences

### 긍정
- 코드가 K8s API 와 1:1 매핑 → 매뉴얼 ([Job spec](https://kubernetes.io/docs/concepts/workloads/controllers/job/)) 만 보면 동작 추론 가능
- fluent builder 로 가독성 좋음 (`new JobBuilder().withNewMetadata()...`)
- Spring Cloud Kubernetes 의 추상화 (예: `@Refreshable` ConfigMap) 는 우리 use case 와 무관
- 의존성 트리가 가벼움 (Spring Cloud BOM 미도입)

### 부정
- ConfigMap/Secret 자동 바인딩, leader election 같은 보조 기능을 직접 만들어야 함 → 현재 우리 단일 leader 가 필요한 워크로드 없음 (orchestrator 자체는 stateless 수평 확장)
- fabric8 의 메이저 버전 업그레이드 시 API 호환성 깨짐 → 변환 비용이 우리 책임

### 대안
- **공식 client-java**: 자동 생성 코드라 fluent builder 부재, 사용감이 떨어짐. fabric8 이 커뮤니티에서 더 활발
