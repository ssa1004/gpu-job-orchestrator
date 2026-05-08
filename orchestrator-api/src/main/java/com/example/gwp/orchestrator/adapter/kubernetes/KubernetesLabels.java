package com.example.gwp.orchestrator.adapter.kubernetes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Kubernetes label / annotation 값으로 안전하게 사용할 수 있도록 사용자 입력을 정규화한다.
 *
 * <p><b>K8s label value 제약</b> (Kubernetes 공식 문서 — Names and IDs):
 * <ul>
 *   <li>최대 63자.</li>
 *   <li>비어있을 수 있고, 비어있지 않으면 alphanumeric 으로 시작 / 끝나야 한다.</li>
 *   <li>중간에 {@code -}, {@code _}, {@code .} 만 추가로 허용.</li>
 * </ul>
 *
 * <p>JWT subject 는 보통 UUID / 이메일 / sub-string 인데, 이메일에는 {@code @} 가 들어가
 * 그대로 라벨에 넣으면 K8s API 가 거절 → dispatch 실패. 또한 사용자가 제어 가능한
 * 입력을 그대로 라벨에 박으면 라벨 인젝션 (라벨 값을 통해 다른 라벨 / annotation 으로
 * 인지되는 데이터를 끼워 넣는 공격) 가능성도 있다. 이 클래스로 일관되게 sanitize.</p>
 *
 * <p>전략: 허용 문자만 남기고 나머지는 {@code _}. 결과가 너무 길면 64자 prefix + 10자
 * 해시 suffix 로 잘라 충돌을 줄인다 (총 ≤ 63자). 결과가 비거나 시작 / 끝 문자가 invalid
 * 면 양 끝을 {@code _x} 로 padding 후 다시 잘라 K8s 형식을 맞춘다.</p>
 */
public final class KubernetesLabels {

    private static final int MAX_LEN = 63;

    private KubernetesLabels() {}

    /**
     * 임의 문자열을 K8s label value 로 정규화. 항상 유효한 결과를 반환 (입력이 null /
     * 비어있어도 {@code "unknown"} 으로 fallback).
     */
    public static String sanitizeLabelValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String cleaned = trimToValid(sb.toString());
        if (cleaned.isEmpty()) {
            return "unknown";
        }
        if (cleaned.length() <= MAX_LEN) {
            return cleaned;
        }
        // 너무 길면 prefix + 짧은 해시로 잘라 충돌을 줄인다.
        // 64자 sha256 → 16진수 prefix 10자만 사용 (collision 위험 낮음, 길이 짧음).
        String hash = shortHash(raw);
        int prefixLen = MAX_LEN - 1 - hash.length();              // '-' 한자리 포함
        String prefix = trimToValid(cleaned.substring(0, Math.min(prefixLen, cleaned.length())));
        return trimToValid(prefix + "-" + hash);
    }

    /**
     * 시작 / 끝 문자가 alphanumeric 이 아니면 잘라낸다. 모두 잘려 비면 빈 문자열 반환 —
     * 호출자가 fallback 처리.
     */
    private static String trimToValid(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && !isAlnum(s.charAt(start))) start++;
        while (end > start && !isAlnum(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isAlnum(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static String shortHash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(20);
            for (int i = 0; i < 5; i++) {                          // 5 byte → 10자 hex
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 표준 — 실제로는 도달 불가능. 안전 fallback.
            return Integer.toHexString(raw.hashCode());
        }
    }
}
