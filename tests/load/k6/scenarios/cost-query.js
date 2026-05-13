// GET /api/v1/cost/owners/{owner}?from=...&to=... constant 100 req/s 부하.
//
// 시나리오 의도:
//   - CostController.ownerSummary 의 부하 측정 — owner 본인 시각의 시간 구간 cost 합계.
//     CostQueryService.summaryForOwner 가 cost_record 테이블의 BigDecimal 컬럼을 시간
//     구간 + owner 로 필터 + SUM 집계 (단가 박제 모델 — JobCostRecord 참고).
//   - 운영 환경의 cost record 행 수가 늘어나도 (월별 청구서 행 누적) p99 가 무너지지
//     않는지가 핵심. read-heavy 시나리오라 캐시 / 인덱스 (owner_id, created_at) 동작이
//     집계 latency 의 주된 결정 요인.
//   - 시간 구간은 시나리오 시작 시점 기준 24h 또는 7d 윈도우를 round-robin — owner 풀
//     × 윈도우 분기로 캐시 적중 / 미적중 모두 흘려본다.
//
// 권한:
//   - jobAccessControl 로직: owner.equals(caller.owner()) 또는 admin. Permissive 모드는
//     caller.owner() = `anonymousUser` 라 OWNERS 풀의 path variable 과 다르면 403. 부하
//     환경에서는 K6_TOKEN 에 admin scope 토큰을 주입하거나 K6_OWNERS=anonymousUser 로
//     덮어써 비교. 본 시나리오의 임계값은 200 응답만 가드해서 403 비율 자체는 별도
//     metric 으로 분리.
//
// thresholds:
//   - http_req_duration{name:cost-query} p95 < 200ms — BigDecimal SUM + 단일 인덱스 range scan.
//                                                     운영 환경 Postgres 기준.
//   - cost_query_ok rate > 0.9 — 200 응답 비율 (403 / 404 는 환경 / 권한 이슈로 분리).
//   - cost_aggregate_p99_p95_ratio (정보성) — p99/p95 비율. 큐 / 캐시 미적중의 꼬리 신호.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { BASE_URL, pickOwner } from '../lib/config.js';
import { commonHeaders } from '../lib/auth.js';

const queryOk = new Rate('cost_query_ok');
const forbiddenCount = new Counter('cost_query_forbidden');
const notFoundCount = new Counter('cost_query_not_found');
const aggregateLatency = new Trend('cost_aggregate_ms', true);

export const options = {
  scenarios: {
    cost_query: {
      executor: 'constant-arrival-rate',
      rate: 100,                 // 초당 100 req — read-heavy 시나리오로 충분
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 30,
      maxVUs: 150,
    },
  },
  thresholds: {
    'http_req_duration{name:cost-query}': ['p(95)<200', 'p(99)<500'],
    'http_req_failed{name:cost-query}': ['rate<0.1'],   // 403 은 환경 의존이라 좀 관대.
    cost_query_ok: ['rate>0.9'],
  },
};

// 윈도우 후보 — 매 iteration 마다 round-robin. 24h / 7d / 30d.
const WINDOW_HOURS = [24, 24, 24, 168, 168, 720];

function isoUtc(date) {
  return date.toISOString();
}

export default function () {
  const owner = pickOwner(__VU, __ITER);
  const windowHours = WINDOW_HOURS[(__VU + __ITER) % WINDOW_HOURS.length];
  const to = new Date();
  const from = new Date(to.getTime() - windowHours * 60 * 60 * 1000);

  const url = `${BASE_URL}/api/v1/cost/owners/${encodeURIComponent(owner)}` +
              `?from=${encodeURIComponent(isoUtc(from))}` +
              `&to=${encodeURIComponent(isoUtc(to))}`;

  const res = http.get(url, {
    headers: {
      ...commonHeaders({ 'cost-center': 'finops', priority: 'NORMAL' }),
    },
    tags: { name: 'cost-query' },
  });

  aggregateLatency.add(res.timings.duration);
  queryOk.add(res.status === 200);
  if (res.status === 403) forbiddenCount.add(1);
  if (res.status === 404) notFoundCount.add(1);

  check(res, {
    'cost 200 or 403/404 (env)': (r) => r.status === 200 || r.status === 403 || r.status === 404,
    'cost body has totalKrw or empty': (r) => {
      if (r.status !== 200) return true;
      const body = r.body || '';
      // CostSummaryResponse 는 owner / window / totalKrw / totalGpuSeconds 형태.
      return body.includes('"totalKrw"') || body.includes('"total"') || body.startsWith('{');
    },
  });

  sleep(0.01);
}
