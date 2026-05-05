// K6 시나리오: 5분간 50 RPS 지속 부하.
//
// 목적: 중간 길이의 정상 부하에서 Outbox 발행 지연 / DB 연결 풀 / GC 거동 확인.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    sustained: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 30,
      maxVUs: 100,
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.005'],
    'http_req_duration': ['p(50)<50', 'p(95)<200', 'p(99)<500'],
  },
};

export default function () {
  const payload = JSON.stringify({
    inputUri: 's3://demo/in.bin',
    image: 'gpu-worker:1.0',
    gpuCount: 1,
    priority: 'NORMAL',
  });
  http.post(`${BASE_URL}/api/v1/jobs`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });
}
