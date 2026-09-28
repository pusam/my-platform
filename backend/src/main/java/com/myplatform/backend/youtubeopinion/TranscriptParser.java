package com.myplatform.backend.youtubeopinion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 관리자 등록 자막 → 정규화 큐. 순수 함수.
 *
 * <p>받는 형식: SRT · WebVTT({@code <v 이름>} 화자 태그) · 타임스탬프 텍스트({@code [mm:ss] 내용} / {@code hh:mm:ss 내용}).
 * 모든 태그를 걷어낸 <b>평문</b>만 남긴다 — 자막은 분석 대상 데이터이고 화면에 HTML 로 나가지 않는다.
 * 크기·큐 수·길이·시간 순서를 검사해 어긋나면 이유를 담아 거절한다(조용히 잘라 쓰지 않는다).
 */
public final class TranscriptParser {

    private TranscriptParser() {}

    public static final int MAX_BYTES = 512 * 1024;
    public static final int MAX_CUES = 6_000;
    public static final int MAX_DURATION_SEC = 6 * 3600;
    public static final int MAX_CUE_CHARS = 1_000;
    public static final int MAX_TOTAL_CHARS = 300_000;
    /** 텍스트 형식 마지막 줄의 길이(다음 줄 시각이 없으므로). */
    static final int TEXT_LAST_CUE_SEC = 5;

    /** 정규화 큐 — 초 단위(원문 링크 시점에 충분), 화자는 확정된 경우에만. */
    public record Cue(int startSec, int endSec, String speaker, String text) {}

    public record Parsed(YtTranscript.Format format, List<Cue> cues, int durationSec) {}

    private static final Pattern TIMING = Pattern.compile(
            "^\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{2}(?:[.,]\\d{1,3})?)\\s*-->\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{2}(?:[.,]\\d{1,3})?)(?:\\s.*)?$");
    private static final Pattern TEXT_LINE = Pattern.compile(
            "^\\s*\\[?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})\\]?\\s+(.+)$");
    private static final Pattern VOICE = Pattern.compile("<v(?:\\.[^\\s>]*)?\\s+([^>]{1,50})>");
    private static final Pattern TAG = Pattern.compile("<[^>]{0,200}>");
    private static final Pattern SPEAKER_PREFIX = Pattern.compile("^([^\\s:：\\[\\]()]{1,20}(?:\\s[^\\s:：\\[\\]()]{1,10})?)\\s*[:：]\\s*(.+)$");

    public static Parsed parse(String content, YtTranscript.Format declared) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("자막 내용이 비어 있습니다.");
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("자막이 너무 큽니다(최대 " + (MAX_BYTES / 1024) + "KB).");
        }
        String text = content.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        YtTranscript.Format format = declared != null ? declared : detect(text);
        List<Cue> cues = switch (format) {
            case VTT -> parseBlocks(text, true);
            case SRT -> parseBlocks(text, false);
            case TEXT -> parseTextLines(text);
        };
        return new Parsed(format, validate(cues), cues.isEmpty() ? 0 : cues.get(cues.size() - 1).endSec());
    }

    static YtTranscript.Format detect(String text) {
        String head = text.stripLeading();
        if (head.startsWith("WEBVTT")) return YtTranscript.Format.VTT;
        return text.contains("-->") ? YtTranscript.Format.SRT : YtTranscript.Format.TEXT;
    }

    /**
     * 출연자 이름으로 시작하는 줄("홍길동: …")에서만 화자를 확정한다 — 명단에 없는 접두어("주의: …")는 본문으로 둔다.
     * VTT {@code <v>} 태그 화자도 명단에 없으면 버린다(발언자를 모르면 추측하지 않는다).
     *
     * @param participantsByKey {@link NameKeys#key} → 표시 이름
     */
    public static List<Cue> applyParticipants(List<Cue> cues, Map<String, String> participantsByKey) {
        List<Cue> out = new ArrayList<>(cues.size());
        for (Cue c : cues) {
            String speaker = null;
            String body = c.text();
            if (c.speaker() != null) {
                speaker = participantsByKey.get(NameKeys.key(c.speaker()));
            } else {
                Matcher m = SPEAKER_PREFIX.matcher(body);
                if (m.matches()) {
                    String display = participantsByKey.get(NameKeys.key(m.group(1)));
                    if (display != null) {
                        speaker = display;
                        body = m.group(2).trim();
                    }
                }
            }
            out.add(new Cue(c.startSec(), c.endSec(), speaker, body));
        }
        return out;
    }

    public static Map<String, String> participantKeyMap(Collection<String> displayNames) {
        return displayNames.stream().filter(n -> NameKeys.key(n) != null)
                .collect(Collectors.toMap(NameKeys::key, Function.identity(), (a, b) -> a));
    }

    // ---------------------------------------------------------------- SRT / VTT

    private static List<Cue> parseBlocks(String text, boolean vtt) {
        List<Cue> cues = new ArrayList<>();
        String[] blocks = text.split("\\n\\s*\\n");
        for (String block : blocks) {
            String[] lines = block.strip().split("\\n");
            if (lines.length == 0 || lines[0].isBlank()) continue;
            if (vtt && (lines[0].startsWith("WEBVTT") || lines[0].startsWith("NOTE")
                    || lines[0].startsWith("STYLE") || lines[0].startsWith("REGION"))) continue;
            int t = -1;
            for (int i = 0; i < Math.min(lines.length, 3); i++) {
                if (TIMING.matcher(lines[i]).matches()) { t = i; break; }
            }
            if (t < 0) throw new IllegalArgumentException("시간 표기(00:00:00,000 --> …)가 없는 자막 블록이 있습니다: \"" + abbreviate(lines[0]) + "\"");
            Matcher m = TIMING.matcher(lines[t]);
            m.matches();
            int start = toSeconds(m.group(1));
            int end = toSeconds(m.group(2));
            StringBuilder body = new StringBuilder();
            String speaker = null;
            for (int i = t + 1; i < lines.length; i++) {
                String line = lines[i];
                if (vtt && speaker == null) {
                    Matcher v = VOICE.matcher(line);
                    if (v.find()) speaker = v.group(1).trim();
                }
                String clean = cleanText(line);
                if (!clean.isEmpty()) body.append(body.isEmpty() ? "" : " ").append(clean);
            }
            if (body.isEmpty()) continue;
            cues.add(new Cue(start, Math.max(start, end), speaker, body.toString()));
        }
        return cues;
    }

    // ---------------------------------------------------------------- 타임스탬프 텍스트

    private static List<Cue> parseTextLines(String text) {
        List<int[]> times = new ArrayList<>();
        List<StringBuilder> bodies = new ArrayList<>();
        for (String raw : text.split("\\n")) {
            if (raw.isBlank()) continue;
            Matcher m = TEXT_LINE.matcher(raw);
            if (m.matches()) {
                times.add(new int[]{toSeconds(m.group(1))});
                bodies.add(new StringBuilder(cleanText(m.group(2))));
            } else if (!bodies.isEmpty()) {
                String clean = cleanText(raw);
                if (!clean.isEmpty()) bodies.get(bodies.size() - 1).append(' ').append(clean);
            } else {
                throw new IllegalArgumentException("첫 줄에 시각이 없습니다 — \"[00:12] 내용\" 또는 \"00:00:12 내용\" 형식이어야 합니다.");
            }
        }
        List<Cue> cues = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            int start = times.get(i)[0];
            int end = i + 1 < times.size() ? Math.max(start, times.get(i + 1)[0]) : start + TEXT_LAST_CUE_SEC;
            String body = bodies.get(i).toString().trim();
            if (!body.isEmpty()) cues.add(new Cue(start, end, null, body));
        }
        return cues;
    }

    // ---------------------------------------------------------------- 공통

    private static List<Cue> validate(List<Cue> cues) {
        if (cues.isEmpty()) throw new IllegalArgumentException("읽을 수 있는 자막 줄이 없습니다.");
        if (cues.size() > MAX_CUES) throw new IllegalArgumentException("자막 줄이 너무 많습니다(최대 " + MAX_CUES + "개).");
        int total = 0;
        int prevStart = -1;
        for (Cue c : cues) {
            if (c.startSec() < prevStart) {
                throw new IllegalArgumentException("시간 순서가 뒤집힌 자막 줄이 있습니다(" + formatClock(c.startSec()) + ").");
            }
            if (c.endSec() > MAX_DURATION_SEC) throw new IllegalArgumentException("영상 길이 상한(6시간)을 넘는 시각이 있습니다.");
            if (c.text().length() > MAX_CUE_CHARS) {
                throw new IllegalArgumentException("한 줄이 너무 깁니다(" + formatClock(c.startSec()) + ", 최대 " + MAX_CUE_CHARS + "자) — 자막 형식을 확인하세요.");
            }
            total += c.text().length();
            prevStart = c.startSec();
        }
        if (total > MAX_TOTAL_CHARS) throw new IllegalArgumentException("자막 글자 수가 상한(" + MAX_TOTAL_CHARS + "자)을 넘습니다.");
        return List.copyOf(cues);
    }

    /** 태그 제거 + 기본 엔티티 해제 + 공백 압축 — 결과는 평문. */
    static String cleanText(String s) {
        String noTags = TAG.matcher(s).replaceAll(" ");
        String decoded = noTags.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return decoded.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
    }

    static int toSeconds(String stamp) {
        String main = stamp.split("[.,]")[0];
        String[] p = main.split(":");
        int h = p.length == 3 ? Integer.parseInt(p[0]) : 0;
        int m = Integer.parseInt(p[p.length - 2]);
        int s = Integer.parseInt(p[p.length - 1]);
        if (m > 59 || s > 59) throw new IllegalArgumentException("잘못된 시각 표기: " + stamp);
        return h * 3600 + m * 60 + s;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /** 저장용 JSON — [{s,e,sp,t}] (sp 는 확정 화자만). 해시도 이 문자열로 만든다. */
    public static String toJson(List<Cue> cues) {
        List<Map<String, Object>> rows = new ArrayList<>(cues.size());
        for (Cue c : cues) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("s", c.startSec());
            m.put("e", c.endSec());
            m.put("sp", c.speaker());
            m.put("t", c.text());
            rows.add(m);
        }
        try {
            return MAPPER.writeValueAsString(rows);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("자막 직렬화 실패", e);
        }
    }

    public static List<Cue> fromJson(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(json);
            List<Cue> out = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : root) {
                com.fasterxml.jackson.databind.JsonNode sp = n.get("sp");
                out.add(new Cue(n.get("s").asInt(), n.get("e").asInt(),
                        sp == null || sp.isNull() ? null : sp.asText(), n.get("t").asText()));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("저장된 자막을 읽지 못했습니다", e);
        }
    }

    public static String formatClock(int sec) {
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    private static String abbreviate(String s) {
        String t = s.strip();
        return t.length() > 30 ? t.substring(0, 30) + "…" : t;
    }
}
