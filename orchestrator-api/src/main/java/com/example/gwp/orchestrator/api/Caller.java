package com.example.gwp.orchestrator.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 컨트롤러에서 한 줄로 호출자 컨텍스트(owner + admin 여부)를 얻기 위한 record.
 *
 * <p>JWT 가 있으면 sub 클레임 + ROLE_admin 권한 체크. 없으면 anonymous (Permissive 모드 — 로컬 dev).
 *
 * <p>{@code @AuthenticationPrincipal} 으로 컨트롤러 메서드에서 받은 {@link Jwt} 를 넘기면 됨.</p>
 */
public record Caller(String owner, boolean isAdmin) {

    public static final String ANONYMOUS = "anonymous";
    public static final String ADMIN_ROLE = "ROLE_admin";

    public static Caller from(Jwt jwt) {
        if (jwt != null) {
            return new Caller(jwt.getSubject(), hasAdminAuthority());
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
