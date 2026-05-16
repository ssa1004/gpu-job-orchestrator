package com.example.gwp.orchestrator.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 도메인 / 서비스에서 사용할 [Clock] 빈. 모든 시간 결정을 한 곳에서 통제 →
 * 테스트에서는 `Clock.fixed(...)` 로 교체하여 시간 관련 검증을 결정적으로 만든다.
 *
 * 표준 시간대는 UTC. DB의 `hibernate.jdbc.time_zone=UTC` 와 일치시킴.
 */
@Configuration
class ClockConfig {

    @Bean
    open fun clock(): Clock = Clock.systemUTC()
}
