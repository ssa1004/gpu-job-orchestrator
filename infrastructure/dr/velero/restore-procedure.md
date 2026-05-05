# Velero Restore 절차

## 백업 목록 확인

```bash
velero backup get
# NAME                       STATUS      ERRORS   STORAGE LOCATION   CREATED
# gwp-daily-20260601020000   Completed   0        aws-s3-default     2026-06-01 02:00:00 +0900
# gwp-daily-20260531020000   Completed   0        aws-s3-default     2026-05-31 02:00:00 +0900
```

## 일반 시나리오: namespace 전체 복원

```bash
velero restore create gwp-restore-$(date +%Y%m%d-%H%M%S) \
  --from-backup gwp-daily-20260601020000 \
  --include-namespaces gwp,gwp-jobs \
  --restore-volumes \
  --wait
```

## Postgres 단독 복원 (PVC + 데이터)

```bash
# Postgres pod 만 일단 stop (PVC 충돌 방지)
kubectl scale deploy/postgres -n gwp --replicas=0

# PVC 만 복원
velero restore create postgres-restore-$(date +%Y%m%d) \
  --from-backup gwp-postgres-hourly-20260601150000 \
  --include-resources persistentvolumeclaims,persistentvolumes \
  --selector "app=postgres" \
  --wait

# Postgres 다시 시작
kubectl scale deploy/postgres -n gwp --replicas=1
kubectl rollout status deploy/postgres -n gwp
```

## 다른 클러스터로 복원 (DR 시나리오)

```bash
# DR 클러스터에 Velero 설치 + 같은 BackupStorageLocation 등록
# (백업이 보관된 S3 bucket 이 read-accessible 해야 함)

# 백업 동기화 (DR 클러스터 측 Velero)
velero backup-location create aws-s3-default \
  --provider aws --bucket gwp-velero-backups --prefix prod \
  --config region=ap-northeast-2

# 복원
velero restore create dr-failover-$(date +%Y%m%d) \
  --from-backup gwp-daily-20260601020000 \
  --restore-volumes
```

## Restore 후 검증

1. Pod 상태: `kubectl -n gwp get pods` — 모두 Running
2. DB 정합성: orchestrator-api 가 `/actuator/health/db` Green 응답
3. 데이터 확인: 최근 Job 1건 조회 — `curl ... /api/v1/jobs/{id}`
4. Outbox 잔여 확인: `SELECT count(*) FROM outbox WHERE published_at IS NULL` — 백업 시점
   미발행 이벤트가 다시 발행될 것 (idempotent 보장)

## RTO / RPO 측정

- **RTO 목표**: 30분 (백업 위치 → 신규 클러스터 복원 완료까지)
- **RPO 목표**: 5분 (시간별 postgres 백업 + WAL archiving 으로 보강 시)

실측은 분기별 DR drill 에서 갱신 ([`docs/dr/dr-runbook.md`](../../../docs/dr/dr-runbook.md) 참고).
