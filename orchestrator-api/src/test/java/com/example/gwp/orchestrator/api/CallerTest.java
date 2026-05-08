package com.example.gwp.orchestrator.api;

import com.example.gwp.orchestrator.domain.AccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Caller 의 sub 클레임 처리 검증 — 운영에서 IdP 설정 사고로 sub 가 비어 있는 토큰이 들어와도
 * owner=null 이 흐름에 흘러가 NPE 또는 owner=null row 가 생기지 않게 막는다.
 */
class CallerTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void from_nullJwt_inPermissive_returnsAnonymous() {
        Caller caller = Caller.from(null);
        assertThat(caller.owner()).isEqualTo(Caller.ANONYMOUS);
        assertThat(caller.isAdmin()).isFalse();
    }

    @Test
    void from_jwtWithSub_returnsOwner() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("sub", "alice")
                .build();
        Caller caller = Caller.from(jwt);
        assertThat(caller.owner()).isEqualTo("alice");
    }

    @Test
    void from_jwtWithMissingSub_throwsAccessDenied() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claims((Map<String, Object> c) -> c.remove("sub"))
                .build();
        assertThatThrownBy(() -> Caller.from(jwt))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void from_jwtWithBlankSub_throwsAccessDenied() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("sub", "   ")
                .build();
        assertThatThrownBy(() -> Caller.from(jwt))
                .isInstanceOf(AccessDeniedException.class);
    }
}
