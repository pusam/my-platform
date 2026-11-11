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
 * 외부 대조의 순수 규칙 — 응답 해석·종가 판정·표본 고르기·실행 집계. 네트워크·DB 없음.
 *
 * <p><b>무엇과 견주나</b>: 네이버 종목 응답({@code m.stock.naver.com/api/stock/{code}/integration})의 {@code totalInfos} 중
 * {@code lastClosePrice} — 이름과 달리 '전일', 즉 <b>직전 거래일 KRX 종가</b>다(2026-11-10 16:33 삼성전자 189,900 = 같은 시각
 * basic 응답의 현재가 198,900 − 전일 대비 9,000 / 11-11 10:55 세 종목이 우리 11-10 봉과 정확히 같았다). 응답에 날짜 칸이 없어
 * 다음 날로 넘어가는 시각은 모른다 — 그래서 외부 값이 우리 그 앞 봉과 같으면 '하루 늦음(대조 못 함)'으로 가르고, 같은 실행에서
 * 넘어갔음이 증명되면 그 '하루 늦음'은 불일치로 다시 분류한다. ⚠ 같은 응답의 {@code dealTrendInfos[].closePrice} 는 KRX 종가가
 * 아니다(11/9 행 187,000 ≠ 189,900) — 쓰지 않는다.
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
    /** 응답의 종목코드가 요청과 다르다 — 다른 종목 값을 견주지 않는다. */
    public static final String WRONG_ITEM = "WRONG_ITEM";
    /** EPS·BPS — 기록만(판정 없음). */
    public static final String RECORDED = "RECORDED";
    public static final String NO_OUR_VALUE = "NO_OUR_VALUE";

    /** 직전 실행에서 어긋난 종목 — 다음 실행에서 다시 본다('다음에도 어긋나면 올라간다'는 약속을 사실로). */
    public static final String REASON_RECHECK = "RECHECK";
    public static final String REASON_CANDIDATE = "CANDIDATE";
    public static final String REASON_HOLDING = "HOLDING";
    public static final String REASON_WATCH = "WATCH";
    public static final String REASON_MCAP = "MCAP";
    public static final String REASON_RANDOM = "RANDOM";

    /** 한 번 실행의 표본 상한 = 한 번 실행의 외부 호출 상한. */
    public static final int MAX_SAMPLE = 40;
    /** 재확인·후보·보유/관심·시총 상위 각각의 상한 — 나머지는 무작위로 채운다. */
    public static final int GROUP_CAP = 10;
    /** 이만큼 연속으로 값을 받지 못하면(응답 실패·값 없음·다른 종목) 그 실행을 멈춘다 — 더 두드리지 않는다. */
    public static final int ABORT_AFTER_CONSECUTIVE_FAILURES = 5;
    /**
     * 같은 실행에서 종가가 움직인 종목(앞 봉 ≠ 대상 봉)이 이만큼 일치하면 외부 값은 이미 대상 봉으로 넘어간 것이다 — 그 실행의
     * '하루 늦음'은 늦음이 아니라 불일치다(넘어가는 시각이 종목마다 다를 이유가 없다).
     */
    public static final int ROLLED_OVER_PROOF = 5;
    /** diff_pct 칸(DECIMAL(10,4))에 들어가는 절댓값 상한 — 넘으면 저장하지 않고 사유를 남긴다. */
    static final BigDecimal DIFF_PCT_LIMIT = new BigDecimal("999999.9999");

    private static final Pattern CODE = Pattern.compile("^[0-9A-Z]{6}$");
    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    // ---------------------------------------------------------------- 응답 해석

    /** 응답 칸 하나 — 원문·숫자(모르면 null)·기준 결산(valueDesc, 없으면 null). */
    public record Field(String raw, BigDecimal value, String basis) {
        static final Field MISSING = new Field(null, null, null);
    }

    /** integration 응답에서 쓰는 칸만. {@code itemCode} 는 응답이 말하는 종목코드(없으면 null). */
    public record NaverQuote(String itemCode, Field lastClose, Field eps, Field bps) {}

    public static NaverQuote parseIntegration(JsonNode root) {
        Map<String, JsonNode> byCode = new LinkedHashMap<>();
        String itemCode = null;
        if (root != null) {
            for (JsonNode info : root.path("totalInfos")) {
                String code = info.path("code").asText("");
                if (!code.isEmpty()) byCode.putIfAbsent(code, info);
            }
            if (root.path("itemCode").isTextual()) itemCode = blankToNull(root.path("itemCode").asText());
        }
        Field close = field(byCode.get("lastClosePrice"));
        // 종가는 양수여야 한다 — 0·음수는 '값 없음'(§4c, 0 원을 종가로 견주지 않는다)
        if (close.value() != null && close.value().signum() <= 0) close = new Field(close.raw(), null, close.basis());
        return new NaverQuote(itemCode, close, field(byCode.get("eps")), field(byCode.get("bps")));
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

    /** 응답이 다른 종목의 것인가 — 응답에 종목코드가 없으면 판정하지 않는다(모르는 것을 틀렸다고 하지 않는다). */
    public static boolean isWrongItem(String requested, NaverQuote quote) {
        return quote != null && quote.itemCode() != null && !quote.itemCode().equalsIgnoreCase(requested);
    }

    // ---------------------------------------------------------------- 종가 판정

    /**
     * 우리 마지막 확정 봉(D) 종가와 외부 '전일'을 견준다. 종가는 <b>정확히 같아야</b> 한다(허용 폭 없음).
     * 우리 D 봉이 없으면 {@link #NO_OUR_BAR}, 외부 값이 없으면 {@link #NO_EXTERNAL_VALUE}. 다르지만 우리 그 앞 봉과 같으면
     * {@link #EXTERNAL_STALE}(외부가 아직 넘어가지 않았을 수 있다 — 대조 못 함). D 와 그 앞 봉이 같은 보합 종목은 그냥 일치다.
     * 같은 실행의 다른 종목이 넘어갔음을 증명하면 {@link #reclassifyStale} 이 그 '하루 늦음'을 불일치로 바꾼다.
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

    /** 연속 실패로 세는 상태 — 값을 받지 못한 것(응답 실패·값 없음·다른 종목 응답). */
    public static boolean countsTowardAbort(String closeStatus) {
        return FETCH_FAILED.equals(closeStatus) || NO_EXTERNAL_VALUE.equals(closeStatus) || WRONG_ITEM.equals(closeStatus);
    }

    /** (외부−우리)/|우리|×100 — 둘 중 하나라도 없거나 우리 값이 0 이면 null. */
    public static BigDecimal diffPct(BigDecimal ours, BigDecimal external) {
        if (ours == null || external == null || ours.signum() == 0) return null;
        return external.subtract(ours).multiply(BigDecimal.valueOf(100))
                .divide(ours.abs(), 4, RoundingMode.HALF_UP);
    }

    /** 저장할 수 있는 차이 — 칸(DECIMAL(10,4)) 범위를 넘으면 null(저장 실패로 실행이 죽지 않게, 사유는 {@link #diffPctOverflows}). */
    public static BigDecimal diffPctForColumn(BigDecimal ours, BigDecimal external) {
        BigDecimal d = diffPct(ours, external);
        return d == null || d.abs().compareTo(DIFF_PCT_LIMIT) > 0 ? null : d;
    }

    public static boolean diffPctOverflows(BigDecimal ours, BigDecimal external) {
        BigDecimal d = diffPct(ours, external);
        return d != null && d.abs().compareTo(DIFF_PCT_LIMIT) > 0;
    }

    // ---------------------------------------------------------------- 표본

    /** 표본 — 코드 → 이유(넣은 순서)와, 대상 봉이 없어 뺀 고정 그룹 종목 수. */
    public record Sample(Map<String, String> codes, int excludedNoBar) {}

    /**
     * 표본 — 재확인(직전 실행의 불일치)·후보·보유·관심·시총 상위(각 최대 {@link #GROUP_CAP}, 보유+관심은 함께 10) 다음 무작위로
     * {@link #MAX_SAMPLE} 까지. <b>대상 봉이 있는 종목에서만</b> 고른다 — 봉이 없는 종목은 대조할 것이 없어 외부 호출만 낭비한다
     * (후보 풀 밖 보유·관심 종목은 아무도 봉을 받지 않는다). 뺀 수는 돌려준다. 무작위는 {@code seed}(대조 날짜)로 섞는다 —
     * 같은 날 다시 돌리면 무작위 몫은 같고, 후보·보유가 그대로면 표본 전체가 같다. 거래정지 종목·코드 모양이 아닌 값은 뺀다.
     */
    public static Sample selectSample(Collection<String> recheck, Collection<String> candidates, Collection<String> holdings,
                                      Collection<String> watchlist, Collection<String> marketCapTop,
                                      Collection<String> randomPool, long seed,
                                      Predicate<String> hasTargetBar, Predicate<String> halted) {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.Set<String> noBar = new java.util.HashSet<>();
        addGroup(out, recheck, REASON_RECHECK, GROUP_CAP, hasTargetBar, halted, noBar);
        addGroup(out, candidates, REASON_CANDIDATE, GROUP_CAP, hasTargetBar, halted, noBar);
        int before = out.size();
        addGroup(out, holdings, REASON_HOLDING, GROUP_CAP, hasTargetBar, halted, noBar);
        addGroup(out, watchlist, REASON_WATCH, GROUP_CAP - (out.size() - before), hasTargetBar, halted, noBar);
        addGroup(out, marketCapTop, REASON_MCAP, GROUP_CAP, hasTargetBar, halted, noBar);

        List<String> pool = new ArrayList<>();
        if (randomPool != null) for (String c : randomPool) if (c != null) pool.add(c.trim());
        Collections.sort(pool);                       // 입력 순서와 무관하게 — 같은 날 같은 무작위 몫
        Collections.shuffle(pool, new Random(seed));
        addGroup(out, pool, REASON_RANDOM, MAX_SAMPLE - out.size(), hasTargetBar, halted, null);
        noBar.removeAll(out.keySet());
        return new Sample(out, noBar.size());
    }

    private static void addGroup(Map<String, String> out, Collection<String> codes, String reason, int cap,
                                 Predicate<String> hasTargetBar, Predicate<String> halted, java.util.Set<String> noBar) {
        if (codes == null || cap <= 0) return;
        int added = 0;
        for (String raw : codes) {
            if (added >= cap || out.size() >= MAX_SAMPLE) return;
            if (raw == null) continue;
            String code = raw.trim().toUpperCase();
            if (!CODE.matcher(code).matches() || out.containsKey(code)) continue;
            if (halted != null && halted.test(code)) continue;
            if (hasTargetBar != null && !hasTargetBar.test(code)) {
                if (noBar != null) noBar.add(code);
                continue;
            }
            out.put(code, reason);
            added++;
        }
    }

    // ---------------------------------------------------------------- 실행 집계

    /** 종가 행 한 건 — 집계·메모용. */
    public record CloseOutcome(String stockCode, String status, BigDecimal ourD, BigDecimal ourPrev, BigDecimal external) {

        /** 종가가 움직인 종목인가(앞 봉 ≠ 대상 봉) — 이런 종목의 일치만 '외부가 넘어갔다'는 증거가 된다. */
        boolean moved() {
            return ourD != null && ourPrev != null && ourD.compareTo(ourPrev) != 0;
        }

        public BigDecimal diffPct() {
            return CrosscheckRules.diffPct(ourD, external);
        }
    }

    /** 같은 실행에서 외부 값이 대상 봉으로 넘어갔음이 증명되는가 — 종가가 움직인 종목의 일치가 {@link #ROLLED_OVER_PROOF}건 이상. */
    public static boolean rolledOverProven(List<CloseOutcome> outcomes) {
        int proof = 0;
        for (CloseOutcome o : outcomes) if (MATCH.equals(o.status()) && o.moved()) proof++;
        return proof >= ROLLED_OVER_PROOF;
    }

    public static final String STALE_RECLASSIFIED_REASON = "외부 값이 우리 그 앞 봉과 같지만 같은 실행의 다른 종목은 대상 봉으로 넘어가 있었다";

    /** 넘어갔음이 증명되면 '하루 늦음'을 불일치로 바꾼 목록, 아니면 그대로. */
    public static List<CloseOutcome> reclassifyStale(List<CloseOutcome> outcomes) {
        if (!rolledOverProven(outcomes)) return outcomes;
        List<CloseOutcome> out = new ArrayList<>(outcomes.size());
        for (CloseOutcome o : outcomes) {
            out.add(EXTERNAL_STALE.equals(o.status())
                    ? new CloseOutcome(o.stockCode(), MISMATCH, o.ourD(), o.ourPrev(), o.external()) : o);
        }
        return out;
    }

    /**
     * 한 실행 안에 '하루 늦음'과 '앞 봉과 값이 다른데 일치한 종목'이 섞여 있는가(증명 기준에는 못 미친 채). 넘어가는 시각은 종목마다
     * 다르지 않을 것이므로 그 '하루 늦음'은 진짜 불일치일 수 있다. 분류는 그대로 둔다(대조 못 함).
     */
    public static boolean staleMixedWithMatch(List<CloseOutcome> outcomes) {
        boolean stale = false;
        boolean movedMatch = false;
        for (CloseOutcome o : outcomes) {
            if (EXTERNAL_STALE.equals(o.status())) stale = true;
            if (MATCH.equals(o.status()) && o.moved()) movedMatch = true;
        }
        return stale && movedMatch;
    }

    public static final String STALE_MIXED_NOTE = "하루 늦음으로 분류한 종목이 이미 넘어간 종목(앞 봉과 값이 다른데 일치)과 섞여 있다 — "
            + "넘어가는 시각은 종목마다 같을 것이므로 하루 늦음이 아니라 진짜 불일치일 수 있다";

    /** 한 실행의 집계 — 종목 행에서 센다(끝나지 못한 실행도 같은 식으로 다시 센다). */
    public record Tally(int attempted, int matched, int mismatched, int notCompared, Map<String, Integer> notComparedReasons) {}

    public static Tally tally(List<CloseOutcome> outcomes) {
        int matched = 0;
        int mismatched = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (CloseOutcome o : outcomes) {
            if (MATCH.equals(o.status())) matched++;
            else if (MISMATCH.equals(o.status())) mismatched++;
            else reasons.merge(o.status(), 1, Integer::sum);
        }
        return new Tally(outcomes.size(), matched, mismatched, outcomes.size() - matched - mismatched, reasons);
    }

    /** 실행 메모 — 대조 못 함 사유별 건수·다시 분류한 건수·섞임 경고·덧붙일 말. 빈 문자열이 아니면 그대로 저장한다. */
    public static String runNote(List<CloseOutcome> classified, int reclassified, List<String> extras) {
        Tally t = tally(classified);
        List<String> parts = new ArrayList<>();
        if (!t.notComparedReasons().isEmpty()) {
            List<String> reasons = new ArrayList<>();
            t.notComparedReasons().forEach((k, v) -> reasons.add(k + " " + v));
            parts.add("대조 못 함: " + String.join(" · ", reasons));
        }
        if (reclassified > 0) {
            parts.add("하루 늦음 " + reclassified + "건을 불일치로 다시 분류 — " + STALE_RECLASSIFIED_REASON);
        } else if (staleMixedWithMatch(classified)) {
            parts.add(STALE_MIXED_NOTE);
        }
        if (extras != null) for (String e : extras) if (e != null && !e.isBlank()) parts.add(e);
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
