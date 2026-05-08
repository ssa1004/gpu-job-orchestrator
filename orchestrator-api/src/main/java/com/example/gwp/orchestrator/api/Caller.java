package com.example.gwp.orchestrator.api;

import com.example.gwp.orchestrator.domain.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 컨트롤러에서 한 줄로 호출자 컨텍스트 (owner + admin 여부) 를 얻기 위한 record.
 *
 * <p>JWT 가 있으면 sub (subject — JWT 의 사용자 ID 클레임) + ROLE_admin 권한 체크. 없으면
 * anonymous (Permissive 모드 — 로컬 dev 에서 인증 없이 동작시키는 설정).
 *
 * <p>{@code @AuthenticationPrincipal} 으로 컨트롤러 메서드에서 받은 {@link Jwt} 를 넘기면 됨.</p>
 *
 * <p><b>sub 가 비어 있는 JWT 처리</b>: 운영 환경에서 IdP 설정 오류 / 비정상 토큰으로
 * sub 클레임이 없는 경우, owner 가 null 인 채로 흐름이 이어지면 NPE 또는 owner 가
 * null 인 row 가 만들어져 회계 사고 위험. {@link #from(Jwt)} 가 sub null/blank 면
 * {@link AccessDeniedException} 으로 거절 — Spring Security 검증을 통과한
 * 토큰이라도 식별 가능한 principal 이 없으면 어떤 동작도 허용하지 않는다.</p>
 */
public record Caller(String owner, boolean isAdmin) {

    public static final String ANONYMOUS = "anonymous";
    public static final String ADMIN_ROLE = "ROLE_admin";

    public static Caller from(Jwt jwt) {
        if (jwt != null) {
            String sub = jwt.getSubject();
            if (sub == null || sub.isBlank()) {
                throw new AccessDeniedException(null, ANONYMOUS);
            }
            return new Caller(sub, hasAdminAuthority());
        }
        // Permissive 모드 (PermissiveSecurityConfig). SecurityContext 에서 다시 읽어서 ROLE 체크.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return new Caller(ANONYMOUS, false);
        }
        String name = auth.getName();
        return new Caller(
                name != null && !name.isBlank() ? name : ANONYMOUS,
                hasAdminAuthority()
        );
    }

    private static boolean hasAdminAuthority() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ADMIN_ROLE.equals(a.getAuthority()));
    }
}
