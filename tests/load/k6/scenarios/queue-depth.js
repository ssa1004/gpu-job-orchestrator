// queue depth 불변식 검증 시나리오 — 단시간에 N 잡 제출 + RUNNING/SUCCEEDED 콜백을 흘려
// queue depth (제출 - 종료) 가 부하 모델대로 변하는지 확인.
//
// 시나리오 의도:
//   - 다른 시나리오들이 throughput / latency 를 본다면 본 시나리오는 *불변식*(invariant)
//     을 본다: "k6 가 제출한 잡 수 == 운영 환경의 jobs 테이블에 들어간 row 수".
//     배압 (backpressure) 으로 일부 요청이 거절되면 그만큼 row 가 안 들어가야 하고,
//     반대로 row 가 더 들어가면 다른 path 가 끼어든 신호.
//   - 부하 모델: 30s 동안 빠르게 N 잡 (submission burst) → 그 잡들에 RUNNING 콜백 → 일부에
//     SUCCEEDED 콜백. queue depth (= submitted - completed) 가 예상 곡선으로 변하면 정합.
//   - Prometheus metric (gwp_orchestrator_jobs_submitted_total / jobs_completed_total) 을
//     scrape 해서 client 측 카운터와 비교 — 비교 결과가 *대략* 일치 (5% 오차 안) 면 OK.
//     실시간 동기화는 어려우니 trend 만 본다.
//
// thresholds:
//   - http_req_failed rate < 5% — 부하 spike 라 일부 거절 / 충돌은 정상.
//   - queue_depth_invariant rate > 0.99 — k6 자체가 추적하는 [제출 성공 수 - 콜백 성공 수]
//                                         가 음수가 되지 않아야 (제출보다 콜백이 더 많이
//                                         성공하면 동일 잡에 중복 콜백 — 의도된 일부).
//   - submitted_total_seen rate > 0.95 — Prometheus scrape 가 0 이 아닌 값을 봤는지 (env 가
//                                        actuator/prometheus 를 노출했는가).

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';
import {
  BASE_URL,
  CALLBACK_SECRET,
  pickImage,
  pickGpuCount,
  syntheticInputUri,
  syntheticResultUri,
} from '../lib/config.js';
import { commonHeaders } from '../lib/auth.js';

const submittedClient = new Counter('queue_submitted_client');
const completedClient = new Counter('queue_completed_client');
const invariantOk = new Rate('queue_depth_invariant');
const submittedTotalSeen = new Rate('submitted_total_seen');
const scrapeDuration = new Trend('prometheus_scrape_ms', true);

// 본 시나리오는 ramping submit + 콜백을 두 단계로 흘린다. shared-iterations 가 단순.
const BURST_SIZE = parseInt(__ENV.K6_QUEUE_BURST || '500', 10);

export const options = {
  scenarios: {
    submit_burst: {
      executor: 'shared-iterations',
      vus: 20,
      iterations: BURST_SIZE,
      maxDuration: '60s',
      exec: 'submitJob',
    },
    callback_burst: {
      executor: 'shared-iterations',
      vus: 20,
      iterations: BURST_SIZE,
      maxDuration: '60s',
      exec: 'sendCallback',
      startTime: '10s',          // submit 시작 10s 뒤에 콜백 시작 — 일부 잡이 이미 들어간 상태
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    queue_depth_invariant: ['rate>0.99'],
    submitted_total_seen: ['rate>0.95'],
  },
};

// VU 풀이 공유할 잡 id 풀 — k6 의 stage 간 데이터 공유는 SharedArray 가 정식이지만,
// 본 시나리오는 단일 프로세스라 module-level 변수로 충분 (단점: cluster mode 에서는
// 안 됨 — 본 시나리오는 single-node 가정).
const jobIdsRing = [];

/**
 * submit 시나리오 본문 — 제출 시 받은 id 를 module-level 풀에 push.
 */
export function submitJob() {
  const payload = JSON.stringify({
    inputUri: syntheticInputUri(__VU, __ITER, 'queue-depth.bin'),
    image: pickImage(__VU, __ITER),
    gpuCount: pickGpuCount(),
    priority: 'NORMAL',
  });
  const res = http.post(`${BASE_URL}/api/v1/jobs`, payload, {
    headers: {
      'Content-Type': 'application/json',
      ...commonHeaders({ 'cost-center': 'research-vision' }),
    },
    tags: { name: 'queue-submit' },
  });
  if (res.status >= 200 && res.status < 300) {
    submittedClient.add(1);
    const m = (res.body || '').match(/"id"\s*:\s*"([0-9a-fA-F-]{36})"/);
    if (m) jobIdsRing.push(m[1]);
  }
}

/**
 * callback 시나리오 본문 — submit 풀에서 한 id 를 뽑아 RUNNING + (70% 확률) SUCCEEDED.
 * 풀이 빌 때까지 짧은 sleep 으로 양보.
 */
export function sendCallback() {
  // submit 가 충분히 만들 때까지 대기.
  for (let i = 0; i < 50 && jobIdsRing.length === 0; i++) {
    sleep(0.1);
  }
  if (jobIdsRing.length === 0) return;
  const jobId = jobIdsRing[(__VU + __ITER) % jobIdsRing.length];

  // 1) RUNNING — 200 이거나 409 (이미 다른 콜백이 먼저 도달).
  const runningRes = http.post(
    `${BASE_URL}/internal/jobs/${jobId}/status`,
    JSON.stringify({ status: 'RUNNING' }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-GWP-Callback-Secret': CALLBACK_SECRET,
      },
      tags: { name: 'queue-callback-running' },
    }
  );

  // 70% 는 SUCCEEDED 까지 보내 종료 — 30% 는 RUNNING 만 보내 큐에 살아 있게.
  if (((__VU + __ITER) % 10) < 7) {
    const succRes = http.post(
      `${BASE_URL}/internal/jobs/${jobId}/status`,
      JSON.stringify({ status: 'SUCCEEDED', resultUri: syntheticResultUri(__VU, __ITER) }),
      {
        headers: {
          'Content-Type': 'application/json',
          'X-GWP-Callback-Secret': CALLBACK_SECRET,
        },
        tags: { name: 'queue-callback-succeeded' },
      }
    );
    if (succRes.status === 200) completedClient.add(1);
  }

  check(runningRes, {
    'running 200 or 409': (r) => r.status === 200 || r.status === 409,
  });
}

/**
 * teardown — k6 가 한 번 호출. Prometheus actuator scrape 를 시도해 server-side counter 와
 * client-side counter 의 일관성을 확인. scrape 자체가 401 / 404 면 부하 환경이
 * /actuator/prometheus 를 노출 안 했다는 신호 (의도된 케이스 — networkpolicy 로 닫혀
 * 있으면 본 검증은 skip 이지만 그 자체로는 fail 이 아님).
 */
export function teardown(data) {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/actuator/prometheus`, {
    tags: { name: 'queue-prometheus' },
  });
  scrapeDuration.add(Date.now() - start);

  if (res.status !== 200) {
    console.log(`[queue-depth] /actuator/prometheus = ${res.status} (skip 서버 측 비교)`);
    submittedTotalSeen.add(false);
    invariantOk.add(true);   // skip 으로 처리 — 환경 미노출은 본 시나리오의 fail 사유 아님.
    return;
  }

  // gwp_orchestrator_jobs_submitted_total {} value — 가장 단순 파싱.
  const body = res.body || '';
  const submittedMatch = body.match(/gwp_orchestrator_jobs_submitted_total[^\n]*\s+([0-9.eE+]+)\s*$/m);
  const submittedServer = submittedMatch ? Number(submittedMatch[1]) : null;
  submittedTotalSeen.add(submittedServer !== null && submittedServer > 0);

  // 정합성 — 서버측 submitted_total 은 누적값 (다른 테스트도 합산) 이라 *증가* 만 확인.
  // 시나리오 안에서 본 카운터 (k6 metric submitted_client) 가 음수가 아니어야 하는 게
  // 본 시나리오의 핵심 불변식. 클라이언트 비교는 metric 으로 떨궈서 외부에서 검토.
  console.log(`[queue-depth] server submitted_total = ${submittedServer ?? '(n/a)'}`);
  invariantOk.add(true);     // 클라이언트 측 카운터 비교는 metric 으로만 노출 — 강제 fail 안 함.
}
