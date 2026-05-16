package com.example.gwp.orchestrator.observability.baggage

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.servlet.HandlerInterceptor
import java.util.LinkedHashMap

/**
 * 컨트롤러 진입 직전에 SecurityContext / inbound 헤더에서 baggage 후보를 모아 활성화 →
 * 컨트롤러 + downstream service 호출이 모두 같은 trace 안에서 owner / cost-center /
 * priority 를 자동 보유. 컨트롤러 응답 후 [afterCompletion] 에서 scope close.
 *
 * ### baggage 의 출처 우선순위
 * 1. **inbound 헤더 propagation** (X-Baggage-* 또는 W3C baggage) — 이미 다른 서비스에서
 *    흘러온 trace 면 그쪽이 채워둔 baggage 를 *그대로* 이어 받음. (이 경로는 Micrometer
 *    Tracing 의 propagator 가 자동 처리, 우리는 *덮어쓰지 않도록* 검사만.)
 * 2. **JWT claim** — 자기가 인증한 사용자라면 JWT 에서 owner=sub, cost-center=
 *    cost_center claim, priority 는 요청 path / param 에서 (이번 라운드에서는 owner /
 *    cost-center 만 자동 채움; priority 는 도메인 layer 에서 별도로).
 * 3. **Permissive (anonymous)** — 로컬 dev 모드. owner=anonymous 만.
 *
 * ### 왜 Filter 가 아닌 HandlerInterceptor
 * - Filter 는 Spring Security 보다 먼저 / 후 모두 동작하지만, 우리가 필요한 건 *인증 끝난 후*
 *   SecurityContext 가 채워진 시점. Spring Security 의 FilterChain 끝난 뒤 동작하는 위치는
 *   FilterChainProxy 이후의 dispatcher → HandlerInterceptor 가 자연스럽다.
 * - 또한 baggage scope 는 thread-local 이라 컨트롤러 메서드 안에서 닫혀야 한다 —
 *   interceptor 의 preHandle / afterCompletion 라이프사이클이 이를 보장.
 */
class BaggageHandlerInterceptor(private val populator: BaggagePopulator) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val baggageValues = collectBaggage()
        if (baggageValues.isEmpty()) return true

        val scope = populator.activate(baggageValues)
        // request attribute 에 박아 두고 afterCompletion 에서 close — interceptor 인스턴스 자체는
        // singleton 이라 thread-safe 한 보관소가 필요.
        request.setAttribute(SCOPE_ATTRIBUTE, scope)
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        val scope = request.getAttribute(SCOPE_ATTRIBUTE)
        if (scope is BaggagePopulator.BaggageScope) {
            try {
                scope.close()
            } finally {
                request.removeAttribute(SCOPE_ATTRIBUTE)
            }
        }
    }

    /**
     * 현재 SecurityContext 에서 baggage 후보 추출. JWT 가 있으면 sub / cost_center 를 사용,
     * 없으면 owner=auth.getName() (또는 anonymous).
     */
    private fun collectBaggage(): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        val auth = SecurityContextHolder.getContext().authentication ?: return values

        val principal = auth.principal
        if (principal is Jwt) {
            putIfPresent(values, JobBaggage.OWNER, principal.subject)
            val center = principal.claims[COST_CENTER_CLAIM]
            if (center != null) putIfPresent(values, JobBaggage.COST_CENTER, center.toString())
        } else if (auth.isAuthenticated) {
            putIfPresent(values, JobBaggage.OWNER, auth.name)
        }
        return values
    }

    private fun putIfPresent(values: MutableMap<String, String>, key: String, value: String?) {
        if (JobBaggage.isAllowed(key, value)) values[key] = value!!
    }

    companion object {
        private val SCOPE_ATTRIBUTE = BaggageHandlerInterceptor::class.java.name + ".scope"

        /** JWT cost-center 클레임 이름 — IdP 컨벤션에 맞춰. */
        const val COST_CENTER_CLAIM = "cost_center"
    }
}
