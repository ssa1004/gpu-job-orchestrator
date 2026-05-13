// 워커 콜백 시뮬레이션 (POST /internal/jobs/{id}/status) constant 500 req/s.
//
// 시나리오 의도:
//   - GPU 워커 (worker/ 의 Go 바이너리) 의 상태 콜백을 모사. 한 잡 라이프사이클 동안
//     RUNNING → SUCCEEDED 두 번 호출 — setup() 에서 N 개 잡을 미리 제출하고, default
//     함수가 각 잡에 대해 두 상태를 순차 발사.
//   - 콜백 endpoint 가 하는 일: X-GWP-Callback-Secret 상수시간 비교 → 상태 머신 전이
//     (advisory lock 또는 @Version optimistic lock) → status 컬럼 UPDATE → 종료 상태
//     면 Outbox publish 이벤트 INSERT. 본 시나리오는 그 path 의 callback throughput 을
//     본다.
//   - 워커가 재시도로 같은 콜백을 두 번 보내거나 (네트워크 timeout), 두 워커가 같은
//     잡에 동시에 콜백을 보내는 (drain / dispatch race) 케이스도 의도적으로 일부 섞어
//     409 / 200 정합성을 가드.
//
// thresholds:
//   - http_req_duration{name:callback} p95 < 50ms — 상태 머신 전이 + UPDATE 한 row + 종료
//                                                  시 Outbox INSERT 한 row 만의 짧은 path.
//   - http_req_failed{name:callback} rate < 2% — OptimisticLock 충돌 / 401 (잘못된 secret 의
//                                               의도된 일부) 제외하면 0 에 가까워야.
//   - callback_lifecycle_ok rate > 95% — 200 (정상 전이) + 409 (lock 충돌 retry) 의 합 비율.
//   - callback_unexpected_5xx count < 10 — 콜백 path 가 5xx 를 거의 내지 않아야 한다.

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

const lifecycleOk = new Rate('callback_lifecycle_ok');
const unexpected5xx = new Counter('callback_unexpected_5xx');
const callbackLatency = new Trend('callback_latency_ms', true);

// 사전 제출 잡 개수 — duration / rate 기준 충분히 큰 풀이 필요. 500 req/s × 30s = 15000
// req → 한 잡당 2 콜백 (RUNNING + SUCCEEDED) → 7500 잡이면 부족하지 않게.
const JOB_POOL_SIZE = parseInt(__ENV.K6_CALLBACK_JOB_POOL || '300', 10);

export const options = {
  scenarios: {
    job_callback: {
      executor: 'constant-arrival-rate',
      rate: 500,                 // 초당 500 req — callback throughput 한계 측정
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 80,
      maxVUs: 300,
    },
  },
  thresholds: {
    'http_req_failed{name:callback}': ['rate<0.02'],
    'http_req_duration{name:callback}': ['p(95)<50', 'p(99)<150'],
    callback_lifecycle_ok: ['rate>0.95'],
    callback_unexpected_5xx: ['count<10'],
  },
};

/**
 * setup() — JOB_POOL_SIZE 개의 잡을 미리 제출해서 id 풀을 만든다. 콜백 시나리오는 잡 id
 * 가 없으면 모든 호출이 404 — 본 시나리오의 목적인 callback path latency 가 의미를 잃는다.
 *
 * 사전 제출이 실패하면 (BASE_URL 잘못 / 권한 등) 빈 풀로 진행되어 default 함수가 모두
 * 404 를 받는다 — http_req_failed threshold 가 빨갛게 변하므로 운영자가 즉시 부하 환경
 * 미완성을 알아챈다.
 */
export function setup() {
  const ids = [];
  for (let i = 0; i < JOB_POOL_SIZE; i++) {
    const payload = JSON.stringify({
      inputUri: syntheticInputUri(0, i, 'callback-pool.bin'),
      image: pickImage(0, i),
      gpuCount: pickGpuCount(),
      priority: 'NORMAL',
    });
    const res = http.post(`${BASE_URL}/api/v1/jobs`, payload, {
      headers: {
        'Content-Type': 'application/json',
        ...commonHeaders({ 'cost-center': 'platform-team' }),
      },
      tags: { name: 'callback-setup' },
    });
    if (res.status >= 200 && res.status < 300) {
      const m = (res.body || '').match(/"id"\s*:\s*"([0-9a-fA-F-]{36})"/);
      if (m) ids.push(m[1]);
    }
  }
  console.log(`[job-callback] prepared ${ids.length} / ${JOB_POOL_SIZE} jobs`);
  return { jobIds: ids };
}

export default function (data) {
  if (!data.jobIds || data.jobIds.length === 0) {
    // 풀이 비어 있으면 의미 있는 callback 부하가 불가 — 자연스럽게 fail 로 끝난다.
    sleep(0.1);
    return;
  }

  // 한 iteration 에서 한 잡의 한 상태만 보낸다. 한 잡이 RUNNING + SUCCEEDED 두 번 받는
  // 모양은 iteration 분포로 자연 발생 — 80% RUNNING, 20% SUCCEEDED (RUNNING 후에만
  // SUCCEEDED 정상 전이지만 본 시나리오는 callback path 자체의 throughput 이 목적이라
  // 일부 transition 거절 (409) 까지 받는다).
  const jobId = data.jobIds[(__VU + __ITER) % data.jobIds.length];
  const dice = (__VU + __ITER) % 10;
  const isSucceed = dice >= 8;
  const status = isSucceed ? 'SUCCEEDED' : 'RUNNING';
  const body = isSucceed
    ? JSON.stringify({ status: 'SUCCEEDED', resultUri: syntheticResultUri(__VU, __ITER) })
    : JSON.stringify({ status: 'RUNNING' });

  const res = http.post(`${BASE_URL}/internal/jobs/${jobId}/status`, body, {
    headers: {
      'Content-Type': 'application/json',
      'X-GWP-Callback-Secret': CALLBACK_SECRET,
      ...commonHeaders({ 'cost-center': 'platform-team' }),
    },
    tags: { name: 'callback' },
  });

  callbackLatency.add(res.timings.duration);
  // 정상 path — 200 (전이 성공) + 409 (Optimistic lock 충돌, 재시도면 정상) 둘 다 정합.
  // 404 (id 못 찾음) 는 setup 풀이 비어 있을 때만 발생하고 거기서 신호가 분리된다.
  const ok = res.status === 200 || res.status === 409;
  lifecycleOk.add(ok);
  if (res.status >= 500) unexpected5xx.add(1);

  check(res, {
    'callback 200 or 409': (r) => r.status === 200 || r.status === 409,
    'never 5xx': (r) => r.status < 500,
  });

  sleep(0.005);
}
