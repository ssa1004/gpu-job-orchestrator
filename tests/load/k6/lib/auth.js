// k6 시나리오 공용 인증 + W3C Baggage 헤더 헬퍼.
//
// orchestrator-api 의 운영 보안 모드:
//   - SecurityConfig (gwp.security.jwt.enabled=true) — `/api/**` 는 OAuth2 JWT 필수,
//     `/internal/**` 은 X-GWP-Callback-Secret 헤더로 별도 검증.
//   - PermissiveSecurityConfig — 로컬 dev. JWT 검사 우회. caller.owner() 가
//     `anonymousUser` 로 떨어진다 — Permissive 모드에서 부하 시나리오를 흘리면 모든
//     요청이 한 owner 로 묶이므로 cost-query 같은 owner 분기 시나리오는 의미가 줄어든다.
//     운영 환경 측정은 K6_TOKEN 을 외부 IdP 발급 토큰으로 채워 sub 클레임 기반으로
//     자연스럽게 owner 가 분산되도록.
//
// 두 가지 경로를 동시에 지원:
//   1) K6_TOKEN env 가 비어 있으면 Authorization 헤더 미부착 — Permissive 모드 통과.
//   2) K6_TOKEN 이 있으면 Bearer 로 부착 — auth-service 또는 외부 Keycloak 토큰을 외부에서
//      주입하는 케이스.
//
// W3C Baggage:
//   본 시스템은 cross-system 검색 차원 (owner / cost-center / priority) 를 baggage 로
//   전파 — BaggageRequestInterceptor 가 들어오는 Baggage 헤더를 화이트리스트 키만
//   캡처해 trace + log + metric 라벨까지 연결한다. 부하 시나리오는 매 요청에 baggage
//   를 박아 운영 환경의 cardinality (cost-center / priority 별 metric 분리) 가
//   부하 상황에서도 깨지지 않는지를 함께 확인한다.

const ENV_TOKEN = __ENV.K6_TOKEN || '';

/**
 * Authorization 헤더 객체. 토큰이 비어 있으면 빈 객체 (Permissive 모드 통과).
 */
export function authHeader() {
  if (!ENV_TOKEN) return {};
  return { Authorization: `Bearer ${ENV_TOKEN}` };
}

/**
 * W3C Baggage 헤더 — RFC 표준은 `key=value,key=value` 형태. owner / cost-center / priority
 * 화이트리스트 (JobBaggage.ALLOWED) 안의 키만 의미가 있고 나머지는 인터셉터에서 drop.
 *
 * 시나리오마다 cost-center 를 다르게 박아 부하 측정 분리:
 *   - submit / dag / queue-depth — research-vision (학습 잡 부서)
 *   - callback — platform-team (운영 콜백 모사)
 *   - cost-query — finops (회계 / 빌링 조회)
 *
 * @param overrides {Record<string, string>} — key=value 로 덮어쓸 항목 (cost-center / priority 등)
 */
export function baggageHeader(overrides = {}) {
  const defaults = {
    'cost-center': __ENV.K6_COST_CENTER || 'research-vision',
    priority: __ENV.K6_PRIORITY_LABEL || 'NORMAL',
  };
  const merged = { ...defaults, ...overrides };
  const entries = Object.entries(merged)
    .filter(([, v]) => v !== undefined && v !== null && String(v).length > 0)
    .map(([k, v]) => `${k}=${encodeBaggageValue(String(v))}`);
  if (entries.length === 0) return {};
  return { Baggage: entries.join(',') };
}

/**
 * Baggage value 부분의 percent-encoding — RFC 7230 (token) 외 문자가 들어가면 헤더 파싱이
 * 깨질 수 있어 안전한 케이스만 raw, 그 외는 encodeURIComponent.
 */
function encodeBaggageValue(value) {
  // 화이트리스트: alphanum + `-_.`. 그 외는 percent-encoding.
  if (/^[A-Za-z0-9._-]+$/.test(value)) return value;
  return encodeURIComponent(value);
}

/**
 * authHeader + baggageHeader 를 한 번에 만들어주는 편의 함수.
 */
export function commonHeaders(overrides = {}) {
  return {
    ...authHeader(),
    ...baggageHeader(overrides),
  };
}
