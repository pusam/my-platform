package com.myplatform.backend.crosscheck;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 외부 대조의 순수 규칙 — 응답 해석·종가 판정·표본 고르기·실행 메모. 네트워크·DB 없음.
 *
 * <p><b>무엇과 견주나</b>: 네이버 종목 응답({@code m.stock.naver.com/api/stock/{code}/integration})의 {@code totalInfos} 중
 * {@code lastClosePrice} — 이름과 달리 '전일', 즉 <b>직전 거래일 KRX 종가</b>다(2026-11-10 16:33 삼성전자 189,900 = 같은 시각
 * basic 응답의 현재가 198,900 − 전일 대비 9,000). 응답에 날짜 칸이 없어 다음 날로 넘어가는 시각은 모른다 — 그래서 외부 값이 우리
 * 그 앞 봉과 같으면 '하루 늦음(대조 못 함)'으로 가른다. ⚠ 같은 응답의 {@code dealTrendInfos[].closePrice} 는 KRX 종가가 아니다
 * (11/9 행 187,000 ≠ 189,900) — 쓰지 않는다.
 */
public final class CrosscheckRules {

    private CrosscheckRules() {}

    // ---------------------------------------------------------------- 상태

    public static final String ITEM_CLOSE = "CLOSE";
    public static final String ITEM_EPS = "EPS";
    public static final String ITEM_BPS = "BPS";

    /** 종가 — 판정한 것. */
    public static final String MATCH = "MATCH";
    public static final String MISMATCH = "MISMATCH";
    /** 종가 — 대조 못 함(일치가 아니다, §4c). */
    public static final String EXTERNAL_STALE = "EXTERNAL_STALE";
    public static final String NO_OUR_BAR = "NO_OUR_BAR";
    public static final String NO_EXTERNAL_VALUE = "NO_EXTERNAL_VALUE";
    public static final String FETCH_FAILED = "FETCH_FAILED";
    /** EPS·BPS — 기록만(판정 없음). */
    public static final String RECORDED = "RECORDED";
    public static final String NO_OUR_VALUE = "NO_OUR_VALUE";

    public static final String REASON_CANDIDATE = "CANDIDATE";
    public static final String REASON_HOLDING = "HOLDING";
    public static final String REASON_WATCH = "WATCH";
    public static final String REASON_MCAP = "MCAP";
    public static final String REASON_RANDOM = "RANDOM";

    /** 하루 표본 상한 = 외부 호출 상한. */
    public static final int MAX_SAMPLE = 40;
    /** 후보·보유/관심·시총 상위 각각의 상한 — 나머지는 무작위로 채운다(최소 10). */
    public static final int GROUP_CAP = 10;
    /** 이만큼 연속으로 받지 못하면 그 실행을 멈춘다(막힘 의심 — 더 두드리지 않는다). */
    public static final int ABORT_AFTER_CONSECUTIVE_FAILURES = 5;

    private static final Pattern CODE = Pattern.compile("^[0-9A-Z]{6}$");
    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    // ---------------------------------------------------------------- 응답 해석

    /** 응답 칸 하나 — 원문·숫자(모르면 null)·기준 결산(valueDesc, 없으면 null). */
    public record Field(String raw, BigDecimal value, String basis) {
        static final Field MISSING = new Field(null, null, null);
    }

    /** integration 응답에서 쓰는 칸만. */
    public record NaverQuote(Field lastClose, Field eps, Field bps) {}

    public static NaverQuote parseIntegration(JsonNode root) {
        Map<String, JsonNode> byCode = new LinkedHashMap<>();
        if (root != null) {
            for (JsonNode info : root.path("totalInfos")) {
                String code = info.path("code").asText("");
                if (!code.isEmpty()) byCode.putIfAbsent(code, info);
            }
        }
        Field close = field(byCode.get("lastClosePrice"));
        // 종가는 양수여야 한다 — 0·음수는 '값 없음'(§4c, 0 원을 종가로 견주지 않는다)
        if (close.value() != null && close.value().signum() <= 0) close = new Field(close.raw(), null, close.basis());
        return new NaverQuote(close, field(byCode.get("eps")), field(byCode.get("bps")));
    }

    private static Field field(JsonNode info) {
        if (info == null) return Field.MISSING;
        String raw = info.path("value").isMissingNode() || info.path("value").isNull() ? null : info.path("value").asText();
        String basis = info.path("valueDesc").isTextual() ? blankToNull(info.path("valueDesc").asText()) : null;
        return new Field(raw == null ? null : truncate(raw, 40), number(raw), basis == null ? null : truncate(basis, 20));
    }

    /** "189,900" · "22,292원" · "8.92배" · "-1,234원" → 숫자. "N/A"·"-"·빈 값·그 밖은 null. */
    static BigDecimal number(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace(",", "").replace("원", "").replace("배", "").replace("%", "").trim();
        if (!NUMBER.matcher(cleaned).matches()) return null;
        return new BigDecimal(cleaned);
    }

    // ---------------------------------------------------------------- 종가 판정

    /**
     * 우리 마지막 확정 봉(D) 종가와 외부 '전일'을 견준다. 종가는 <b>정확히 같아야</b> 한다(허용 폭 없음).
     * 우리 D 봉이 없으면 {@link #NO_OUR_BAR}, 외부 값이 없으면 {@link #NO_EXTERNAL_VALUE}. 다르지만 우리 그 앞 봉과 같으면
     * {@link #EXTERNAL_STALE}(외부가 아직 넘어가지 않았다 — 대조 못 함). D 와 그 앞 봉이 같은 보합 종목은 그냥 일치다.
     */
    public static String classifyClose(BigDecimal ourD, BigDecimal ourPrev, BigDecimal external) {
        if (ourD == null) return NO_OUR_BAR;
        if (external == null) return NO_EXTERNAL_VALUE;
        if (ourD.compareTo(external) == 0) return MATCH;
        if (ourPrev != null && ourPrev.compareTo(external) == 0) return EXTERNAL_STALE;
        return MISMATCH;
    }

    public static boolean isCompared(String closeStatus) {
        return MATCH.equals(closeStatus) || MISMATCH.equals(closeStatus);
    }

    /** (외부−우리)/|우리|×100 — 둘 중 하나라도 없거나 우리 값이 0 이면 null. */
    public static BigDecimal diffPct(BigDecimal ours, BigDecimal external) {
        if (ours == null || external == null || ours.signum() == 0) return null;
        return external.subtract(ours).multiply(BigDecimal.valueOf(100))
                .divide(ours.abs(), 4, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- 표본

    /**
     * 표본 — 후보·보유·관심·시총 상위(각 그룹 합 최대 {@link #GROUP_CAP}, 보유+관심은 함께 10) 다음 무작위로 {@link #MAX_SAMPLE} 까지.
     * 무작위는 {@code seed}(대조 날짜)로 섞어 같은 날 다시 돌려도 같은 표본이다. 거래정지 종목·코드 모양이 아닌 값은 뺀다.
     *
     * @return 코드 → 표본 이유(넣은 순서)
     */
    public static Map<String, String> selectSample(Collection<String> candidates, Collection<String> holdings,
                                                   Collection<String> watchlist, Collection<String> marketCapTop,
                                                   Collection<String> randomPool, long seed, Predicate<String> halted) {
        Map<String, String> out = new LinkedHashMap<>();
        addGroup(out, candidates, REASON_CANDIDATE, GROUP_CAP, halted);
        int before = out.size();
        addGroup(out, holdings, REASON_HOLDING, GROUP_CAP, halted);
        addGroup(out, watchlist, REASON_WATCH, GROUP_CAP - (out.size() - before), halted);
        addGroup(out, marketCapTop, REASON_MCAP, GROUP_CAP, halted);

        List<String> pool = new ArrayList<>();
        if (randomPool != null) for (String c : randomPool) if (c != null) pool.add(c.trim());
        Collections.sort(pool);                       // 입력 순서와 무관하게 — 같은 날 같은 표본
        Collections.shuffle(pool, new Random(seed));
        addGroup(out, pool, REASON_RANDOM, MAX_SAMPLE - out.size(), halted);
        return out;
    }

    private static void addGroup(Map<String, String> out, Collection<String> codes, String reason, int cap,
                                 Predicate<String> halted) {
        if (codes == null || cap <= 0) return;
        int added = 0;
        for (String raw : codes) {
            if (added >= cap || out.size() >= MAX_SAMPLE) return;
            if (raw == null) continue;
            String code = raw.trim().toUpperCase();
            if (!CODE.matcher(code).matches() || out.containsKey(code)) continue;
            if (halted != null && halted.test(code)) continue;
            out.put(code, reason);
            added++;
        }
    }

    // ---------------------------------------------------------------- 실행 요약

    /** 종가 행 한 건 — 요약·메모용. */
    public record CloseOutcome(String stockCode, String status, BigDecimal ourD, BigDecimal ourPrev, BigDecimal external) {}

    /**
     * 한 실행 안에 '하루 늦음'과 '앞 봉과 값이 다른데 일치한 종목'이 섞여 있는가. 외부 값이 넘어가는 시각은 종목마다 다르지 않을
     * 것이므로, 다른 종목이 이미 D 값으로 넘어갔다면 그 '하루 늦음'은 늦음이 아니라 진짜 불일치일 수 있다. 분류는 그대로 둔다(대조 못 함).
     */
    public static boolean staleMixedWithMatch(List<CloseOutcome> outcomes) {
        boolean stale = false;
        boolean movedMatch = false;
        for (CloseOutcome o : outcomes) {
            if (EXTERNAL_STALE.equals(o.status())) stale = true;
            if (MATCH.equals(o.status()) && o.ourPrev() != null && o.ourD() != null
                    && o.ourPrev().compareTo(o.ourD()) != 0) movedMatch = true;
        }
        return stale && movedMatch;
    }

    public static final String STALE_MIXED_NOTE = "하루 늦음으로 분류한 종목이 이미 넘어간 종목(앞 봉과 값이 다른데 일치)과 섞여 있다 — "
            + "넘어가는 시각은 종목마다 같을 것이므로 하루 늦음이 아니라 진짜 불일치일 수 있다";

    /** 실행 메모 — 대조 못 함 사유별 건수와 섞임 경고. 빈 문자열이 아니면 그대로 저장한다. */
    public static String runNote(List<CloseOutcome> outcomes, String extra) {
        Map<String, Integer> notCompared = new LinkedHashMap<>();
        for (CloseOutcome o : outcomes) {
            if (!isCompared(o.status())) notCompared.merge(o.status(), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        if (!notCompared.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            notCompared.forEach((k, v) -> reasons.add(k + " " + v));
            parts.add("대조 못 함: " + String.join(" · ", reasons));
        }
        if (staleMixedWithMatch(outcomes)) parts.add(STALE_MIXED_NOTE);
        if (extra != null && !extra.isBlank()) parts.add(extra);
        String note = String.join(" / ", parts);
        return note.isEmpty() ? null : truncate(note, 1000);
    }

    // ---------------------------------------------------------------- 도우미

    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
