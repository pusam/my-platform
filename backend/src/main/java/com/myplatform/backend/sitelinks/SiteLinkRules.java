package com.myplatform.backend.sitelinks;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 내 사이트 바로가기 입력 규칙 (순수함수).
 *
 * <p>주소는 화면에서 링크({@code href})가 된다 — 그래서 저장하기 전에 여기서 거른다:
 * <ul>
 *   <li>{@code http}·{@code https} 만 받는다. {@code javascript:}·{@code data:}·{@code file:} 같은 주소는 누르면 스크립트가 돌거나
 *       엉뚱한 것을 연다.</li>
 *   <li>호스트가 있어야 한다(상대 주소·{@code https:///path} 거절).</li>
 *   <li>{@code https://아이디:비밀번호@호스트} 처럼 주소에 계정 정보를 싣지 않는다 — 비밀번호를 표에 남기지 않는다.</li>
 * </ul>
 * 어긋나면 {@link IllegalArgumentException}(전역 핸들러가 400 으로 바꾼다). 화면도 같은 검사를 한 번 더 한다
 * ({@code frontend/src/utils/siteLinks.js}) — 한쪽만 믿지 않는다.
 */
public final class SiteLinkRules {

    public static final int MAX_NAME = 60;
    public static final int MAX_DESCRIPTION = 200;
    public static final int MAX_URL = 500;
    /** 계정당 줄 수 상한 — 링크 모음이지 저장소가 아니다. */
    public static final int MAX_LINKS_PER_USER = 100;

    private SiteLinkRules() {}

    public static String name(String raw) {
        String v = raw == null ? "" : raw.strip();
        if (v.isEmpty()) throw new IllegalArgumentException("이름을 입력하세요.");
        if (v.length() > MAX_NAME) throw new IllegalArgumentException("이름은 " + MAX_NAME + "자까지입니다.");
        return v;
    }

    /** 비어 있으면 null(설명 없음). */
    public static String description(String raw) {
        String v = raw == null ? "" : raw.strip();
        if (v.isEmpty()) return null;
        if (v.length() > MAX_DESCRIPTION) throw new IllegalArgumentException("설명은 " + MAX_DESCRIPTION + "자까지입니다.");
        return v;
    }

    public static String url(String raw) {
        String v = raw == null ? "" : raw.strip();
        if (v.isEmpty()) throw new IllegalArgumentException("주소를 입력하세요.");
        if (v.length() > MAX_URL) throw new IllegalArgumentException("주소는 " + MAX_URL + "자까지입니다.");
        // 공백·제어문자가 섞인 주소는 브라우저마다 다르게 읽는다("java\nscript:" 류) — 통째로 거절한다.
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalArgumentException("주소에 공백이나 줄바꿈을 넣을 수 없습니다.");
            }
        }
        URI uri;
        try {
            uri = new URI(v);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("주소 형식이 올바르지 않습니다.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("주소는 http:// 또는 https:// 로 시작해야 합니다.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("주소에 사이트 이름(호스트)이 없습니다.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("주소에 아이디·비밀번호를 넣지 마세요.");
        }
        return v;
    }
}
