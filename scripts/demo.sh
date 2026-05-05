#!/usr/bin/env bash
# 30초 데모 — orchestrator-api 가 이미 :8080 에서 돌고 있어야 함
# (다른 터미널에서 `cd orchestrator-api && ./gradlew bootRun` 실행)
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
SECRET="${GWP_CALLBACK_SECRET:-dev-secret-change-me}"

say() { printf "\n\033[1;36m▶ %s\033[0m\n" "$*"; }
out() { printf "  \033[2m%s\033[0m\n" "$*"; }

say "1. health 체크"
curl -s "$BASE/actuator/health/readiness" | jq -r '.status' | xargs -I{} out "readiness = {}"

say "2. Job 제출 (priority=HIGH)"
SUBMIT=$(curl -s -X POST "$BASE/api/v1/jobs" \
  -H 'Content-Type: application/json' \
  -d '{"inputUri":"s3://demo/input.bin","image":"engine:1.0","gpuCount":2,"priority":"HIGH"}')
echo "$SUBMIT" | jq
JOB_ID=$(echo "$SUBMIT" | jq -r .id)
out "JOB_ID=$JOB_ID"

say "3. 단건 조회 (Redis cache-aside 첫 호출 → DB hit)"
curl -s "$BASE/api/v1/jobs/$JOB_ID" | jq '{id, status, priority, gpuCount}'

say "4. 다시 조회 (cache hit — 응답 동일, 로그 보면 DB 안 감)"
curl -s "$BASE/api/v1/jobs/$JOB_ID" | jq '{id, status, priority}'

say "5. 워커 콜백 시뮬레이션 — RUNNING"
curl -s -X POST "$BASE/internal/jobs/$JOB_ID/status" \
  -H 'Content-Type: application/json' \
  -H "X-GWP-Callback-Secret: $SECRET" \
  -d '{"status":"RUNNING"}' | jq '{status, startedAt}'

say "6. 워커 콜백 — SUCCEEDED + 결과 URI"
curl -s -X POST "$BASE/internal/jobs/$JOB_ID/status" \
  -H 'Content-Type: application/json' \
  -H "X-GWP-Callback-Secret: $SECRET" \
  -d '{"status":"SUCCEEDED","resultUri":"s3://demo/result.bin"}' | jq '{status, resultUri, finishedAt}'

say "7. 결과 Presigned URL"
curl -s "$BASE/api/v1/jobs/$JOB_ID/result-url" | jq

say "8. 페이징 조회 (anonymous owner — Permissive 모드)"
curl -s "$BASE/api/v1/jobs?owner=anonymous&page=0&size=5" \
  | jq '{totalElements, content: [.content[] | {id, status, priority}]}'

say "9. 메트릭 (Prometheus)"
curl -s "$BASE/actuator/prometheus" | grep -E 'gwp_orchestrator_jobs_(submitted|completed)' | head

say "10. 잘못된 요청 → 400 + 에러 코드"
curl -s -X POST "$BASE/api/v1/jobs" \
  -H 'Content-Type: application/json' \
  -d '{"inputUri":"not-an-s3-uri","image":"","gpuCount":99}' | jq '{code, details}'

say "완료. Swagger UI: $BASE/swagger"
