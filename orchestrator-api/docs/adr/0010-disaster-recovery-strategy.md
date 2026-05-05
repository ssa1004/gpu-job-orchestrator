# ADR-0010: Disaster Recovery 전략 — Velero + 시간별 백업

## 상태
적용

## 배경

운영 중 일어날 수 있는 데이터 손실 시나리오:

1. **Postgres PVC 손상** (디스크 장애, 실수 truncate)
2. **K8s 클러스터 자체 손상** (control plane 장애, namespace 잘못 삭제)
3. **Region 장애** (AZ 동시 장애 또는 region 이슈)
4. **Ransomware / 악성 변조** — 운영 데이터 무결성 손상

각 시나리오의 RPO 요구사항이 다르고, 단일 방법으로는 모두 커버 안 됨.

## 결정

3-tier 백업 전략:

### Tier 1: Postgres WAL archiving (RPO ≤ 1분)

- PostgreSQL `archive_mode=on` + `archive_command` 으로 WAL segment 를 S3 에 push
- PIT (point-in-time) 복원 가능
- 가장 작은 RPO 보장

### Tier 2: Velero 시간별 namespace 스냅샷 (RPO ≤ 1시간)

```yaml
schedule: "0 * * * *"   # 매시간 정각
includedNamespaces: [gwp]
labelSelector: {matchLabels: {app: postgres}}
ttl: 24h
```

빠른 복원 + namespace 단위 일관성.

### Tier 3: Velero 일일 전체 백업 (RPO ≤ 24시간)

매일 새벽 02:00 (KST) 전체 namespace + PV. 30일 보관. cross-region S3 replication
으로 region 장애 대비.

## 시나리오별 매핑

| 시나리오 | 사용 백업 | 예상 RTO | RPO |
|---|---|---|---|
| Postgres 손상 | Tier 2 (시간별) + Tier 1 (WAL) | 15분 | 1분 |
| 클러스터 손상 | Tier 2 + Tier 3 | 30분 | 1시간 |
| Region 장애 | Tier 3 (cross-region replicated) | 60분 | 24시간 |
| Ransomware | Tier 3 (immutable bucket) | 60분 | 24시간 |

목표 RTO 30분 / RPO 5분 의 근거.

## 결과

- 모든 시나리오에서 명확한 복구 절차 ([`docs/dr/dr-runbook.md`](../../../docs/dr/dr-runbook.md))
- 분기별 DR drill 로 RTO 실측 → runbook 업데이트
- (운영 비용) Velero 백업 + WAL archiving = 월 ~수십 USD (S3 비용 + cross-region 전송)
- (한계) Region 장애 시 60분 RTO 가 비즈니스 요구사항 대비 길 수 있음 → multi-region
  active-active 가 다음 단계 (cost 큰 폭 증가)
