package com.example.gwp.orchestrator.config;

import com.example.gwp.orchestrator.application.NoopQuotaLock;
import com.example.gwp.orchestrator.application.PgAdvisoryQuotaLock;
import com.example.gwp.orchestrator.application.QuotaLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QuotaLock 빈 wiring — owner 단위 quota 검사 동시성 가드.
 *
 * <p>운영 (prod / Postgres): {@link PgAdvisoryQuotaLock} — {@code pg_advisory_xact_lock}
 * 으로 같은 owner 의 동시 제출을 직렬화 → over-commit race 차단.</p>
 *
 * <p>H2 / 단위 테스트 (default): {@link NoopQuotaLock} — 단일 인스턴스
 * 인메모리라 race window 없음.</p>
 *
 * <p>활성화 스위치는 {@code gwp.quota.advisory-lock-enabled} (default false).
 * application-prod.yml 에서 true 로 override.</p>
 */
@Configuration
public class QuotaLockConfig {

    @Bean
    @ConditionalOnProperty(name = "gwp.quota.advisory-lock-enabled", havingValue = "true")
    public QuotaLock pgAdvisoryQuotaLock() {
        return new PgAdvisoryQuotaLock();
    }

    @Bean
    @ConditionalOnProperty(name = "gwp.quota.advisory-lock-enabled",
            havingValue = "false", matchIfMissing = true)
    public QuotaLock noopQuotaLock() {
        return new NoopQuotaLock();
    }
}
