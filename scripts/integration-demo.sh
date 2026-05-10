#!/usr/bin/env bash
# Portfolio set 통합 시연 — 외부 의존 0, 컨테이너 안에서 닫힘 검증.
#
# 전제: 다음이 띄워져 있어야 함.
#   docker compose -f infrastructure/docker/docker-compose.integration.yml up -d --build
#
# 시나리오:
#   0. 모든 서비스 health 확인
#   1. auth-stub 에서 access_token 발급 (mock JWT)
#   2. 토큰으로 본 레포에 단순 job 제출 → 워커 콜백 시뮬레이션 (RUNNING → SUCCEEDED)
#   3. notification-stub 가 gwp.job.jobsubmitted / jobcompleted 양쪽을 받았는지 검증
#   4. billing-stub 가 cost 까지 lookup 해서 ledger 에 적재했는지 검증
#   5. DAG 시연 — parent 2 + child 1, parent 들 SUCCEEDED → child 자동 진행
#
# 본 스크립트는 멱등 호출을 가정하지 않는다 — `docker compose down -v` 후 다시 실행 권장.
# 두 번째 실행은 stub 들이 이전 누적분을 그대로 가지고 있어 카운트 검증이 어긋난다.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/infrastructure/docker/docker-compose.integration.yml}"

ORCH_BASE="${ORCH_BASE:-http://localhost:8080}"
AUTH_BASE="${AUTH_BASE:-http://localhost:18080}"
NOTIF_BASE="${NOTIF_BASE:-http://localhost:18081}"
BILL_BASE="${BILL_BASE:-http://localhost:18082}"
CALLBACK_SECRET="${CALLBACK_SECRET:-dev-secret-change-me}"
DEMO_OWNER="${DEMO_OWNER:-demo-user}"

say() { printf "\n\033[1;36m%s\033[0m\n" "$*"; }
out() { printf "  \033[2m%s\033[0m\n" "$*"; }
fail() { printf "\n\033[1;31mFAIL: %s\033[0m\n" "$*" >&2; exit 1; }

require() {
    command -v "$1" >/dev/null || { printf "%s 가 PATH 에 필요합니다.\n" "$1" >&2; exit 2; }
}
require curl
require jq

# ---- 0. 헬스 체크 ---------------------------------------------------------
say "0. 모든 서비스 health 확인"

wait_until() {
    # $1=label $2=url
    local label="$1" url="$2" attempts=60
    for i in $(seq 1 $attempts); do
        if curl -sSf -o /dev/null "$url"; then
            out "$label OK"
            return 0
        fi
        sleep 1
    done
    fail "$label not ready: $url (${attempts}s timeout)"
}

wait_until "auth-stub"          "$AUTH_BASE/oauth2/jwks"
wait_until "orchestrator-api"   "$ORCH_BASE/actuator/health/readiness"
wait_until "notification-stub"  "$NOTIF_BASE/health"
wait_until "billing-stub"       "$BILL_BASE/health"

# ---- 1. mock JWT 발급 -----------------------------------------------------
say "1. auth-stub 에서 access_token 발급 (sub=$DEMO_OWNER, scope=jobs.write jobs.read)"
TOKEN_BODY=$(curl -sSf -X POST "$AUTH_BASE/oauth2/token" \
    --data-urlencode "sub=$DEMO_OWNER" \
    --data-urlencode "scope=jobs.write jobs.read" \
    --data-urlencode "ttl=600")
TOKEN=$(echo "$TOKEN_BODY" | jq -r '.access_token')
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || fail "토큰 발급 실패: $TOKEN_BODY"
out "토큰 발급 OK (앞 24자: ${TOKEN:0:24}...)"

AUTH_H="Authorization: Bearer $TOKEN"

# 401 검증 — 토큰 없이 호출하면 거절되는지 (JWT enabled 모드 동작 확인)
say "1.1 토큰 없이 호출 → 401 거절 확인"
status=$(curl -s -o /dev/null -w '%{http_code}' "$ORCH_BASE/api/v1/jobs?owner=$DEMO_OWNER&page=0&size=1")
[[ "$status" == "401" ]] || fail "JWT enabled 모드인데 anon 호출이 $status (401 기대)"
out "401 거절 OK"

# ---- 2. job 제출 + 콜백 ---------------------------------------------------
say "2. job 제출 (owner=$DEMO_OWNER, gpuCount=2, priority=HIGH)"
SUBMIT=$(curl -sSf -X POST "$ORCH_BASE/api/v1/jobs" \
    -H 'Content-Type: application/json' -H "$AUTH_H" \
    -d '{"inputUri":"s3://demo/in.bin","image":"engine:1.0","gpuCount":2,"priority":"HIGH"}')
JOB_ID=$(echo "$SUBMIT" | jq -r .id)
[[ -n "$JOB_ID" && "$JOB_ID" != "null" ]] || fail "제출 실패: $SUBMIT"
out "JOB_ID=$JOB_ID"

say "2.1 워커 콜백 시뮬레이션 — RUNNING → SUCCEEDED (실제 K8s 대신 데모 스크립트가 직접 발사)"
curl -sSf -X POST "$ORCH_BASE/internal/jobs/$JOB_ID/status" \
    -H 'Content-Type: application/json' \
    -H "X-GWP-Callback-Secret: $CALLBACK_SECRET" \
    -d '{"status":"RUNNING"}' >/dev/null
out "RUNNING 콜백 송신"
sleep 1
curl -sSf -X POST "$ORCH_BASE/internal/jobs/$JOB_ID/status" \
    -H 'Content-Type: application/json' \
    -H "X-GWP-Callback-Secret: $CALLBACK_SECRET" \
    -d '{"status":"SUCCEEDED","resultUri":"s3://demo/out.bin"}' >/dev/null
out "SUCCEEDED 콜백 송신"

# ---- 3. notification-stub 검증 -------------------------------------------
say "3. notification-stub 가 JobSubmitted + JobCompleted 양쪽을 받았는지"
poll_until_received() {
    # $1=topic $2=expected_min_count $3=label
    local topic="$1" expected="$2" label="$3" attempts=30
    for i in $(seq 1 $attempts); do
        local count
        count=$(curl -sSf "$NOTIF_BASE/received/$topic" | jq '.count // 0')
        if [[ "$count" -ge "$expected" ]]; then
            out "$label OK (count=$count)"
            return 0
        fi
        sleep 1
    done
    out "$label TIMEOUT — 마지막 응답:"
    curl -sS "$NOTIF_BASE/received/$topic" | jq .
    fail "$label 가 ${attempts}s 안에 도달 못함"
}
poll_until_received "gwp.job.jobsubmitted" 1 "JobSubmitted 수신"
poll_until_received "gwp.job.jobcompleted" 1 "JobCompleted 수신"

# ---- 4. billing-stub 검증 -------------------------------------------------
say "4. billing-stub 가 cost lookup 후 ledger 에 적재했는지"
poll_until_ledger() {
    local job_id="$1" attempts=30
    for i in $(seq 1 $attempts); do
        local row
        row=$(curl -sS "$BILL_BASE/ledger/$job_id" || echo '{}')
        if [[ "$(echo "$row" | jq -r '.cost.computedCost // empty')" != "" ]]; then
            echo "$row" | jq '{jobId, status, finishedAt, cost: {gpuCount: .cost.gpuCount, runtimeMillis: .cost.runtimeMillis, ratePerGpuHour: .cost.ratePerGpuHour, computedCost: .cost.computedCost, currency: .cost.currency, finalStatus: .cost.finalStatus}}'
            return 0
        fi
        sleep 1
    done
    out "ledger 에 cost 가 안 박힘. 마지막 응답:"
    curl -sS "$BILL_BASE/ledger/$job_id" | jq .
    fail "billing-stub ledger 적재 실패 ($job_id)"
}
poll_until_ledger "$JOB_ID"

# ---- 5. DAG 시연 ----------------------------------------------------------
say "5. DAG 시연 — parent 2 + child 1, parent 들 SUCCEEDED → child 자동 진행"

submit() {
    local payload="$1"
    curl -sSf -X POST "$ORCH_BASE/api/v1/jobs" \
        -H 'Content-Type: application/json' -H "$AUTH_H" \
        -d "$payload" | jq -r .id
}
callback() {
    local id="$1" body="$2"
    curl -sSf -X POST "$ORCH_BASE/internal/jobs/$id/status" \
        -H 'Content-Type: application/json' \
        -H "X-GWP-Callback-Secret: $CALLBACK_SECRET" \
        -d "$body" >/dev/null
}

P1=$(submit '{"inputUri":"s3://demo/p1.bin","image":"preprocess:1.0","gpuCount":1,"priority":"NORMAL"}')
P2=$(submit '{"inputUri":"s3://demo/p2.bin","image":"preprocess:1.0","gpuCount":1,"priority":"NORMAL"}')
out "P1=$P1, P2=$P2"
CHILD=$(submit "{\"inputUri\":\"s3://demo/train.bin\",\"image\":\"train:1.0\",\"gpuCount\":2,\"priority\":\"HIGH\",\"parentJobIds\":[\"$P1\",\"$P2\"]}")
out "CHILD=$CHILD"
child_status=$(curl -sSf -H "$AUTH_H" "$ORCH_BASE/api/v1/jobs/$CHILD" | jq -r .status)
[[ "$child_status" == "WAITING_DEPS" ]] || fail "CHILD 가 WAITING_DEPS 가 아님: $child_status"
out "CHILD status = WAITING_DEPS (정상)"

callback "$P1" '{"status":"RUNNING"}'
callback "$P1" '{"status":"SUCCEEDED","resultUri":"s3://demo/p1.out"}'
callback "$P2" '{"status":"RUNNING"}'
callback "$P2" '{"status":"SUCCEEDED","resultUri":"s3://demo/p2.out"}'
sleep 2
child_after=$(curl -sSf -H "$AUTH_H" "$ORCH_BASE/api/v1/jobs/$CHILD" | jq -r .status)
out "parent 2 종료 후 CHILD status = $child_after"
[[ "$child_after" == "QUEUED" || "$child_after" == "DISPATCHING" || "$child_after" == "RUNNING" ]] \
    || fail "DependencyResolutionService 가 CHILD 를 진행 안 시킴 ($child_after)"

callback "$CHILD" '{"status":"RUNNING"}'
callback "$CHILD" '{"status":"SUCCEEDED","resultUri":"s3://demo/train.out"}'

# DAG 까지 합쳐 jobcompleted 4 건 (J + P1 + P2 + CHILD) 누적
poll_until_received "gwp.job.jobcompleted" 4 "JobCompleted 누적 (4건)"
poll_until_ledger "$CHILD"

# ---- 마무리 ---------------------------------------------------------------
say "통합 시연 완료. 더 보려면:"
out "  본 레포 Swagger      : $ORCH_BASE/swagger"
out "  notification-stub    : curl $NOTIF_BASE/received | jq '.counts'"
out "  billing-stub ledger  : curl $BILL_BASE/ledger | jq '{count, rows: [.rows[] | {jobId, status, cost: .cost.computedCost}]}'"
out "  컨테이너 로그        : docker compose -f $COMPOSE_FILE logs --tail=50 notification-stub billing-stub"
out "  정리                : docker compose -f $COMPOSE_FILE down -v"
