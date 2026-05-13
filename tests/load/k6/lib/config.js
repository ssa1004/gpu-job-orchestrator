// k6 시나리오 공용 설정 — BASE URL + GPU 이미지 / owner / DAG depth 풀.
//
// BASE_URL 은 환경변수로 덮어쓸 수 있다. 기본은 orchestrator-api 의 단독 bootRun
// 포트 8080. 운영 환경 측정은 `BASE_URL=https://gwp-api.example.com` 형태로 외부 주입.

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/**
 * 콜백 endpoint 의 공유 시크릿 — `/internal/jobs/{id}/status` 검증용.
 * application.yml 의 기본 `dev-secret-change-me` 와 같게 두고, 운영 환경은
 * `GWP_CALLBACK_SECRET` env 로 외부 주입.
 */
export const CALLBACK_SECRET = __ENV.K6_CALLBACK_SECRET || __ENV.GWP_CALLBACK_SECRET || 'dev-secret-change-me';

/**
 * GPU 워커 이미지 풀 — 한 시나리오 안에서 round-robin 으로 흘려 한 image tag 에
 * cardinality 가 몰리는 케이스를 피한다 (실제 운영은 팀 / 모델 / 버전 별로 image
 * tag 가 다양). JobSubmissionRequest 의 image 정규식
 * (`[registry/]repo[:tag][@sha256:digest]`) 을 통과하는 형태만.
 */
export const GPU_IMAGES = (__ENV.K6_GPU_IMAGES
  || 'gpu-worker:1.0,gpu-worker:1.1,gpu-worker:1.2,llm-trainer:latest-cuda12,sd-inference:v3,whisper-runner:0.6')
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

/**
 * owner 풀 — JWT mode 에서는 sub 클레임이 owner 가 되지만, 본 풀은 cost-query
 * 시나리오에서 owner path variable 에 직접 박는 용도 + cost-center / priority 분기
 * 시뮬레이션용. Permissive 모드 (caller.owner() = `anonymousUser`) 에서는 DB 의
 * cost record owner 도 anonymousUser 로 묶이므로 본 풀은 더미.
 */
export const OWNERS = (__ENV.K6_OWNERS
  || 'team-vision,team-llm,team-speech,team-recommender,team-research,team-infra,team-platform,team-finops')
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

/**
 * GPU 카운트 후보 — 1 / 2 / 4 / 8. JobSubmissionRequest 의 `@Min(1) @Max(8)` 범위 안.
 */
export const GPU_COUNTS = [1, 1, 1, 2, 2, 4, 8];

/**
 * 잡 우선순위 후보 — JobPriority enum (LOW / NORMAL / HIGH). 부하 모델은 NORMAL 위주에
 * HIGH 일부 (preemption 후보 트리거).
 */
export const PRIORITIES = ['NORMAL', 'NORMAL', 'NORMAL', 'NORMAL', 'HIGH', 'LOW'];

/**
 * DAG 폭 / 깊이 풀. dag-submit 시나리오가 parent N + child 1 그래프를 만들 때 N 의 후보.
 *   - 대부분은 parent 2 (가장 흔한 fork-join 모양)
 *   - 일부는 3~5 로 더 큰 fan-in. cycle detection 의 그래프 traversal 비용이 길어지는 영역.
 */
export const DAG_PARENT_COUNTS = [2, 2, 2, 2, 3, 3, 5];

/**
 * 한 VU × iteration 단위로 GPU image 를 round-robin 으로 뽑는 헬퍼.
 */
export function pickImage(vuId, iter) {
  if (GPU_IMAGES.length === 0) return 'gpu-worker:1.0';
  return GPU_IMAGES[(vuId + iter) % GPU_IMAGES.length];
}

/**
 * owner round-robin — cost-query 시나리오의 path variable 분산용.
 */
export function pickOwner(vuId, iter) {
  if (OWNERS.length === 0) return 'team-vision';
  return OWNERS[(vuId + iter) % OWNERS.length];
}

/**
 * GPU 카운트 / 우선순위 — Math.random() 기반 단순 분포. cardinality 검증이 아니라
 * 부하 모델 다양성 목적.
 */
export function pickGpuCount() {
  return GPU_COUNTS[Math.floor(Math.random() * GPU_COUNTS.length)];
}

export function pickPriority() {
  return PRIORITIES[Math.floor(Math.random() * PRIORITIES.length)];
}

export function pickParentCount() {
  return DAG_PARENT_COUNTS[Math.floor(Math.random() * DAG_PARENT_COUNTS.length)];
}

/**
 * 기본 input URI — JobSubmissionRequest 의 정규식 (`^s3://[a-z0-9.\-]+/.+`) 통과.
 * 매 iteration 마다 다른 key 가 되도록 vuId / iter 를 끼워 cache hit 가 생기지 않게 한다.
 */
export function syntheticInputUri(vuId, iter, suffix = 'in.bin') {
  return `s3://gwp-loadtest/${vuId}/${iter}/${suffix}`;
}

/**
 * 결과 URI — callback 의 SUCCEEDED 시 resultUri 채우는 용도. 정합성 검증 X, 길이 cap 안.
 */
export function syntheticResultUri(vuId, iter) {
  return `s3://gwp-loadtest/results/${vuId}/${iter}/out.bin`;
}
