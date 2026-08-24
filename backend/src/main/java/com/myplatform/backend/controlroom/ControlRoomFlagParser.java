package com.myplatform.backend.controlroom;

import org.yaml.snakeyaml.Yaml;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code docs/CONTROL_ROOM_FLAGS.md} FLAGGED 파서 — 순수 함수(파일 I/O 없음).
 *
 * <p>열려 있는 이상 항목은 <b>사람이 손으로 관리</b>한다. 감사/리뷰에서 나온 항목을 파일에 적고 해소되면
 * 지운다. 자동 감지 소스가 아니다.
 *
 * <p>§4c: 파일이 없거나 블록이 비면 "플래그 0건"이 아니라 {@code dataAvailable=false}("데이터 없음")로
 * 구분한다. 개별 항목 파싱 실패도 조용히 건너뛰지 않고 {@code parseErrors} 로 노출한다.
 */
public final class ControlRoomFlagParser {

    private ControlRoomFlagParser() {}

    public static final String SEVERITY_CRITICAL = "critical";
    public static final String SEVERITY_WARNING = "warning";
    public static final String SEVERITY_INFO = "info";

    private static final Set<String> VALID_SEVERITY =
            Set.of(SEVERITY_CRITICAL, SEVERITY_WARNING, SEVERITY_INFO);

    private static final Pattern YAML_FENCE =
            Pattern.compile("```yaml\\s*\\n(.*?)\\n```", Pattern.DOTALL);

    /**
     * @param key         제목 옆 배지(파일명·티켓·심볼). null 이면 배지 생략
     * @param recordedOn  기록일 — 오래된 항목의 신선도 판단용. 화면이 경과일을 함께 보여준다
     */
    public record Flag(
            String id,
            String severity,
            String title,
            String key,
            String body,
            LocalDate recordedOn,
            String ref
    ) {
        public boolean isCritical() { return SEVERITY_CRITICAL.equals(severity); }
    }

    /**
     * @param dataAvailable false = 파일/블록 없음("플래그 0건"과 구분)
     * @param parseErrors   파싱 실패 항목 (§4c)
     */
    public record Result(List<Flag> flags, List<String> parseErrors, boolean dataAvailable) {

        public long criticalCount() {
            return flags.stream().filter(Flag::isCritical).count();
        }
    }

    @SuppressWarnings("unchecked")
    public static Result parse(String markdown) {
        List<Flag> flags = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();

        if (markdown == null || markdown.isBlank()) {
            return new Result(List.of(), List.of(), false);
        }

        Object raw = extractBlock(markdown);
        if (!(raw instanceof List<?> list)) {
            return new Result(List.of(), List.of(), false);
        }

        int index = 0;
        for (Object item : list) {
            index++;
            if (!(item instanceof Map<?, ?> map)) {
                parseErrors.add("파싱 오류: flags[" + index + "] — 항목이 맵이 아님");
                continue;
            }
            String id = str(map.get("id"));
            try {
                flags.add(toFlag((Map<String, Object>) map));
            } catch (IllegalArgumentException e) {
                parseErrors.add("파싱 오류: " + (id != null ? id : "flags[" + index + "]")
                        + " — " + e.getMessage());
            }
        }
        return new Result(List.copyOf(flags), List.copyOf(parseErrors), true);
    }

    private static Flag toFlag(Map<String, Object> map) {
        String id = require(str(map.get("id")), "id 누락");
        String severity = require(str(map.get("severity")), "severity 누락");
        if (!VALID_SEVERITY.contains(severity)) {
            throw new IllegalArgumentException(
                    "severity 는 critical|warning|info 여야 함 (받은 값: " + severity + ")");
        }
        String title = require(str(map.get("title")), "title 누락");
        String body = require(str(map.get("body")), "body 누락");

        LocalDate recordedOn = YamlValues.date(map.get("recorded_on"), "recorded_on");
        return new Flag(id, severity, title, str(map.get("key")), body, recordedOn, str(map.get("ref")));
    }

    private static Object extractBlock(String markdown) {
        Matcher m = YAML_FENCE.matcher(markdown);
        while (m.find()) {
            try {
                Object loaded = new Yaml().load(m.group(1));
                if (loaded instanceof Map<?, ?> map && map.containsKey("flags")) {
                    return map.get("flags");
                }
            } catch (RuntimeException ignore) {
                // 우리 블록이 아니거나 깨졌다 — 다음 펜스를 본다.
            }
        }
        return null;
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    private static String str(Object o) {
        return YamlValues.str(o);
    }
}
