package com.example.gwp.orchestrator.dlq

/**
 * 관리자 API rate limit port. scope 별 token bucket — read / write / bulk 가 따로
 * 카운트되어 read 가 폭발해도 write / bulk 가 살아남는다.
 *
 * 운영 구현은 Redis Lua (`admin:dlq:<ip>` 키, 분당 60 — application.yml 의
 * `gwp.dlq.admin.rate-limit-per-minute`), dev / 단위 테스트는 NoOp.
 */
interface AdminRateLimiter {

    fun allow(key: String, scope: Scope): Boolean

    enum class Scope { READ, WRITE, BULK }
}
