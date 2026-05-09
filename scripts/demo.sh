#!/usr/bin/env bash
# 약 1분 도메인 데모 — orchestrator-api 가 :8080 에서 돌고 있어야 함
# (다른 터미널에서 `cd orchestrator-api && ./gradlew bootRun`)
#
# 다루는 영역:
#   1~10    기본 흐름 (제출 / 캐시 / 콜백 / 결과 URL / 메트릭 / 검증 실패)
#   11~13   Job DAG (parent → child WAITING_DEPS → 자동 진행 / cycle 거절)
#   14~16   Cost ledger (단가 박제 → owner 별 누적 → 단건 cost record)
#   17      OTel Baggage 자동 전파 (요청 헤더 → 로그·트레이스 라벨)
#   18      preemption / leader 흐름 안내
#
# Permissive 모드 한계: cost summary / preemption timeline 같은 admin 전용
# endpoint 는 anonymous 호출자에게 403. 본 데모는 owner 본인 view 만 호출.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
SECRET="${GWP_CALLBACK_SECRET:-dev-secret-change-me}"
# Permissive 모드의 caller.owner() — Spring Security 의 anonymous 토큰이 'anonymousUser'.
# JWT 모드면 sub 클레임이 그대로 owner. 환경변수로 덮어쓸 수 있음.
OWNER="${OWNER:-anonymousUser}"

say() { printf "\n\033[1;36m%s\033[0m\n" "$*"; }
out() { printf "  \033[2m%s\033[0m\n" "$*"; }

require() {
    command -v "$1" >/dev/null || { printf "%s 가 PATH 에 필요합니다.\n" "$1" >&2; exit 2; }
}
require curl
require jq

submit_simple() {
    # $1=image $2=gpuCount $3=priority [$4=parentJobIds JSON array]
    local image="$1" gpu="$2" priority="$3" parents="${4:-[]}"
    curl -s -X POST "$BASE/api/v1/jobs" \
        -H 'Content-Type: application/json' \
        -d "{\"inputUri\":\"s3://demo/in.bin\",\"image\":\"$image\",\"gpuCount\":$gpu,\"priority\":\"$priority\",\"parentJobIds\":$parents}"
}

callback() {
    # $1=jobId $2=status [$3=resultUri]
    local id="$1" status="$2" result="${3:-}"
    local body="{\"status\":\"$status\""
    [[ -n "$result" ]] && body+=",\"resultUri\":\"$result\""
    body+="}"
    curl -s -X POST "$BASE/internal/jobs/$id/status" \
        -H 'Content-Type: application/json' \
        -H "X-GWP-Callback-Secret: $SECRET" \
        -d "$body"
}

# ---- 0. 시작점 -----------------------------------------------------------
say "0. base health (readiness)"
out "readiness = $(curl -s "$BASE/actuator/health/readiness" | jq -r '.status')"

# ---- 1. 기본 제출 흐름 ---------------------------------------------------
say "1. Job 제출 (priority=HIGH, gpuCount=2)"
SUBMIT=$(submit_simple "engine:1.0" 2 HIGH)
echo "$SUBMIT" | jq
JOB_ID=$(echo "$SUBMIT" | jq -r .id)
out "JOB_ID=$JOB_ID"

say "2. 단건 조회 — Redis cache-aside 첫 호출 (DB hit)"
curl -s "$BASE/api/v1/jobs/$JOB_ID" | jq '{id, status, priority, gpuCount, owner}'

say "3. 다시 조회 — cache hit (응답 동일, 로그 보면 DB 안 감)"
curl -s "$BASE/api/v1/jobs/$JOB_ID" | jq '{id, status, priority}'

say "4. 워커 콜백 — RUNNING (상태 머신 QUEUED → DISPATCHING → RUNNING 중 RUNNING)"
callback "$JOB_ID" RUNNING | jq '{status, startedAt}'

say "5. 워커 콜백 — SUCCEEDED + 결과 URI"
callback "$JOB_ID" SUCCEEDED "s3://demo/result.bin" | jq '{status, resultUri, finishedAt}'

say "6. 결과 Presigned URL"
curl -s "$BASE/api/v1/jobs/$JOB_ID/result-url" | jq

# ---- 7~9. 페이징 / 메트릭 / 검증 -----------------------------------------
say "7. 페이징 조회 (owner=$OWNER)"
curl -s "$BASE/api/v1/jobs?owner=$OWNER&page=0&size=5" \
    | jq '{totalElements, content: [.content[] | {id, status, priority}]}'

say "8. Prometheus 메트릭 — 제출 / 완료 카운터"
curl -s "$BASE/actuator/prometheus" | grep -E '^gwp_orchestrator_jobs_(submitted|completed)' | head -5

say "9. 잘못된 요청 → 400 + 에러 코드"
curl -s -X POST "$BASE/api/v1/jobs" \
    -H 'Content-Type: application/json' \
    -d '{"inputUri":"not-an-s3-uri","image":"","gpuCount":99}' | jq '{code, message}'

say "10. 종료된 잡에 또 콜백 → 멱등 처리 (최종 상태 유지)"
callback "$JOB_ID" SUCCEEDED "s3://demo/result.bin" | jq '{status}'

# ---- 11~13. Job DAG (ADR-0015) -------------------------------------------
say "11. DAG: parent 잡 2 개 + child 잡 1 개 (child 는 WAITING_DEPS 로 시작)"
P1=$(submit_simple "preprocess:1.0" 1 NORMAL | jq -r .id)
P2=$(submit_simple "preprocess:1.0" 1 NORMAL | jq -r .id)
out "P1=$P1"
out "P2=$P2"
CHILD_RAW=$(submit_simple "train:1.0" 2 HIGH "[\"$P1\",\"$P2\"]")
CHILD=$(echo "$CHILD_RAW" | jq -r .id)
echo "$CHILD_RAW" | jq '{id, status, priority}'
out "CHILD=$CHILD (status 가 WAITING_DEPS 여야 정상)"

say "12. parent 들 SUCCEEDED → child 가 자동으로 QUEUED 로 전이"
callback "$P1" SUCCEEDED "s3://demo/p1.bin" >/dev/null
callback "$P2" SUCCEEDED "s3://demo/p2.bin" >/dev/null
# DependencyResolutionService 의 후속 흐름이 비동기일 수 있어 잠시 대기
sleep 1
curl -s "$BASE/api/v1/jobs/$CHILD" | jq '{id, status, priority}'

say "13. DAG: 존재하지 않는 parent → 검증 실패"
curl -s -X POST "$BASE/api/v1/jobs" \
    -H 'Content-Type: application/json' \
    -d "{\"inputUri\":\"s3://demo/in.bin\",\"image\":\"x:1.0\",\"gpuCount\":1,\"parentJobIds\":[\"00000000-0000-0000-0000-000000000000\"]}" \
    | jq '{code, message}'
out "(실제 cycle 검출 시나리오는 DependencyGraphTest.cycleDetected 단위 테스트에서 검증)"

# ---- 14~16. Cost ledger (ADR-0016) ---------------------------------------
say "14. Cost: child 잡을 RUNNING → SUCCEEDED 시키면 종료 hook 에서 단가 박제"
callback "$CHILD" RUNNING >/dev/null
sleep 1
callback "$CHILD" SUCCEEDED "s3://demo/train.bin" >/dev/null
sleep 1

say "15. Cost: 단건 cost record (owner 본인) — gpuCount × runtime × ratePerGpuHour"
curl -s "$BASE/api/v1/cost/jobs/$CHILD" \
    | jq '{jobId, owner, gpuCount, runtimeMillis, ratePerGpuHour, computedCost, currency, finalStatus}'

say "16. Cost: owner 별 누적 (지난 1 시간)"
FROM=$(date -u -v-1H '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '1 hour ago' '+%Y-%m-%dT%H:%M:%SZ')
TO=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
curl -s "$BASE/api/v1/cost/owners/$OWNER?from=$FROM&to=$TO" \
    | jq '{owner, from, to, jobCount, totalGpuMillis, totalGpuHours, totalCost, currency}'

# ---- 17. Baggage (ADR-0021) ----------------------------------------------
say "17. Baggage: owner / cost-center 헤더를 박아 제출 → 로그·trace 라벨로 자동 미러링"
out "Baggage 헤더는 W3C 표준 (RFC 9.5.3): 'baggage: key=value,key=value'"
curl -s -X POST "$BASE/api/v1/jobs" \
    -H 'Content-Type: application/json' \
    -H 'baggage: cost-center=research-vision,priority-tier=HIGH' \
    -d '{"inputUri":"s3://demo/in.bin","image":"engine:1.0","gpuCount":1,"priority":"HIGH"}' \
    | jq '{id, status, priority}'
out "→ orchestrator-api 로그 라인의 [...,owner=anonymousUser,cc=research-vision,...] 부분 확인"
out "→ Tempo / Jaeger UI 가 연결되어 있다면 span tag 로 cost-center 도 노출"

# ---- 18. preemption / leader 흐름 안내 -----------------------------------
say "18. (참고) preemption / leader endpoint"
out "GET /api/v1/jobs/{id}/preemption-history — owner / admin 만"
out "GET /api/v1/preemption-history?limit=N    — admin 전용 (Permissive 모드에서는 403)"
out "GET /actuator/health/readiness            — 외부 의존성 회로 OPEN 시 unready"
out "leader 상태는 K8s 환경에서 'kubectl get lease gwp-orchestrator-leader -n gwp -o yaml'"

say "완료. 더 보려면:"
out "  Swagger UI : $BASE/swagger"
out "  AsyncAPI   : orchestrator-api/docs/asyncapi/  (consumer-driven contract 의 출처)"
out "  ADR 인덱스  : orchestrator-api/docs/adr/README.md"
