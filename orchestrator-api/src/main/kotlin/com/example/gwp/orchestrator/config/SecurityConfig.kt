package com.example.gwp.orchestrator.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain

/**
 * 운영 보안 설정: OAuth2 Resource Server (자기는 토큰 발급 X, 외부 IdP 가 발급한 토큰을
 * 검증하는 역할만) + JWT (JSON Web Token — 서명된 토큰으로 사용자 정보를 담는 표준) 검증.
 * gwp.security.jwt.enabled=true 일 때만 활성화. 로컬 dev 는 PermissiveSecurityConfig
 * (인증 검사 없이 모든 요청 허용) 사용.
 */
@Configuration
@ConditionalOnProperty(name = ["gwp.security.jwt.enabled"], havingValue = "true")
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    open fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/actuator/health/**", "/actuator/info",
                        "/v3/api-docs/**", "/swagger", "/swagger-ui/**", "/swagger-ui.html",
                    ).permitAll()
                    // 인증 우회. 외부 노출은 NetworkPolicy (Pod 사이 트래픽 방화벽 규칙) 로 차단
                    .requestMatchers("/actuator/prometheus").permitAll()
                    // 콜백은 헤더 공유 시크릿(X-GWP-Callback-Secret)으로 별도 검증
                    .requestMatchers("/internal/**").permitAll()
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().denyAll()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()) }
            }
        return http.build()
    }

    /**
     * JWT 의 scope 클레임 (토큰에 담긴 권한 키 — 어떤 동작을 허용받았는지) → SCOPE_*,
     * realm_access.roles (Keycloak 이라는 IdP 의 표준 키 위치) → ROLE_* 매핑.
     * 다른 IdP (Identity Provider — OAuth2 발급자) 사용 시 이 부분만 교체.
     */
    @Bean
    open fun jwtAuthConverter(): Converter<Jwt, AbstractAuthenticationToken> {
        val scopes = JwtGrantedAuthoritiesConverter()
        scopes.setAuthorityPrefix("SCOPE_")
        scopes.setAuthoritiesClaimName("scope")

        val converter = JwtAuthenticationConverter()
        converter.setPrincipalClaimName("sub")
        converter.setJwtGrantedAuthoritiesConverter { jwt ->
            val all: MutableCollection<GrantedAuthority> = ArrayList(scopes.convert(jwt) ?: emptyList())
            val realmAccess = jwt.getClaim<Any>("realm_access")
            if (realmAccess is Map<*, *>) {
                val roles = realmAccess["roles"]
                if (roles is List<*>) {
                    roles.asSequence()
                        .filterIsInstance<String>()
                        .map { "ROLE_$it" }
                        .map { SimpleGrantedAuthority(it) }
                        .forEach { all.add(it) }
                }
            }
            all
        }
        return converter
    }
}
