package com.example.gwp.orchestrator.api

import com.example.gwp.orchestrator.domain.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt

/**
 * 컨트롤러에서 한 줄로 호출자 컨텍스트 (owner + admin 여부) 를 얻기 위한 record.
 *
 * JWT 가 있으면 sub (subject — JWT 의 사용자 ID 클레임) + ROLE_admin 권한 체크. 없으면
 * anonymous (Permissive 모드 — 로컬 dev 에서 인증 없이 동작시키는 설정).
 *
 * `@AuthenticationPrincipal` 으로 컨트롤러 메서드에서 받은 [Jwt] 를 넘기면 됨.
 *
 * **sub 가 비어 있는 JWT 처리**: 운영 환경에서 IdP 설정 오류 / 비정상 토큰으로
 * sub 클레임이 없는 경우, owner 가 null 인 채로 흐름이 이어지면 NPE 또는 owner 가
 * null 인 row 가 만들어져 회계 사고 위험. [from] 가 sub null/blank 면
 * [AccessDeniedException] 으로 거절 — Spring Security 검증을 통과한
 * 토큰이라도 식별 가능한 principal 이 없으면 어떤 동작도 허용하지 않는다.
 *
 * Java 호출자 (`caller.owner()` / `Caller.from(jwt)` / `Caller.ANONYMOUS`) 그대로 동작 —
 * `@JvmRecord data class` + companion `@JvmField` / `@JvmStatic` 로 record-style 호환.
 */
@JvmRecord
data class Caller(val owner: String, val isAdmin: Boolean) {

    companion object {

        @JvmField
        val ANONYMOUS: String = "anonymous"

        @JvmField
        val ADMIN_ROLE: String = "ROLE_admin"

        @JvmStatic
        fun from(jwt: Jwt?): Caller {
            if (jwt != null) {
                val sub = jwt.subject
                if (sub.isNullOrBlank()) {
                    throw AccessDeniedException(null, ANONYMOUS)
                }
                return Caller(sub, hasAdminAuthority())
            }
            // Permissive 모드 (PermissiveSecurityConfig). SecurityContext 에서 다시 읽어서 ROLE 체크.
            val auth = SecurityContextHolder.getContext().authentication
            if (auth == null || !auth.isAuthenticated) {
                return Caller(ANONYMOUS, false)
            }
            val name = auth.name
            return Caller(
                if (!name.isNullOrBlank()) name else ANONYMOUS,
                hasAdminAuthority(),
            )
        }

        private fun hasAdminAuthority(): Boolean {
            val auth = SecurityContextHolder.getContext().authentication
            return auth != null && auth.authorities.any { ADMIN_ROLE == it.authority }
        }
    }
}
