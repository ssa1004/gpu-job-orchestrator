// DAG 잡 제출 (parent 2 + child 1) ramping 0 → 50 VU.
//
// 시나리오 의도:
//   - DAG 워크플로 모사: 한 iteration 에서 parent 2 개를 먼저 제출 → 받은 id 들로 child 1
//     개를 parentJobIds 로 묶어 제출. child 는 WAITING_DEPS 로 들어가고 parent 가 모두
//     SUCCEEDED 되면 자동으로 QUEUED 로 promote (lifecycle hook). 본 시나리오는 child
//     제출 path 의 latency — cycle detection (그래프 traversal) + parent existence /
//     ownership 검증 + Job + JobDependency link 의 트랜잭션 INSERT 비용을 본다.
//   - 부하 모델은 ramping-vus — 0 → 50 VU 로 증가시키며 graph traversal 비용이 큐 깊이
//     / 동시성 변화에 어떻게 반응하는지 본다. constant-arrival-rate 가 아닌 이유:
//     ramping 부하에서 lock 경합 / Hikari 풀 고갈이 더 잘 드러난다.
//   - parent 풀이 미리 채워지지 않은 상태에서 child 만 보내면 의존성 검증이 404 / 403 로
//     떨어진다 — 본 시나리오는 매 iteration 마다 parent 부터 신선하게 만들어 그 path 도
//     함께 측정.
//
// thresholds:
//   - http_req_duration{name:dag-child} p95 < 500ms — child 의 cycle detection +
//                                                    parent ownership 검증 + JobDependency
//                                                    INSERT 2 row + Job INSERT.
//   - http_req_duration{name:dag-parent} p95 < 200ms — parent 두 잡 제출의 단순 path.
//                                                    job-submit 시나리오와 비슷한 비용.
//   - http_req_failed rate < 2% — parent / child 둘 다 실패는 1% 이하 기대.
//   - dag_complete_ratio rate > 95% — parent 2 + child 1 한 묶음이 모두 2xx 인 iteration 비율.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import {
  BASE_URL,
  pickImage,
  pickGpuCount,
  pickPriority,
  pickParentCount,
  syntheticInputUri,
} from '../lib/config.js';
import { commonHeaders } from '../lib/auth.js';

const completeRatio = new Rate('dag_complete_ratio');
const cycleDetectRejected = new Counter('dag_cycle_rejected');
const dagResolveTime = new Trend('dag_resolve_time_ms', true);

export const options = {
  scenarios: {
    dag_submit: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 10 },   // 워밍업
        { duration: '30s', target: 50 },   // 본격 ramping
        { duration: '15s', target: 50 },   // 정점 유지
        { duration: '10s', target: 0 },    // 점감
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    'http_req_failed{name:dag-parent}': ['rate<0.02'],
    'http_req_failed{name:dag-child}': ['rate<0.02'],
    'http_req_duration{name:dag-parent}': ['p(95)<200'],
    'http_req_duration{name:dag-child}': ['p(95)<500', 'p(99)<1000'],
    dag_complete_ratio: ['rate>0.95'],
  },
};

/**
 * 한 잡 제출 후 id 추출. parent / child 가 같은 path 를 공유.
 *
 * @param tag {string} — k6 tag (`dag-parent` / `dag-child`)
 * @param parentJobIds {string[] | null} — null/empty 이면 parent, 그 외는 child
 * @returns {{ id: string | null, status: number }}
 */
function submitJob(tag, parentJobIds = null) {
  const payload = {
    inputUri: syntheticInputUri(__VU, __ITER, `${tag}.bin`),
    image: pickImage(__VU, __ITER),
    gpuCount: pickGpuCount(),
    priority: pickPriority(),
  };
  if (parentJobIds && parentJobIds.length > 0) {
    payload.parentJobIds = parentJobIds;
  }
  const res = http.post(`${BASE_URL}/api/v1/jobs`, JSON.stringify(payload), {
    headers: {
      'Content-Type': 'application/json',
      ...commonHeaders({ 'cost-center': 'research-vision' }),
    },
    tags: { name: tag },
  });
  let id = null;
  if (res.status >= 200 && res.status < 300) {
    const m = (res.body || '').match(/"id"\s*:\s*"([0-9a-fA-F-]{36})"/);
    if (m) id = m[1];
  }
  return { id, status: res.status, res };
}

export default function () {
  const start = Date.now();

  // 1) parent N 개 제출 — pickParentCount() 가 2/3/5 분포. 대부분 2.
  const parentCount = pickParentCount();
  const parentIds = [];
  let parentAllOk = true;
  for (let i = 0; i < parentCount; i++) {
    const r = submitJob('dag-parent', null);
    if (r.id) parentIds.push(r.id);
    if (r.status < 200 || r.status >= 300) parentAllOk = false;
    check(r.res, { 'parent 2xx': () => r.status >= 200 && r.status < 300 });
  }

  // 2) child 1 개 — parentIds 가 비어 있으면 의존성 검증 실패가 의도된 신호 → 호출 자체는
  //    뜨리고 결과를 dag_complete_ratio 에 반영.
  let childOk = false;
  if (parentIds.length > 0) {
    const childRes = submitJob('dag-child', parentIds);
    childOk = childRes.status >= 200 && childRes.status < 300;
    check(childRes.res, {
      'child 2xx': () => childOk,
      // cycle 거절 — 본 시나리오는 cycle 을 의도하지 않으므로 400 이 보이면 carry-over issue.
      'child not cycle-rejected': (r) => {
        const cycle = r.status === 400 && (r.body || '').includes('cycle');
        if (cycle) cycleDetectRejected.add(1);
        return !cycle;
      },
    });
  }

  completeRatio.add(parentAllOk && childOk);
  dagResolveTime.add(Date.now() - start);

  // ramping 모델에서는 VU 가 짧은 sleep 으로 다음 iteration 으로 빨리 넘어간다 —
  // constant-arrival-rate 와 달리 한 VU 가 직접 부하를 만들기 때문.
  sleep(0.05);
}
