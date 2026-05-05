# JobCallbackMissing

RUNNING 상태로 30분 이상 머무는 Job 이 5건을 초과하면 발화하는 알림입니다. 워커가 콜백을
보냈지만 수신되지 않았거나, 워커 자체가 비정상 종료된 경우가 일반적입니다. PagerDuty 호출
까지는 가지 않는 warning 수준입니다.

```promql
sum(gwp_orchestrator_jobs_in_state{status="RUNNING", age_bucket="gt_30m"}) > 5
```

알람보다 대시보드의 "Stale RUNNING jobs" 패널에서 먼저 발견되는 경우가 많습니다.

## 1차 확인

장시간 RUNNING 상태인 Job 을 조회합니다.

```sql
SELECT id, owner, k8s_job_name, started_at, now() - started_at AS age
FROM jobs
WHERE status = 'RUNNING'
  AND started_at < now() - interval '30 minutes'
ORDER BY started_at LIMIT 20;
```

K8s 측 Job 의 실제 상태를 확인합니다.

```bash
kubectl -n gwp-jobs get job <k8s_job_name> -o jsonpath='{.status}' | jq
kubectl -n gwp-jobs logs job/<k8s_job_name> --tail=100
```

## 원인별 대응

### K8s 는 완료되었으나 DB 는 RUNNING 상태인 경우

K8s status.conditions 에 `Complete: true` 인 경우, 워커가 콜백을 보냈지만 수신에 실패한
케이스입니다. 수동으로 콜백을 재현하여 DB 를 동기화합니다.

```bash
JOB_ID="<jobId>"
RESULT_URI="<from K8s job logs>"

curl -X POST http://orchestrator-api.gwp/internal/jobs/$JOB_ID/status \
  -H "X-GWP-Callback-Secret: $CALLBACK_SECRET" \
  -H 'Content-Type: application/json' \
  -d "{\"status\":\"SUCCEEDED\",\"resultUri\":\"$RESULT_URI\"}"
```

K8s 측에서 Failed 로 종료된 경우 (Pod OOMKilled 등) [gpu-oom.md](gpu-oom.md) 절차로 진행합니다.
동일한 방식으로 FAILED 콜백을 수동 전송하여 동기화할 수 있습니다.

### 워커와 API 간 네트워크 단절

워커 Pod 에서 API 호출이 가능한지 확인합니다.

```bash
kubectl -n gwp-jobs exec <worker-pod> -- \
  curl -sv http://orchestrator-api.gwp.svc.cluster.local:8080/actuator/health
```

NetworkPolicy 변경 직후 자주 발생하는 시나리오입니다.
[`network-policy.yaml`](../../orchestrator-api/k8s/security/network-policy.yaml) 의 ingress
규칙에서 `gwp-jobs` namespace 트래픽이 허용되어 있는지 확인합니다.

### 콜백 시크릿 불일치

워커 로그에 `401 Unauthorized` 가 다수 발생하는 경우입니다. 양쪽 Secret 의 일치 여부를
확인하면 즉시 해결됩니다.

## 장기 개선 방향

이 알람이 월 2회 이상 발생한다면 단발성 대응으로는 부족합니다. 다음 중 하나의 개선이
필요합니다.

- 콜백 인증 방식을 공유 시크릿에서 mTLS 로 전환 (ADR-0003 의 follow-up 항목)
- timeout 감시 작업 추가. Spring Scheduling + ShedLock 으로 RUNNING 상태가 (timeout × 1.5)
  를 초과하면 K8s 상태를 자동으로 동기화
- 워커 측에서 콜백 실패 시 지수 백오프 retry 적용. 영구 실패는 dead-letter 디렉터리에 보관
