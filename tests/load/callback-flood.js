// K6 시나리오: 1000개 Job 의 콜백을 동시 전송.
//
// 목적: 콜백 endpoint 가 OptimisticLockException 을 적절히 처리하고 Outbox 트랜잭션이
// 안정적으로 일어나는지 검증. 같은 Job 에 여러 콜백이 동시 도착하는 시나리오 (워커 재시도
// 로 인해 발생 가능) 도 일부 포함.
//
// 사전 준비: setup() 단계에서 1000개 Job 을 미리 생성.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CALLBACK_SECRET = __ENV.CALLBACK_SECRET || 'dev-secret-change-me';
const JOB_COUNT = parseInt(__ENV.JOB_COUNT || '1000');

export const options = {
  scenarios: {
    callbacks: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: JOB_COUNT * 2,  // RUNNING + SUCCEEDED 두 번씩
      maxDuration: '5m',
    },
  },
  thresholds: {
    'http_req_failed{scenario:callbacks}': ['rate<0.02'],  // OptimisticLock retry 감안
    'http_req_duration{name:callback}': ['p(95)<500'],
  },
};

export function setup() {
  const ids = [];
  for (let i = 0; i < JOB_COUNT; i++) {
    const res = http.post(`${BASE_URL}/api/v1/jobs`, JSON.stringify({
      inputUri: 's3://demo/in.bin',
      image: 'gpu-worker:1.0',
      gpuCount: 1,
      priority: 'NORMAL',
    }), { headers: { 'Content-Type': 'application/json' } });
    if (res.status >= 200 && res.status < 300) {
      ids.push(res.json('id'));
    }
  }
  console.log(`prepared ${ids.length} jobs for callback test`);
  return { jobIds: ids };
}

export default function (data) {
  const jobId = data.jobIds[Math.floor(Math.random() * data.jobIds.length)];
  const status = Math.random() < 0.5 ? 'RUNNING' : 'SUCCEEDED';
  const payload = status === 'SUCCEEDED'
    ? { status: 'SUCCEEDED', resultUri: 's3://demo/out.bin' }
    : { status: 'RUNNING' };

  const res = http.post(
    `${BASE_URL}/internal/jobs/${jobId}/status`,
    JSON.stringify(payload),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-GWP-Callback-Secret': CALLBACK_SECRET,
      },
      tags: { name: 'callback' },
    }
  );

  check(res, {
    'callback ok or expected conflict': (r) => r.status === 200 || r.status === 409,
  });
}
