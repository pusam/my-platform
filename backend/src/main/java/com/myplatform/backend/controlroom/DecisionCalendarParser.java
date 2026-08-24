package com.myplatform.backend.controlroom;

import org.yaml.snakeyaml.Yaml;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code docs/SCHEDULE_DECISIONS.md} 판정 캘린더 파서 — 순수 함수(파일 I/O 없음).
 *
 * <p><b>YAML 블록만 읽는다.</b> 마크다운 헤딩은 파싱하지 않는다 — 헤딩엔 "경"·"중"·"수시" 같은 근사
 * 표현이 섞여 있어 날짜로 쓸 수 없다. 캘린더에 올릴 항목은 사람이 YAML 블록에 확정 날짜로 적는다.
 *
 * <p>두 가지를 함께 읽는다:
 * <ol>
 *   <li><b>YAML calendar 블록</b> — 캘린더에 실제로 찍히는 항목</li>
 *   <li><b>"판정 기록" 표</b> — 로스터. 판정일 칸이 미기입(밑줄)이면 <b>미판정</b>이다.
 *       표에 있는데 YAML 에 없는 안건은 캘린더에 뜨지 않고 {@code unregisteredTitles} 로 보고된다.</li>
 * </ol>
 *
 * <p>§4c: 파싱 실패 항목을 조용히 건너뛰지 않는다 — {@code parseErrors} 에 담아 화면 FLAGGED 에
 * 노출한다. 블록이 아예 없으면 {@code dataAvailable=false} 로, "판정 0건"과 구분한다.
 */
public final class DecisionCalendarParser {

    private DecisionCalendarParser() {}

    /** milestone = 캘린더 핀 전용. 로스터·미판정 집계에서 제외된다. */
    public static final String KIND_MILESTONE = "milestone";
    public static final String KIND_DECISION = "decision";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DECIDED = "decided";
    public static final String STATUS_DEFERRED = "deferred";

    private static final Set<String> VALID_STATUS =
            Set.of(STATUS_PENDING, STATUS_DECIDED, STATUS_DEFERRED);

    /** yaml 펜스. 파일에 여러 개 있을 수 있어 전부 훑고 원하는 최상위 키를 가진 것을 고른다. */
    private static final Pattern YAML_FENCE =
            Pattern.compile("```yaml\\s*\\n(.*?)\\n```", Pattern.DOTALL);

    /** 판정 기록 표의 행 — 첫 칸이 판정일(미기입이면 밑줄 포함), 둘째 칸이 안건명. */
    private static final Pattern RECORD_ROW =
            Pattern.compile("^\\|\\s*(\\d{4}-[\\d_]{2}-[\\d_]{2})\\s*\\|\\s*([^|]+?)\\s*\\|");

    /**
     * 판정 캘린더 1건.
     *
     * @param trigger 조건 트리거. 비어있지 않으면 {@code due} 는 판정일이 아니라 <b>확인일</b>이며
     *                화면에서 판정일과 구분 표기한다(조건 미달인데 판정한 것처럼 보이지 않게).
     * @param kind    {@link #KIND_DECISION}(기본) / {@link #KIND_MILESTONE}
     */
    public record Entry(
            String id,
            String title,
            LocalDate due,
            String status,
            LocalDate decidedOn,
            String result,
            String trigger,
            String kind
    ) {
        public boolean isMilestone() { return KIND_MILESTONE.equals(kind); }

        public boolean hasTrigger() { return trigger != null && !trigger.isBlank(); }

        /** OVERDUE = 기한이 지났는데 아직 판정되지 않음. decided 는 지났어도 OVERDUE 가 아니다. */
        public boolean isOverdue(LocalDate today) {
            return due != null && due.isBefore(today)
                    && (STATUS_PENDING.equals(status) || STATUS_DEFERRED.equals(status));
        }
    }

    /**
     * @param undecidedCount     로스터(판정 기록 표) 중 판정일 미기입 행 수 = 미판정 KPI
     * @param unregisteredTitles 표에는 있는데 YAML 블록에 없는 안건 — FLAGGED "미등록 판정 N건"
     * @param parseErrors        파싱 실패 항목 (§4c — 조용히 건너뛰지 않는다)
     * @param dataAvailable      YAML 블록을 찾았는지. false = "판정 데이터 없음"(0건과 구분)
     * @param recordTableFound   판정 기록 표를 찾았는지. false 면 미판정 수를 0 으로 단정하지 않는다
     */
    public record Result(
            List<Entry> entries,
            int undecidedCount,
            int rosterSize,
            List<String> unregisteredTitles,
            List<String> parseErrors,
            boolean dataAvailable,
            boolean recordTableFound
    ) {
        public List<Entry> overdue(LocalDate today) {
            return entries.stream().filter(e -> e.isOverdue(today)).toList();
        }

        public List<Entry> conditionWaiting() {
            return entries.stream().filter(Entry::hasTrigger).toList();
        }
    }

    /** 마크다운 원문 to 파싱 결과. 예외를 던지지 않는다(부분 실패는 parseErrors 로). */
    @SuppressWarnings("unchecked")
    public static Result parse(String markdown) {
        List<Entry> entries = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();
        boolean dataAvailable = false;

        if (markdown == null || markdown.isBlank()) {
            return new Result(List.of(), 0, 0, List.of(), List.of(), false, false);
        }

        Object rawCalendar = extractBlock(markdown, "calendar");
        if (rawCalendar instanceof List<?> list) {
            dataAvailable = true;
            int index = 0;
            for (Object item : list) {
                index++;
                if (!(item instanceof Map<?, ?> map)) {
                    parseErrors.add("파싱 오류: calendar[" + index + "] — 항목이 맵이 아님");
                    continue;
                }
                String id = str(map.get("id"));
                try {
                    entries.add(toEntry((Map<String, Object>) map));
                } catch (IllegalArgumentException e) {
                    parseErrors.add("파싱 오류: " + (id != null ? id : "calendar[" + index + "]")
                            + " — " + e.getMessage());
                }
            }
        }

        // 로스터(판정 기록 표)
        List<String> rosterTitles = new ArrayList<>();
        int undecided = 0;
        for (String line : markdown.split("\\R")) {
            Matcher m = RECORD_ROW.matcher(line.trim());
            if (!m.find()) continue;
            String dateCell = m.group(1);
            String title = normalizeTitle(m.group(2));
            if (title.isBlank() || "안건".equals(title)) continue;   // 헤더 행 방어
            rosterTitles.add(title);
            if (dateCell.indexOf('_') >= 0) undecided++;             // 미기입 = 미판정
        }
        boolean recordTableFound = !rosterTitles.isEmpty();

        // 표에는 있는데 YAML 에 없는 안건 (milestone 은 로스터 대상이 아니므로 매칭에서 제외)
        Set<String> registered = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.isMilestone()) continue;
            registered.add(normalizeTitle(e.title()));
        }
        List<String> unregistered = rosterTitles.stream()
                .filter(t -> !registered.contains(t))
                .distinct()
                .toList();

        return new Result(List.copyOf(entries), undecided, rosterTitles.size(),
                unregistered, List.copyOf(parseErrors), dataAvailable, recordTableFound);
    }

    private static Entry toEntry(Map<String, Object> map) {
        String id = require(str(map.get("id")), "id 누락");
        String title = require(str(map.get("title")), "title 누락");
        // due 는 전 항목 필수 — deferred 의 "새 due 필수" 규칙도 이 한 줄로 함께 강제된다.
        LocalDate due = YamlValues.date(map.get("due"), "due");

        String status = require(str(map.get("status")), "status 누락");
        if (!VALID_STATUS.contains(status)) {
            throw new IllegalArgumentException(
                    "status 는 pending|decided|deferred 여야 함 (받은 값: " + status + ")");
        }

        Object decidedRaw = map.get("decided_on");
        LocalDate decidedOn = decidedRaw == null ? null : YamlValues.date(decidedRaw, "decided_on");

        String kind = str(map.get("kind"));
        if (kind == null) kind = KIND_DECISION;
        if (!KIND_DECISION.equals(kind) && !KIND_MILESTONE.equals(kind)) {
            throw new IllegalArgumentException(
                    "kind 는 decision|milestone 여야 함 (받은 값: " + kind + ")");
        }

        return new Entry(id, title, due, status, decidedOn,
                str(map.get("result")), str(map.get("trigger")), kind);
    }

    /** 마크다운 안의 yaml 펜스들 중 topLevelKey 를 가진 첫 블록의 값을 돌려준다. */
    private static Object extractBlock(String markdown, String topLevelKey) {
        Matcher m = YAML_FENCE.matcher(markdown);
        while (m.find()) {
            try {
                Object loaded = new Yaml().load(m.group(1));
                if (loaded instanceof Map<?, ?> map && map.containsKey(topLevelKey)) {
                    return map.get(topLevelKey);
                }
            } catch (RuntimeException ignore) {
                // 이 펜스는 우리 블록이 아니거나 깨졌다 — 다음 펜스를 계속 본다.
                // 우리 블록이 깨진 경우는 dataAvailable=false 로 이어져 "데이터 없음"으로 표시된다.
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

    /** 표 셀과 YAML title 을 맞추기 위한 정규화 — 볼드/백틱/중복 공백 제거. */
    static String normalizeTitle(String raw) {
        if (raw == null) return "";
        return raw.replace("**", "").replace("`", "").replaceAll("\\s+", " ").trim();
    }
}
