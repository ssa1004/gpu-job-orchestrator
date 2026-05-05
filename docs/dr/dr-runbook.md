# Disaster Recovery Runbook

## SLO

| 지표 | 목표 | 측정 방법 |
|---|---|---|
| **RTO** (Recovery Time Objective) | 30분 | 장애 인지 → 정상 트래픽 복귀까지 wall-clock |
| **RPO** (Recovery Point Objective) | 5분 | Postgres WAL archiving + 시간별 Velero 스냅샷 기준 |

분기별 DR drill 에서 실측 → 본 runbook 의 "검증 결과" 표에 기록.

## 시나리오 분류

| 시나리오 | 빈도 | 대응 절차 |
|---|---|---|
| **A. 단일 Pod 장애** | 일상 | K8s 자동 재시작 + PDB 보호. 별도 액션 불필요 |
| **B. 단일 노드 장애** | 월 1~2회 | Cluster Autoscaler 자동 회복. 모니터링만 |
| **C. AZ 단위 장애** | 분기 1회 가정 | Multi-AZ 배포로 자동 회복. AZ 재배치 확인 |
| **D. Region 단위 장애 (DR)** | 매우 드뭄 | 본 runbook 의 핵심 — 수동 failover |
| **E. 데이터 손상 (악성/실수 삭제)** | 드뭄 | Velero PIT restore |

## D 시나리오: Region 단위 Failover

### 단계 1: 장애 인지 (5분 이내)

- AlertManager 의 `RegionDown` 알람 (multi-region 외부 health check 기반)
- 또는 운영자가 수동 판단

### 단계 2: DR 클러스터 활성화 (10분)

```bash
# Terraform 으로 DR 환경의 EKS 가 standby 로 떠있다고 가정
cd infrastructure/terraform/environments/dr
terraform apply -var="active=true" -auto-approve
```

대안: Always-on DR 클러스터 (cost 더 들지만 시간 단축).

### 단계 3: Velero 로 백업 복원 (10분)

```bash
# 가장 최근 백업 확인
velero backup get | head

# 복원 트리거
velero restore create dr-failover-$(date +%Y%m%d-%H%M) \
  --from-backup gwp-postgres-hourly-$(latest_id) \
  --include-namespaces gwp,gwp-jobs \
  --restore-volumes \
  --wait
```

### 단계 4: DNS 전환 (5분)

```bash
# Route53 weighted routing 또는 health-check 기반 자동 failover
# 수동 트리거 시:
aws route53 change-resource-record-sets \
  --hosted-zone-id Z123456 \
  --change-batch file://failover-to-dr.json
```

### 단계 5: 검증

| 체크 | 명령 / 도구 |
|---|---|
| Pod 상태 | `kubectl -n gwp get pods` |
| API 응답 | `curl https://api.gwp.example.com/actuator/health` |
| DB 정합성 | `SELECT count(*) FROM jobs WHERE updated_at > now() - interval '1 hour'` |
| Outbox 잔여 | `SELECT count(*) FROM outbox WHERE published_at IS NULL` |
| Job 처리 | 테스트 Job 1건 제출 → SUCCEEDED 확인 |

총 **30분 이내** 완료가 목표.

## E 시나리오: 데이터 손상 PIT 복원

```bash
# Postgres WAL archiving 사용 시 임의 시점 복원 (point-in-time)
# (Velero 시간별 스냅샷 + WAL replay)

# 1. 손상 시점 식별 (audit log, application log 기반)
# 2. 손상 직전의 가장 가까운 백업 복원
velero restore create pit-restore-$(date +%Y%m%d) \
  --from-backup gwp-postgres-hourly-<before-incident> \
  --include-resources persistentvolumeclaims,persistentvolumes \
  --selector "app=postgres" \
  --wait

# 3. 복원 후 손상 데이터를 application 레벨로 재현하지 않게 검증
```

## 검증 결과 (분기별 갱신)

| 일자 | 시나리오 | RTO 실측 | RPO 실측 | 비고 |
|---|---|---|---|---|
| 2026-Q2 | D (region failover) | TBD | TBD | drill 예정 |

## 사후 회고 템플릿

장애 또는 drill 후 작성:

```
일자: 2026-MM-DD
시나리오: [A-E 중 어느 것]
인지 시각: HH:MM
복구 시각: HH:MM
RTO 실측: NN분
RPO 실측: NN분 (마지막 백업 시점 기준)

타임라인:
  HH:MM - 장애 발생
  HH:MM - 알람 수신
  HH:MM - on-call 응답
  ...

근본 원인:

후속 조치:
  - [ ] (개선 항목)

(다음 drill 일정)
```
