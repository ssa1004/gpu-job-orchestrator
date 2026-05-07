package com.example.gwp.orchestrator.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 운영 보안 설정: OAuth2 Resource Server (자기는 토큰 발급 X, 외부 IdP 가 발급한 토큰을
 * 검증하는 역할만) + JWT (JSON Web Token — 서명된 토큰으로 사용자 정보를 담는 표준) 검증.
 * gwp.security.jwt.enabled=true 일 때만 활성화. 로컬 dev 는 PermissiveSecurityConfig
 * (인증 검사 없이 모든 요청 허용) 사용.
 */
@Configuration
@ConditionalOnProperty(name = "gwp.security.jwt.enabled", havingValue = "true")
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health/**", "/actuator/info",
                                "/v3/api-docs/**", "/swagger", "/swagger-ui/**", "/swagger-ui.html"
                        ).permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()  // 인증 우회. 외부 노출은 NetworkPolicy (Pod 사이 트래픽 방화벽 규칙) 로 차단
                        .requestMatchers("/internal/**").permitAll()           // 콜백은 헤더 공유 시크릿(X-GWP-Callback-Secret)으로 별도 검증
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
                );
        return http.build();
    }

    /**
     * JWT 의 scope 클레임 (토큰에 담긴 권한 키 — 어떤 동작을 허용받았는지) → SCOPE_*,
     * realm_access.roles (Keycloak 이라는 IdP 의 표준 키 위치) → ROLE_* 매핑.
     * 다른 IdP (Identity Provider — OAuth2 발급자) 사용 시 이 부분만 교체.
     */
    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthorityPrefix("SCOPE_");
        scopes.setAuthoritiesClaimName("scope");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<org.springframework.security.core.GrantedAuthority> all =
                    new ArrayList<>(scopes.convert(jwt));
            Object realmAccess = jwt.getClaim("realm_access");
            if (realmAccess instanceof Map<?, ?> m && m.get("roles") instanceof List<?> roles) {
                roles.stream()
                        .filter(r -> r instanceof String)
                        .map(r -> "ROLE_" + r)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .forEach(all::add);
            }
            return all;
        });
        return converter;
    }
}
