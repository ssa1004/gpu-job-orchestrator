# GPU OOM / Job FAILED 폭증

OOM = Out Of Memory (메모리 부족). `gwp_orchestrator_jobs_completed_total{status="failed"}`
의 5분 비율이 평소 대비 3배 이상이거나, `DCGM_FI_DEV_FB_USED / total > 0.9` (DCGM = NVIDIA
GPU 메트릭 exporter, FB_USED = Frame Buffer 사용량 / 전체 비율) 알람이 GPU Pod OOMKilled
(메모리 부족으로 K8s 가 강제 종료한 Pod) 와 함께 발생하는 경우의 대응 절차입니다.

평상시 실패율은 0.5% 수준이며, 5% 를 초과하면 사용자 영향이 가시화됩니다.

## 1차 확인

실패 패턴을 확인합니다.

```sql
SELECT substr(error_message, 1, 80) AS error, count(*) AS n
FROM jobs
WHERE status = 'FAILED'
  AND finished_at > now() - interval '1 hour'
GROUP BY 1 ORDER BY 2 DESC LIMIT 10;
```

OOM 발생 여부:

```bash
kubectl get events -A --field-selector reason=OOMKilling --sort-by='.lastTimestamp' | tail
```

## 원인별 대응

### A. 새 이미지의 메모리 사용량 증가

배포 직후 발생한 경우 가장 의심스러운 원인입니다. 최근 30분 내 image tag 별 실패 분포를
확인합니다.

```sql
SELECT image, count(*) FROM jobs
WHERE status = 'FAILED'
  AND finished_at > now() - interval '30 minutes'
GROUP BY image ORDER BY 2 DESC;
```

문제 이미지가 식별되면 GitOps repo 에서 직전 stable (안정) 버전으로 ArgoCD 롤백합니다.
영향을 받은 사용자에게는 재제출을 안내합니다 (`Idempotency-Key` — 같은 요청이 두 번 와도
한 번만 처리되게 막는 헤더 — 도입 전이므로 중복 처리 위험이 존재합니다).

### B. 특정 사용자의 quota 우회

quota = 사용자별 동시 실행 작업 / GPU 한도. `gpuCount=N` 으로 모델 N개를 동시 실행하는
패턴 등이 의심됩니다.

```sql
SELECT owner, sum(gpu_count) AS total_gpus
FROM jobs
WHERE status IN ('RUNNING', 'DISPATCHING')
GROUP BY owner ORDER BY 2 DESC LIMIT 10;
```

해당 owner 의 quota 를 임시로 강하게 제한합니다.

```sql
UPDATE user_quotas
SET max_concurrent_jobs = 1, max_gpu_count = 1
WHERE owner = '<owner>';
```

### C. GPU 노드 풀 부족

Cluster Autoscaler (노드 자체 개수를 자동 조절하는 K8s 컴포넌트) 의 확장이 정상 동작
하지 않는 경우 ASG max (Auto Scaling Group 의 최대 인스턴스 수 — AWS) 또는 AWS quota
(서비스 한도) 를 확인합니다.

```bash
kubectl get nodes -l nvidia.com/gpu.present=true \
  -o custom-columns='NAME:.metadata.name,ALLOCATABLE_GPU:.status.allocatable.nvidia\.com/gpu' \
  | column -t
```

### D. 드라이버 / CUDA 버전 불일치

`error_message` 가 `CUDA error: ...` 패턴인 경우 노드 차원의 문제입니다. DCGM (NVIDIA
GPU 메트릭 도구) 의 `XID_ERRORS` (NVIDIA 드라이버가 보고하는 GPU 하드웨어 / 드라이버
오류 카운터) 메트릭을 함께 확인하고, 해당 노드를 cordon (새 Pod 스케줄 차단) + drain
(기존 Pod 들을 다른 노드로 옮김) 한 뒤 ansible 로 GPU driver 를 재설치합니다.

```bash
kubectl cordon <node>
kubectl drain <node> --ignore-daemonsets --delete-emptydir-data
ansible-playbook -i inventories/onprem/hosts.yml playbooks/gpu-setup.yml --limit <node>
kubectl uncordon <node>
```

## 영향

비용 측면의 손실이 가장 큽니다. Job 이 실패하더라도 시작까지 소모된 GPU 시간은 청구됩니다.
사용자 측에서는 result_url 미발급으로 인해 client retry 가 발생하는데, 동일한 OOM 으로
재실패할 가능성이 높습니다.

## 후속 조치

- 평상시 실패율 (~0.5%) 로 회복되는지 확인합니다.
- 영향 범위 (영향받은 owner 수, Job 수) 를 incident 보고서에 기록합니다.
- 동일한 패턴이 두 번 연속 발생하는 경우 quota 정책 또는 이미지 검증 절차의 개선을 검토합니다.
