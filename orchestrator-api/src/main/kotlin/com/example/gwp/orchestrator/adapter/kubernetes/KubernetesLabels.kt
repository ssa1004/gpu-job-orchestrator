package com.example.gwp.orchestrator.adapter.kubernetes

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Kubernetes label / annotation 값으로 안전하게 사용할 수 있도록 사용자 입력을 정규화한다.
 *
 * ### K8s label value 제약
 * - 최대 63자.
 * - 비어있을 수 있고, 비어있지 않으면 alphanumeric 으로 시작·종료.
 * - 중간에 `-`, `_`, `.` 만 추가 허용.
 *
 * ### 왜 필요한가
 * JWT subject 는 보통 UUID / 이메일 / 임의 문자열이다. 이메일에는 `@` 가 들어가
 * 그대로 라벨에 넣으면 K8s API 가 거절 → dispatch 실패. 또한 사용자가 제어 가능한 값을
 * 그대로 라벨에 박으면 *라벨 인젝션* (라벨 값을 통해 다른 라벨 / annotation 으로 인식되는
 * 데이터를 끼워 넣는 공격) 가능. 이 클래스로 일관되게 sanitize.
 *
 * ### 변환 전략
 * 1. 허용 문자 (alnum, `-_.`) 만 남기고 나머지는 `_` 로 치환.
 * 2. 시작 / 끝 문자가 alphanumeric 이 아니면 양 끝을 잘라낸다.
 * 3. 결과가 63자 초과면 prefix + `-` + 10자 sha256 hex 로 잘라 충돌 위험 ↓.
 * 4. 완전히 비면 `"unknown"` fallback.
 *
 * ### 예
 * - `"alice@example.com"`    → `"alice_example.com"`
 * - `"user/with slash"`      → `"user_with_slash"`
 * - `"@bad-start"`           → `"bad-start"` (앞뒤 invalid 문자 트림)
 * - (70자짜리 긴 문자열)      → `"<52자 prefix>-<10자 hash>"` (총 63자)
 * - `null` / `""`            → `"unknown"`
 *
 * Java 호출자 (`KubernetesLabels.sanitizeLabelValue(raw)`) 그대로 동작 — `object` +
 * `@JvmStatic` 으로 static 메서드 합성.
 */
object KubernetesLabels {

    private const val MAX_LEN = 63

    /**
     * 임의 문자열을 K8s label value 로 정규화. 항상 유효한 결과를 반환 (입력이 null /
     * 비어있어도 `"unknown"` 으로 fallback).
     */
    @JvmStatic
    fun sanitizeLabelValue(raw: String?): String {
        if (raw.isNullOrEmpty()) {
            return "unknown"
        }
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '_' || c == '.'
            ) {
                sb.append(c)
            } else {
                sb.append('_')
            }
        }
        val cleaned = trimToValid(sb.toString())
        if (cleaned.isEmpty()) {
            return "unknown"
        }
        if (cleaned.length <= MAX_LEN) {
            return cleaned
        }
        // 너무 길면 prefix + 짧은 해시로 잘라 충돌을 줄인다.
        // 64자 sha256 → 16진수 prefix 10자만 사용 (collision 위험 낮음, 길이 짧음).
        val hash = shortHash(raw)
        val prefixLen = MAX_LEN - 1 - hash.length              // '-' 한자리 포함
        val prefix = trimToValid(cleaned.substring(0, minOf(prefixLen, cleaned.length)))
        return trimToValid(prefix + "-" + hash)
    }

    /**
     * 시작 / 끝 문자가 alphanumeric 이 아니면 잘라낸다. 모두 잘려 비면 빈 문자열 반환 —
     * 호출자가 fallback 처리.
     */
    private fun trimToValid(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && !isAlnum(s[start])) start++
        while (end > start && !isAlnum(s[end - 1])) end--
        return s.substring(start, end)
    }

    private fun isAlnum(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'

    private fun shortHash(raw: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(raw.toByteArray(StandardCharsets.UTF_8))
            val hex = StringBuilder(20)
            for (i in 0 until 5) {                                 // 5 byte → 10자 hex
                hex.append(String.format("%02x", digest[i]))
            }
            hex.toString()
        } catch (_: NoSuchAlgorithmException) {
            // SHA-256 은 JDK 표준 — 실제로는 도달 불가능. 안전 fallback.
            Integer.toHexString(raw.hashCode())
        }
    }
}
