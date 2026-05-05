package com.example.gwp.orchestrator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러 활성화. {@link com.example.gwp.orchestrator.outbox.OutboxRelay} 등이 사용.
 * Spring 컨벤션상 @EnableScheduling 은 @Component 가 아닌 @Configuration 에 둔다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
