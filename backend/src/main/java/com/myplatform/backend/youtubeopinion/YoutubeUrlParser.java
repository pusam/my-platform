package com.myplatform.backend.youtubeopinion;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * YouTube 영상 주소 → 영상 ID. 순수 함수, 단일 출처.
 *
 * <p><b>서버는 이 주소를 가져오지 않는다</b> — 허용된 호스트의 정해진 경로 모양에서 11자 영상 ID 만 뽑고,
 * 이후로는 ID 만 쓴다. 그래서 임의 URL 을 넣어도 서버가 그 주소로 요청을 보내는 일(SSRF)이 없다.
 * 닮은꼴 호스트({@code youtube.com.example.org}), 사용자정보·포트가 붙은 주소, 다른 스킴은 거절한다.
 */
public final class YoutubeUrlParser {

    private YoutubeUrlParser() {}

    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Set<String> YOUTUBE_HOSTS = Set.of("www.youtube.com", "youtube.com", "m.youtube.com");
    private static final int MAX_URL_LENGTH = 300;

    /** 허용된 주소 모양이면 영상 ID, 아니면 빈 값. */
    public static Optional<String> extractVideoId(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.trim();
        if (s.isEmpty() || s.length() > MAX_URL_LENGTH) return Optional.empty();
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            return Optional.empty();
        }
        if (uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getHost() == null) return Optional.empty();

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String id = null;
        if (host.equals("youtu.be")) {
            id = segment(path, 1);
        } else if (YOUTUBE_HOSTS.contains(host)) {
            if (path.equals("/watch")) {
                id = queryParam(uri.getRawQuery(), "v");
            } else if (path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")) {
                id = segment(path, 2);
            }
        }
        return isValidVideoId(id) ? Optional.of(id) : Optional.empty();
    }

    public static boolean isValidVideoId(String id) {
        return id != null && VIDEO_ID.matcher(id).matches();
    }

    /** 발언 시점으로 가는 원문 링크 — ID 형식이 틀리면 만들지 않는다. */
    public static String timestampUrl(String videoId, int startSec) {
        if (!isValidVideoId(videoId)) throw new IllegalArgumentException("영상 ID 형식이 아닙니다: " + videoId);
        return "https://www.youtube.com/watch?v=" + videoId + "&t=" + Math.max(0, startSec) + "s";
    }

    private static String segment(String path, int index) {
        String[] parts = path.split("/");
        return parts.length > index ? parts[index] : null;
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }
}
