package com.example.gwp.orchestrator.application;

/**
 * 이미지 reference 를 로그에 안전하게 남기기 위한 마스킹.
 *
 * <p>{@link com.example.gwp.orchestrator.api.dto.JobSubmissionRequest} 의 정규식이
 * {@code user:pwd@} 형태 자격증명을 이미 차단하지만, 다음과 같은 이유로 defense-in-depth
 * 가 필요:</p>
 * <ul>
 *   <li>controller 우회 경로 (내부 콜백 / 시스템 작업) 가 추가될 가능성.</li>
 *   <li>정규식 검증이 우회되는 입력 (URL-encoded 등) 시나리오 보호.</li>
 *   <li>Bearer 토큰처럼 보이는 임의 segment ({@code @sha256:...}) 와 자격증명 분리 해석.</li>
 * </ul>
 *
 * <p>전략:</p>
 * <ul>
 *   <li>이미지 문자열 안에 {@code //user:token@} 또는 {@code user:token@} 형태가 보이면
 *       userinfo 부분을 {@code ***} 로 치환.</li>
 *   <li>{@code @sha256:digest} 는 컨테이너 표준이라 그대로 보존.</li>
 *   <li>너무 길면 256자에서 자르고 {@code ...} 로 표기.</li>
 * </ul>
 */
public final class ImageLogMask {

    private static final int MAX_LEN = 256;

    private ImageLogMask() {}

    /** 로그 표현으로 변환. null 입력은 {@code "<null>"}. */
    public static String mask(String image) {
        if (image == null) return "<null>";
        if (image.isEmpty()) return "<empty>";

        String masked = stripUserInfo(image);
        if (masked.length() > MAX_LEN) {
            masked = masked.substring(0, MAX_LEN) + "...";
        }
        return masked;
    }

    /**
     * {@code scheme://user:token@host/...} 또는 {@code user:token@host/...} 형식의
     * userinfo 를 {@code ***} 로 치환. {@code @sha256:digest} 는 보존.
     */
    private static String stripUserInfo(String s) {
        // @sha256:... 또는 @sha512:... 등 image digest 는 그대로 보존.
        // 그 외 '@' 앞에 ':' 이 있는 패턴 (user:token@) 만 자격증명으로 간주하고 마스킹.
        int at = s.indexOf('@');
        if (at < 0) return s;
        // 첫 '@' 만 보면 충분 — 이미지 문자열에 @ 가 2개 이상 들어오면 invalid 라 일단 한 번만 처리.
        String head = s.substring(0, at);
        String tail = s.substring(at);   // '@' 포함
        // tail 이 sha256/sha512 digest 면 user info 가 아니라 digest — 보존.
        if (tail.startsWith("@sha256:") || tail.startsWith("@sha512:")) {
            return s;
        }
        // head 에 ':' 가 있으면 userinfo 패턴. scheme 의 ':' (https://) 와 구분 위해
        // 마지막 ':' 위치로 판단 — scheme 다음 '//' 뒤를 userinfo 시작으로 본다.
        int slash = head.indexOf("//");
        String prefix;
        String userInfoSegment;
        if (slash >= 0) {
            prefix = head.substring(0, slash + 2);
            userInfoSegment = head.substring(slash + 2);
        } else {
            prefix = "";
            userInfoSegment = head;
        }
        if (userInfoSegment.indexOf(':') < 0) {
            return s;   // 자격증명 패턴 아님
        }
        return prefix + "***" + tail;
    }
}
