package com.example.gwp.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * 도메인 / 서비스에서 사용할 {@link Clock} 빈. 모든 시간 결정을 한 곳에서 통제 →
 * 테스트에서는 {@code Clock.fixed(...)} 로 교체하여 시간 관련 검증을 결정적으로 만든다.
 *
 * <p>표준 시간대는 UTC. DB의 {@code hibernate.jdbc.time_zone=UTC} 와 일치시킴.</p>
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
