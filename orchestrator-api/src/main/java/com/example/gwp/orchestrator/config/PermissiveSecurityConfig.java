package com.example.gwp.orchestrator.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 로컬 dev / 테스트용 보안 설정. JWT 검증 없이 모든 요청 허용.
 * gwp.security.jwt.enabled 가 미설정이거나 false 일 때 활성화.
 * 운영에서는 SecurityConfig 가 적용됨.
 */
@Configuration
@ConditionalOnProperty(name = "gwp.security.jwt.enabled", havingValue = "false", matchIfMissing = true)
public class PermissiveSecurityConfig {

    @Bean
    public SecurityFilterChain permissiveFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable());
        return http.build();
    }
}
