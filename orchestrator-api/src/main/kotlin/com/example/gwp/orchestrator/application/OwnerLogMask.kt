package com.example.gwp.orchestrator.application

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * 로그에 owner (이메일 / JWT subject — 개인 식별 가능 정보 PII) 를 평문으로 남기지 않기
 * 위한 마스킹 유틸. 로그 검색 / 추적은 가능해야 하므로 ID 자체를 지우지 않고 *결정론적
 * 단방향 해시 prefix* 로 변환한다.
 *
 * 전략: SHA-256 prefix 12자 hex + 도메인 힌트 보존 (`@example.com` 의 도메인 부분만).
 * 같은 owner 는 항상 같은 마스크 → 운영 추적 가능, 원본 PII 는 노출되지 않음.
 *
 * 예: `alice@example.com` → `6c7e7d3c2c1b@example.com`.
 *     `uuid-style-subject-12345` → `8a4d3c1f2e9b`.
 *
 * 로그 시스템 (ELK / Loki) 으로 owner 단위 통계가 필요하면 이 마스크 prefix 가 그대로
 * group key 로 동작 — 원본 owner 매핑은 별도 안전한 lookup 테이블로 관리.
 *
 * Java 호출자 (`OwnerLogMask.mask(owner)`) 무변경 — `object` + `@JvmStatic` 으로
 * static 메서드 합성.
 */
object OwnerLogMask {

    private const val HASH_HEX_LEN = 12   // 6 bytes — 충돌 확률 충분히 낮음

    /**
     * owner 를 안전한 로그 표현으로 변환. null / blank 는 `"anonymous"` 로 fallback.
     */
    @JvmStatic
    fun mask(owner: String?): String {
        if (owner.isNullOrBlank()) {
            return "anonymous"
        }
        val at = owner.indexOf('@')
        if (at > 0 && at < owner.length - 1) {
            // 이메일 형태 — local part 는 해시, 도메인은 그대로 (운영에서 어느 도메인의
            // 사용자인지 정도는 분류용으로 유용).
            val local = owner.substring(0, at)
            val domain = owner.substring(at)
            return shortHash(local) + domain
        }
        return shortHash(owner)
    }

    private fun shortHash(raw: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(raw.toByteArray(StandardCharsets.UTF_8))
            val hex = StringBuilder(HASH_HEX_LEN)
            for (i in 0 until HASH_HEX_LEN / 2) {
                hex.append(String.format("%02x", digest[i]))
            }
            hex.toString()
        } catch (e: NoSuchAlgorithmException) {
            // SHA-256 은 JDK 표준 — 실제로는 도달 불가. 안전 fallback.
            Integer.toHexString(raw.hashCode())
        }
    }
}
