package com.example.gwp.orchestrator.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * 캐시 활성화. CacheManager 빈은 Spring Boot 자동 설정에 위임:
 * <ul>
 *   <li>default: ConcurrentMapCacheManager (in-memory) - 로컬 dev / 단위 테스트</li>
 *   <li>prod: RedisCacheManager - spring-boot-starter-data-redis 가 자동 구성</li>
 * </ul>
 * 캐시 타입 분기는 application.yml 의 spring.cache.type 으로 제어.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
