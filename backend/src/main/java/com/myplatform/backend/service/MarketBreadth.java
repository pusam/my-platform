package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.entity.MarketDailyStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.List;

/**
 * 시장 폭(상승·하락 종목 수)과 ADR(20일) — 순수 함수 (2026-10-02).
 *
 * <p><b>왜 다시 만들었나.</b> 등락 종목 수는 네이버 레거시 시세 페이지({@code finance.naver.com/sise/sise_rise.naver} 등)를
 * 긁었는데, 그 페이지가 죽은 뒤(마지막 정상 수집 2026-09-10) 크롤 실패가 <b>0 으로 저장</b>됐다. ADR 은 "최근 20행" 합이라
 * 0 행은 분자·분모에 아무것도 더하지 않아 겉보기엔 멀쩡한 값이 나왔다 — 10/2 화면의 "ADR(20일) 85.0 · 정상 범위"는 실제로는
 * 3주 전(9/3~9/10) 6일치였다. 그 값이 모닝브리핑·AI 분석·시장 알림으로 매일 나갔다.
 *
 * <p>지금은 ① 출처를 KIS 국내업종 현재지수[국내주식-063] {@code FHPUP02100000} 응답의 {@code ascn_issu_cnt}(상승)·
 * {@code down_issu_cnt}(하락)·{@code stnr_issu_cnt}(보합)·{@code uplm_issu_cnt}(상한)·{@code lslm_issu_cnt}(하한)로 바꿨고
 * (공식 샘플 {@code examples_llm/domestic_stock/inquire_index_price/chk_inquire_index_price.py} 의 COLUMN_MAPPING),
 * ② 실패는 0 이 아니라 null(모름)이며 ③ ADR 은 창 안에서 <b>등락 수가 실제로 있는 날만</b> 합산하고, 그런 날이
 * {@link #MIN_VALID_DAYS} 미만이면 값을 내지 않는다(판단 보류). 과거 등락 수는 이 API 로 못 받는다 — 일자별지수[065]의
 * 날짜별 목록(output2)엔 등락 수 필드가 없다 — 그래서 9/11~10/1 구멍은 메울 수 없고, 창이 다시 차는 데 몇 주 걸린다.
 */
public final class MarketBreadth {

    private MarketBreadth() {}

    /** ADR 창 — 최근 20거래일(행 기준, 종전과 같다). */
    public static final int ADR_PERIOD = 20;

    /** ADR 을 내려면 창 안에 등락 수가 실제로 있는 날이 이만큼은 있어야 한다(20일 중 75%). */
    public static final int MIN_VALID_DAYS = 15;

    /**
     * 등락 수가 그날 값으로 굳는 시각(KST) — 정규장 15:30 + 여유. 이 전에 받은 값은 장중 잠정치라 '오늘' 행에 쓰지 않는다
     * (투자자 일별 기록을 장 마감 확정치만 쓰는 것과 같은 원칙, 2026-10-01 감사). 16:30 크론이 확정치를 쓴다.
     */
    public static final LocalTime COUNTS_SETTLED_AT = LocalTime.of(15, 40);

    /** 하루 등락 종목 수. 상한·하한은 화면 표시용이라 없으면 null. */
    public record Counts(int advancing, int declining, int unchanged, Integer upperLimit, Integer lowerLimit) {
        public int total() {
            return advancing + declining + unchanged;
        }
    }

    /**
     * ADR 계산 결과.
     *
     * @param value      ADR(%) — 판단 보류면 null
     * @param validDays  창 안에서 등락 수가 실제로 있는 날 수
     * @param windowDays 창 크기(행 수, 최대 {@link #ADR_PERIOD})
     */
    public record Adr(BigDecimal value, int validDays, int windowDays) {}

    /**
     * KIS 국내업종 현재지수 응답 → 등락 종목 수. 응답 실패·필드 결측·숫자 아님·전부 0 이면 null(§4c — 실패를 0 으로 두지 않는다).
     * 전부 0 은 장 시작 전이거나 응답이 빈 것이다 — 거래일에 상승·하락·보합이 모두 0 인 시장은 없다.
     */
    public static Counts fromKisIndexPrice(JsonNode body) {
        if (body == null || !"0".equals(body.path("rt_cd").asText(""))) return null;
        JsonNode out = body.path("output");
        if (out.isArray()) out = out.size() > 0 ? out.get(0) : null;
        if (out == null || out.isMissingNode() || out.isNull()) return null;
        Integer adv = intOrNull(out, "ascn_issu_cnt");
        Integer dec = intOrNull(out, "down_issu_cnt");
        Integer unch = intOrNull(out, "stnr_issu_cnt");
        if (adv == null || dec == null || unch == null) return null;
        if (adv < 0 || dec < 0 || unch < 0 || adv + dec + unch == 0) return null;
        return new Counts(adv, dec, unch, intOrNull(out, "uplm_issu_cnt"), intOrNull(out, "lslm_issu_cnt"));
    }

    /**
     * 저장된 행이 등락 수를 실제로 담았는가. 0/0 은 2026-09-11~10-02 크롤 사망 동안 실패를 0 으로 저장한 행이다 —
     * 그 행들은 지우지 않고(이력) 여기서 '모름'으로 읽는다.
     */
    public static boolean hasCounts(MarketDailyStatus row) {
        if (row == null || row.getAdvancingCount() == null || row.getDecliningCount() == null) return false;
        return row.getAdvancingCount() + row.getDecliningCount() > 0;
    }

    /**
     * ADR(20일) = 창 안 '등락 수가 있는 날'의 상승 합 ÷ 하락 합 × 100.
     *
     * @param newestFirst 최근 날짜부터 정렬된 행(창은 앞에서 {@link #ADR_PERIOD}개)
     */
    public static Adr adr(List<MarketDailyStatus> newestFirst) {
        List<MarketDailyStatus> window = newestFirst == null ? List.of()
                : newestFirst.subList(0, Math.min(ADR_PERIOD, newestFirst.size()));
        long adv = 0;
        long dec = 0;
        int valid = 0;
        for (MarketDailyStatus row : window) {
            if (!hasCounts(row)) continue;
            adv += row.getAdvancingCount();
            dec += row.getDecliningCount();
            valid++;
        }
        if (valid < MIN_VALID_DAYS) return new Adr(null, valid, window.size());
        if (dec == 0) return new Adr(new BigDecimal("999.99"), valid, window.size());
        BigDecimal value = BigDecimal.valueOf(adv)
                .divide(BigDecimal.valueOf(dec), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
        return new Adr(value, valid, window.size());
    }

    /** 지금(KST) 받는 등락 수가 그날 확정치인가. */
    public static boolean countsSettled(LocalTime nowKst) {
        return nowKst != null && !nowKst.isBefore(COUNTS_SETTLED_AT);
    }

    /** 판단 보류 문구 — 화면·브리핑이 같은 문장을 쓴다. */
    public static String insufficientMessage(Integer kospiValidDays, Integer kosdaqValidDays) {
        return String.format("시장 폭(상승·하락 종목 수) 데이터 부족 — 최근 %d거래일 중 코스피 %s일·코스닥 %s일만 수집돼 "
                        + "ADR 판단을 보류합니다(%d일 필요). ",
                ADR_PERIOD,
                kospiValidDays == null ? "?" : kospiValidDays.toString(),
                kosdaqValidDays == null ? "?" : kosdaqValidDays.toString(),
                MIN_VALID_DAYS);
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("").replace(",", "").trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
