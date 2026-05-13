#!/usr/bin/env bash
# k6 부하 시나리오 5종 일괄 실행.
#
# 단계:
#   1) orchestrator-api healthcheck (없으면 bootRun 안내)
#   2) k6 실행 경로 결정 — 우선 로컬 k6, 없으면 docker run
#   3) job-submit → job-callback → dag-submit → cost-query → queue-depth
#   4) 각 결과는 build/k6-reports/{scenario}.json 에 떨군다
#
# 환경변수:
#   BASE_URL                — 시나리오의 endpoint base (기본 :8080).
#   K6_TOKEN                — JWT mode 일 때 Bearer. Permissive 모드면 빈 값.
#   K6_CALLBACK_SECRET      — /internal/jobs/{id}/status 의 X-GWP-Callback-Secret.
#                             기본 application.yml 의 `dev-secret-change-me`.
#   K6_OWNERS               — cost-query owner 풀 (CSV). Permissive 모드면 anonymousUser 로.
#   K6_GPU_IMAGES           — image 풀 (CSV).
#   K6_CALLBACK_JOB_POOL    — job-callback setup() 단계 잡 풀 크기 (기본 300).
#   K6_QUEUE_BURST          — queue-depth submit / callback burst 크기 (기본 500).

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SCENARIO_DIR="${ROOT_DIR}/tests/load/k6/scenarios"
REPORT_DIR="${ROOT_DIR}/build/k6-reports"
mkdir -p "$REPORT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8080}"
K6_TOKEN="${K6_TOKEN:-}"
K6_CALLBACK_SECRET="${K6_CALLBACK_SECRET:-${GWP_CALLBACK_SECRET:-dev-secret-change-me}}"
K6_OWNERS="${K6_OWNERS:-team-vision,team-llm,team-speech,team-recommender,team-research,team-infra,team-platform,team-finops}"
K6_GPU_IMAGES="${K6_GPU_IMAGES:-gpu-worker:1.0,gpu-worker:1.1,gpu-worker:1.2,llm-trainer:latest-cuda12,sd-inference:v3,whisper-runner:0.6}"
K6_CALLBACK_JOB_POOL="${K6_CALLBACK_JOB_POOL:-300}"
K6_QUEUE_BURST="${K6_QUEUE_BURST:-500}"

echo "==> base url: $BASE_URL"

# 1) healthcheck
echo
echo "==> health 확인 ($BASE_URL/actuator/health)"
if ! curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1; then
    cat <<EOF
ERROR: $BASE_URL 가 응답하지 않습니다.

먼저 orchestrator-api 를 띄우세요:

  1) 단독 bootRun (가벼움 — H2 또는 dev Postgres):
       cd orchestrator-api && ./gradlew bootRun
       BASE_URL=http://localhost:8080 ./scripts/run-load.sh

  2) 통합 (Postgres + Kafka — 부하 측정용):
       docker compose -f infrastructure/docker/docker-compose.yml \\
           up -d postgres kafka kafka-ui
       cd orchestrator-api && SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
       BASE_URL=http://localhost:8080 ./scripts/run-load.sh

또는 BASE_URL 를 staging 등으로 덮어쓰세요 (예: BASE_URL=https://gwp.example.com).
EOF
    exit 1
fi
echo "    UP"

# 2) k6 실행 경로
if command -v k6 >/dev/null 2>&1; then
    K6_EXEC=("k6")
    echo "==> 로컬 k6 사용 ($(k6 version | head -1))"
elif command -v docker >/dev/null 2>&1; then
    # docker 안에서 호스트의 localhost 를 보려면 host.docker.internal 사용.
    if [[ "$BASE_URL" == *"localhost"* || "$BASE_URL" == *"127.0.0.1"* ]]; then
        BASE_URL_DOCKER="${BASE_URL//localhost/host.docker.internal}"
        BASE_URL_DOCKER="${BASE_URL_DOCKER//127.0.0.1/host.docker.internal}"
    else
        BASE_URL_DOCKER="$BASE_URL"
    fi
    K6_EXEC=(docker run --rm -i \
        -v "${ROOT_DIR}/tests/load/k6:/scripts:ro" \
        -e "BASE_URL=${BASE_URL_DOCKER}" \
        -e "K6_TOKEN=${K6_TOKEN}" \
        -e "K6_CALLBACK_SECRET=${K6_CALLBACK_SECRET}" \
        -e "K6_OWNERS=${K6_OWNERS}" \
        -e "K6_GPU_IMAGES=${K6_GPU_IMAGES}" \
        -e "K6_CALLBACK_JOB_POOL=${K6_CALLBACK_JOB_POOL}" \
        -e "K6_QUEUE_BURST=${K6_QUEUE_BURST}" \
        grafana/k6:0.50.0)
    SCRIPT_PREFIX="/scripts/scenarios"
    echo "==> docker run grafana/k6 사용"
else
    echo "ERROR: k6 도 docker 도 없습니다. brew install k6 또는 docker 설치 후 다시 시도하세요." >&2
    exit 1
fi

# 3) 시나리오 실행 — 한 단계 실패해도 다음 단계는 진행
run_scenario() {
    local name="$1"
    local file="$2"

    echo
    echo "==> [$name] start ($(date +%H:%M:%S))"
    local out="${REPORT_DIR}/${name}.json"
    local rc=0

    if [[ "${K6_EXEC[0]}" == "k6" ]]; then
        export BASE_URL K6_TOKEN K6_CALLBACK_SECRET K6_OWNERS K6_GPU_IMAGES \
               K6_CALLBACK_JOB_POOL K6_QUEUE_BURST
        set +e
        "${K6_EXEC[@]}" run --summary-export="$out" "$file"
        rc=$?
        set -e
    else
        local docker_file="${SCRIPT_PREFIX}/$(basename "$file")"
        # docker mount 안에서 summary-export 경로 맞추기.
        local docker_out="/scripts/${name}.summary.json"
        set +e
        "${K6_EXEC[@]}" run --summary-export="$docker_out" "$docker_file"
        rc=$?
        set -e
        if [[ -f "${ROOT_DIR}/tests/load/k6/${name}.summary.json" ]]; then
            mv "${ROOT_DIR}/tests/load/k6/${name}.summary.json" "$out"
        fi
    fi

    if [[ $rc -eq 0 ]]; then
        echo "==> [$name] PASSED (report: $out)"
    else
        echo "==> [$name] FAILED rc=$rc (report: $out)"
    fi
}

# 실행 순서:
#   - job-submit (가장 빠른 path baseline) →
#   - job-callback (콜백 throughput — setup() 단계에서 잡 풀 생성) →
#   - dag-submit (parent/child ramping) →
#   - cost-query (read-heavy) →
#   - queue-depth (불변식 — submit + callback 두 burst)
run_scenario "job-submit"   "${SCENARIO_DIR}/job-submit.js"
run_scenario "job-callback" "${SCENARIO_DIR}/job-callback.js"
run_scenario "dag-submit"   "${SCENARIO_DIR}/dag-submit.js"
run_scenario "cost-query"   "${SCENARIO_DIR}/cost-query.js"
run_scenario "queue-depth"  "${SCENARIO_DIR}/queue-depth.js"

echo
echo "==> 모든 시나리오 종료. 리포트: $REPORT_DIR"
ls -lah "$REPORT_DIR" 2>/dev/null || true
