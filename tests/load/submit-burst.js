// K6 시나리오: 10초간 100 RPS Job 제출 burst.
//
// 목적: 짧은 burst 에서 API 응답 / Outbox 트랜잭션이 안정적인지 검증.
//
// 실행: BASE_URL=http://localhost:8080 k6 run tests/load/submit-burst.js
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    burst: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '10s',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],                // 5xx + 네트워크 < 1%
    'http_req_duration{name:submit}': ['p(95)<300'], // p95 ≤ 300ms (SLO)
  },
};

export default function () {
  const payload = JSON.stringify({
    inputUri: 's3://demo/in.bin',
    image: 'gpu-worker:1.0',
    gpuCount: 1,
    priority: 'NORMAL',
  });

  const res = http.post(`${BASE_URL}/api/v1/jobs`, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'submit' },
  });

  check(res, {
    'submit 202': (r) => r.status === 202 || r.status === 201,
    'has jobId': (r) => r.json('id') !== undefined,
  });
}
