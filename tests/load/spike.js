// K6 시나리오: 평소 10 RPS 에서 30초간 200 RPS 로 급증.
//
// 목적: KEDA / HPA / Cluster Autoscaler 의 반응성 검증. 운영 환경에서 실제 autoscaling
// 동작을 확인하는 용도.
import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 500,
      stages: [
        { duration: '30s', target: 10 },   // baseline
        { duration: '10s', target: 200 },  // ramp up to spike
        { duration: '30s', target: 200 },  // hold spike
        { duration: '10s', target: 10 },   // back down
        { duration: '30s', target: 10 },   // recover baseline
      ],
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.05'],  // spike 중에는 5% 까지 허용
    'http_req_duration': ['p(95)<1000'],  // spike 중 latency 완화
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
