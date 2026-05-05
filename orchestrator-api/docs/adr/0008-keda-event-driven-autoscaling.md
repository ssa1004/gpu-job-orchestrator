# ADR-0008: KEDA event-driven worker autoscaling

## 상태
적용

## 배경

GPU 워커는 정상적인 traffic 패턴이 매우 들쑥날쑥하다. 새벽 idle 시간 + 낮 burst.
일반 HPA (CPU/메모리 기반) 은 이런 spiky workload 에 부적합:

- HPA 의 메트릭 (CPU 사용률) 이 워커 입장에선 거의 무의미. 워커는 GPU 작업 중 CPU 50%
  쓰면 충분하므로 항상 같은 값
- queue depth 가 진짜 신호. Kafka topic 의 lag 또는 Job 카운트 기반 scaling 필요
- 0 으로 scale-down 가능해야 GPU 비용 절감 (HPA 는 minReplicas=1 권장이라 부적합)

## 결정

KEDA `ScaledJob` 사용. trigger 두 개:

1. **Kafka job_queue topic lag** (primary) — 1 lag = 1 worker Pod 생성
2. **Prometheus `gwp_orchestrator_jobs_in_state{status=QUEUED}`** (보조) — Kafka 측 lag
   값이 일시적으로 잘못 측정되는 경우 fallback

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledJob
spec:
  minReplicaCount: 0       # 작업 없으면 GPU Pod 0
  maxReplicaCount: 50      # autoscaler 한도와 일치
  pollingInterval: 5
  scalingStrategy:
    strategy: "accurate"   # over-provisioning 방지
  triggers:
    - type: kafka
      metadata:
        topic: gwp.job.queue
        lagThreshold: "1"
    - type: prometheus
      metadata:
        query: sum(gwp_orchestrator_jobs_in_state{status="QUEUED"})
        threshold: "1"
```

ScaledObject (Deployment) 가 아닌 ScaledJob 인 이유:

- GPU 작업은 작업당 Pod 1 개가 자연스러움 (배치 처리 X)
- 작업이 끝난 Pod 는 즉시 사라져야 함 (Pod 재사용 X — 메모리 leak 회피)
- K8s Job 의 `backoffLimit: 0` + orchestrator-api 의 retry 정책으로 깔끔히 분리

## 결과

- Idle 시간에 GPU Pod = 0 → 비용 절감
- burst 시 5초 polling 으로 빠른 scale-up (Kubernetes Job 생성)
- Cluster Autoscaler 의 priority expander 와 결합해 spot GPU 노드 우선 사용
- (한계) Kafka lag 측정에 약간의 지연 (5초 polling) — 더 빠른 반응 필요 시 KEDA push 모드
  검토
- (한계) maxReplicaCount 가 cluster GPU 수 초과 시 일부 Pod 가 Pending → CA 가 노드 추가
  trigger
