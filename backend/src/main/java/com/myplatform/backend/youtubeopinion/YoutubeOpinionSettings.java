package com.myplatform.backend.youtubeopinion;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 유튜브 의견 설정 — 켜짐 여부·등록 허용 채널·작업량 상한.
 *
 * <p><b>채널은 사용자가 설정으로 고른 것만</b> 등록할 수 있다({@code youtube-opinion.channels}, "채널ID=이름" 콤마 구분).
 * 비어 있으면 등록이 거절된다 — 코드가 임의 채널을 "신뢰하는 전문가"로 넣지 않는다. 인물 신뢰도·성적 개념은 없다.
 *
 * <p>기본값은 꺼짐. CLAUDE.md §4b 규약대로 compose 배선({@code YOUTUBE_OPINION_ENABLED}·{@code YOUTUBE_OPINION_CHANNELS})과
 * 부팅 시 상태 안내를 같이 둔다 — "기본 false + 조용한 스킵"은 아무도 모르게 안 도는 조합이다.
 */
@Component
@Slf4j
public class YoutubeOpinionSettings {

    private static final Pattern CHANNEL_ID = Pattern.compile("^[A-Za-z0-9_-]{3,64}$");

    public record Channel(String id, String name) {}

    private final boolean enabled;
    private final List<Channel> channels;
    private final int windowDays;
    private final int maxChunksPerRun;
    private final int dailyCallLimit;
    private final LocalTime quietFrom;
    private final LocalTime quietTo;
    private final String model;

    public YoutubeOpinionSettings(
            @Value("${youtube-opinion.enabled:false}") boolean enabled,
            @Value("${youtube-opinion.channels:}") String channelsCsv,
            @Value("${youtube-opinion.window-days:7}") int windowDays,
            @Value("${youtube-opinion.max-chunks-per-run:20}") int maxChunksPerRun,
            @Value("${youtube-opinion.daily-call-limit:60}") int dailyCallLimit,
            @Value("${youtube-opinion.quiet-window:07:20-08:40}") String quietWindow,
            @Value("${gemini.api.url:}") String geminiUrl) {
        this.enabled = enabled;
        this.channels = parseChannels(channelsCsv);
        this.windowDays = windowDays > 0 ? windowDays : OpinionAggregator.DEFAULT_WINDOW_DAYS;
        this.maxChunksPerRun = Math.max(1, maxChunksPerRun);
        this.dailyCallLimit = Math.max(0, dailyCallLimit);
        LocalTime[] q = parseQuietWindow(quietWindow);
        this.quietFrom = q == null ? null : q[0];
        this.quietTo = q == null ? null : q[1];
        this.model = modelName(geminiUrl);
    }

    @PostConstruct
    void notice() {
        if (!enabled) {
            log.warn("[유튜브의견] 꺼짐(youtube-opinion.enabled=false) — 관리자 등록·분석 API 는 503, 화면 섹션은 숨김. "
                    + "켜려면 YOUTUBE_OPINION_ENABLED=true (compose) 후 backend 재생성.");
        } else if (channels.isEmpty()) {
            log.warn("[유튜브의견] 켜졌지만 등록 허용 채널이 없다 — YOUTUBE_OPINION_CHANNELS=\"채널ID=이름,…\" 설정 전엔 영상 등록 불가.");
        } else {
            log.info("[유튜브의견] 켜짐 — 등록 허용 채널 {}개, 일일 분석 호출 상한 {}, 보호 시간대 {}. 자동 자막 수집은 미연결(관리자 자막 등록만).",
                    channels.size(), dailyCallLimit, quietFrom == null ? "없음" : quietFrom + "~" + quietTo);
        }
    }

    /** "id=이름,id=이름" — 형식이 틀린 항목은 건너뛰고 경고한다(조용히 삼키지 않는다). */
    static List<Channel> parseChannels(String csv) {
        List<Channel> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String entry : csv.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int eq = e.indexOf('=');
            String id = eq > 0 ? e.substring(0, eq).trim() : "";
            String name = eq > 0 ? e.substring(eq + 1).trim() : "";
            if (!CHANNEL_ID.matcher(id).matches() || name.isEmpty() || name.length() > 100) {
                log.warn("[유튜브의견] 채널 설정 형식 오류로 건너뜀: \"{}\" (\"채널ID=이름\")", e);
                continue;
            }
            if (out.stream().noneMatch(c -> c.id().equals(id))) out.add(new Channel(id, name));
        }
        return List.copyOf(out);
    }

    /** gemini.api.url 의 models/ 세그먼트 — 분석 실행에 모델 버전으로 남긴다. */
    static String modelName(String url) {
        if (url == null) return "unknown";
        int i = url.indexOf("models/");
        if (i < 0) return "unknown";
        String rest = url.substring(i + "models/".length());
        int end = rest.length();
        for (char c : new char[]{':', '?', '/'}) {
            int k = rest.indexOf(c);
            if (k >= 0 && k < end) end = k;
        }
        String m = rest.substring(0, end);
        return m.isBlank() ? "unknown" : (m.length() > 80 ? m.substring(0, 80) : m);
    }

    static LocalTime[] parseQuietWindow(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.trim().split("-");
        if (p.length != 2) return null;
        try {
            return new LocalTime[]{LocalTime.parse(p[0].trim()), LocalTime.parse(p[1].trim())};
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 모닝브리핑·재료 워밍 시간대 — 이때는 분석을 시작하지 않는다(전역 Gemini 제한기를 나눠 쓰므로). */
    public boolean inQuietWindow(LocalTime t) {
        if (quietFrom == null) return false;
        return quietFrom.isBefore(quietTo)
                ? !t.isBefore(quietFrom) && t.isBefore(quietTo)
                : !t.isBefore(quietFrom) || t.isBefore(quietTo);
    }

    public Optional<Channel> channel(String id) {
        return channels.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public boolean isEnabled() { return enabled; }
    public List<Channel> getChannels() { return channels; }
    public int getWindowDays() { return windowDays; }
    public int getMaxChunksPerRun() { return maxChunksPerRun; }
    public int getDailyCallLimit() { return dailyCallLimit; }
    public String getModel() { return model; }
    public String quietWindowLabel() { return quietFrom == null ? null : quietFrom + "~" + quietTo; }
}
