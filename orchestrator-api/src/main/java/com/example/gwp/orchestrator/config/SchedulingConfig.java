package com.example.gwp.orchestrator.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * 스케줄러 활성화 + ShedLock 활성화.
 *
 * <p>{@link com.example.gwp.orchestrator.outbox.OutboxRelay} / {@link
 * com.example.gwp.orchestrator.application.PreemptionScheduler} / {@link
 * com.example.gwp.orchestrator.application.DependencyScanScheduler} 가
 * @SchedulerLock 어노테이션과 함께 사용된다 — 다중 인스턴스에서도 한 번에 한 인스턴스만
 * 메서드를 실행하게 보장.</p>
 *
 * <p>{@code defaultLockAtMostFor} 는 "락 보유자가 죽었을 때 다른 인스턴스가 얼마나 기다린
 * 뒤 강제로 인계받을지" 의 fallback. 메서드 단위로 {@code @SchedulerLock(lockAtMostFor)}
 * 로 override 한다.</p>
 *
 * <p>Spring 컨벤션상 @EnableScheduling 은 @Component 가 아닌 @Configuration 에 둔다.</p>
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()           // DB 시각을 사용 — 인스턴스 시계 차이로 인한 락 만료 오작동 방지
                        .build()
        );
    }
}
