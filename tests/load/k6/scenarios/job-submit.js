// 단일 잡 제출 (POST /api/v1/jobs) constant 200 req/s 부하.
//
// 시나리오 의도:
//   - parent 의존이 없는 단일 잡 제출 경로의 throughput / latency 를 측정. 한 호출이
//     하는 일: JobSubmissionRequest 검증 → 쿼터 단일 쿼리 (SUM/COUNT) → Job INSERT →
//     Outbox INSERT (한 트랜잭션) → 응답 201 Created + Location 헤더.
//   - DAG / preemption 같은 분기는 들어가지 않는 가장 빠른 path — submit_p95 의 baseline.
//
// thresholds:
//   - http_req_duration{name:job-submit} p95 < 100ms — 검증 + DB insert + Outbox INSERT 의 합.
//                                                     운영 환경 Postgres + HikariCP 20 풀 기준.
//   - http_req_failed rate < 1% — 검증 실패 / 쿼터 초과 (429) 는 의도된 부하 모델 밖.
//                                  부하 환경의 쿼터를 충분히 크게 잡아 5xx 신호만 가드.
//   - submit_accepted rate > 99% — 201 Created 비율. 200/202 도 응답으로 받아 fallback.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import {
  BASE_URL,
  pickImage,
  pickGpuCount,
  pickPriority,
  syntheticInputUri,
} from '../lib/config.js';
import { commonHeaders } from '../lib/auth.js';

const acceptedRate = new Rate('submit_accepted');
// 시나리오 전용 latency trend — 운영 환경의 submitTimer (gwp_orchestrator_job_submit_seconds)
// 와 동일 의미. tag 로 분리되는 http_req_duration{name:job-submit} 과 함께 보면 client side
// + server side 두 시각을 비교 가능.
const submitLatency = new Trend('submit_latency_ms', true);

export const options = {
  scenarios: {
    job_submit: {
      executor: 'constant-arrival-rate',
      rate: 200,                 // 초당 200 req — submission throughput baseline
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    'http_req_failed{name:job-submit}': ['rate<0.01'],
    'http_req_duration{name:job-submit}': ['p(95)<100', 'p(99)<250'],
    submit_accepted: ['rate>0.99'],
  },
};

export default function () {
  const payload = JSON.stringify({
    inputUri: syntheticInputUri(__VU, __ITER),
    image: pickImage(__VU, __ITER),
    gpuCount: pickGpuCount(),
    priority: pickPriority(),
    // preemptionPolicy 미지정 → PREEMPTABLE 기본. parentJobIds null → 즉시 dispatch path.
  });

  const res = http.post(`${BASE_URL}/api/v1/jobs`, payload, {
    headers: {
      'Content-Type': 'application/json',
      ...commonHeaders({ 'cost-center': 'research-vision' }),
    },
    tags: { name: 'job-submit' },
  });

  const accepted = res.status === 201 || res.status === 200 || res.status === 202;
  acceptedRate.add(accepted);
  submitLatency.add(res.timings.duration);

  check(res, {
    'submit 2xx': (r) => r.status >= 200 && r.status < 300,
    'has Location header': (r) => {
      if (r.status !== 201) return true;
      return (r.headers['Location'] || '').length > 0;
    },
    'body has job id': (r) => {
      if (r.status < 200 || r.status >= 300) return true;
      const body = r.body || '';
      return body.includes('"id"');
    },
  });

  // 최소 양보 — VU 의 다음 iteration 까지의 minimum sleep. constant-arrival-rate 이라
  // 자체 rate 가 throttling 을 책임진다.
  sleep(0.01);
}
