package com.myplatform.backend.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 분기 재무 순수함수 모음 — 파싱 · 누적(YTD) 판정 · 개별 분기 환산 · 인접 분기 판정.
 *
 * <p>전부 부작용 없는 static 함수라 단위 테스트로 고정한다({@code QuarterlyFinancialsTest}).
 * 수집기·서프라이즈 판정 양쪽이 <b>같은 함수</b>를 쓰게 해서 "저장할 때의 해석"과
 * "읽을 때의 해석"이 갈라지지 않게 하는 것이 목적이다.
 *
 * <p><b>§4c 원칙</b>: 판정할 수 없으면 그럴듯한 값을 만들지 않고 <b>제외</b>한다.
 * 누적 데이터에서 직전 분기 행이 없으면 그 분기는 환산 불가이므로 비교 대상에서 뺀다 —
 * 누적값을 개별 분기인 척 쓰면 "매출 3배 급증" 같은 유령 서프라이즈가 나온다.
 */
public final class QuarterlyFinancials {

    private QuarterlyFinancials() {}

    /**
     * 한 분기(또는 누적 시점)의 재무 3종.
     *
     * @param fiscalPeriod KIS {@code stac_yymm} 원본 (YYYYMM)
     * @param periodEnd    회계기간 말일 — 정렬·간격 판정용
     * @param cumulative   원본이 누적(YTD)이었는지 (값 자체는 미보정)
     */
    public record Figures(String fiscalPeriod,
                          LocalDate periodEnd,
                          BigDecimal revenue,
                          BigDecimal operatingProfit,
                          BigDecimal netIncome,
                          boolean cumulative) {}

    // ==================== 파싱 ====================

    /**
     * {@code stac_yymm}("202506") → {@link YearMonth}. 형식이 아니면 null(예외 아님).
     *
     * <p>KIS 가 빈 문자열·"-"·null 을 주는 종목이 있어서 호출부가 매번 try/catch 하지 않도록
     * null 로 흡수한다. null 은 "분기 정체성을 모름" 이고, 그런 행은 저장하지 않는다.
     */
    public static YearMonth parseFiscalPeriod(String stacYymm) {
        if (stacYymm == null) return null;
        String s = stacYymm.trim();
        if (s.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            if (!Character.isDigit(s.charAt(i))) return null;
        }
        try {
            YearMonth ym = YearMonth.of(Integer.parseInt(s.substring(0, 4)),
                                        Integer.parseInt(s.substring(4, 6)));
            // 상식 범위 밖(오타·쓰레기값)은 버린다. 1990 이전 분기 실적을 볼 일은 없다.
            if (ym.getYear() < 1990 || ym.getYear() > 2999) return null;
            return ym;
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** 회계기간 말일. {@code parseFiscalPeriod} 결과가 null 이면 null. */
    public static LocalDate periodEnd(YearMonth ym) {
        return ym == null ? null : ym.atEndOfMonth();
    }

    // ==================== 누적(YTD) 판정 ====================

    /** 누적 판정 임계 — 최신/최고(最古) 매출 비율이 이 값을 넘고 단조 증가면 누적으로 본다. */
    static final BigDecimal CUMULATIVE_RATIO_THRESHOLD = new BigDecimal("1.8");

    /**
     * 원본 분기 행들이 <b>누적(YTD)</b> 인지 판정 — 수집기에 인라인돼 있던 휴리스틱을 순수함수로 뺀 것.
     *
     * <p>판정 근거: 개별 분기라면 매출이 시기순으로 단조 증가할 이유가 없다. 누적이면
     * Q1 &lt; 반기 &lt; 3분기 순으로 반드시 커진다. 최신이 가장 크고 가장 오래된 것이 가장 작으면서
     * 비율이 1.8배를 넘으면 누적으로 본다.
     *
     * @param recentFirst 최신이 앞에 오는 행 목록 (KIS 응답 순서 그대로)
     * @return 3개 이상이고 위 조건을 만족할 때만 true. 판정 불가면 false(=개별로 취급, 종전 동작)
     */
    public static boolean detectCumulative(List<Figures> recentFirst) {
        if (recentFirst == null || recentFirst.size() < 3) return false;
        BigDecimal r0 = recentFirst.get(0).revenue();
        BigDecimal r1 = recentFirst.get(1).revenue();
        BigDecimal r2 = recentFirst.get(2).revenue();
        if (r0 == null || r1 == null || r2 == null) return false;
        if (r0.signum() <= 0 || r2.signum() <= 0) return false;
        if (r0.compareTo(r1) <= 0 || r1.compareTo(r2) <= 0) return false;
        BigDecimal ratio = r0.divide(r2, 2, java.math.RoundingMode.HALF_UP);
        return ratio.compareTo(CUMULATIVE_RATIO_THRESHOLD) > 0;
    }

    // ==================== 개별 분기 환산 ====================

    /** 인접 분기로 인정하는 개월 수. */
    private static final int QUARTER_MONTHS = 3;

    /**
     * 누적(YTD) 행들을 <b>개별 분기</b>로 환산 — 환산 가능한 것만 돌려준다.
     *
     * <p>규칙(회계연도 종료월을 모르는 상태에서 안전하게):
     * <ul>
     *   <li>직전(3개월 전) 행이 <b>없으면 제외</b> — 그 분기가 회계연도 첫 분기인지 알 수 없다.
     *       누적값을 개별인 척 쓰면 상반기 누적이 "Q2" 로 둔갑해 변화율이 뻥튀기된다(§4c).</li>
     *   <li>직전 행이 있고 매출이 <b>줄었으면</b> 회계연도가 바뀐 것 → 그 행 자체가 첫 분기 개별값.</li>
     *   <li>그 외에는 이번 누적 − 직전 누적.</li>
     * </ul>
     *
     * <p>매출을 회계연도 경계 판단의 기준으로 삼는 이유: 매출은 음수가 없어 누적이 단조 증가한다.
     * 이익은 적자 분기에 줄어들 수 있어 경계 신호로 못 쓴다. 경계 판단 결과는 3종 모두에 같이 적용.
     *
     * <p>입력이 이미 개별({@code cumulative=false})이면 그대로 돌려준다.
     *
     * @param rows 순서 무관 — 내부에서 periodEnd 오름차순 정렬
     * @return 개별 분기 값 (periodEnd 오름차순). 환산 불가 행은 빠져 있다.
     */
    public static List<Figures> toIndividualQuarters(List<Figures> rows) {
        List<Figures> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) return out;

        List<Figures> asc = new ArrayList<>(rows);
        asc.removeIf(f -> f == null || f.periodEnd() == null);
        asc.sort(Comparator.comparing(Figures::periodEnd));

        for (int i = 0; i < asc.size(); i++) {
            Figures cur = asc.get(i);
            if (!cur.cumulative()) { out.add(cur); continue; }

            Figures prev = (i == 0) ? null : asc.get(i - 1);
            if (prev == null || !prev.cumulative() || !isAdjacentQuarter(prev.periodEnd(), cur.periodEnd())) {
                // 직전 누적 행이 없다 = 회계연도 첫 분기인지 판별 불가 → 제외
                continue;
            }

            boolean fiscalYearReset = isFiscalYearReset(prev.revenue(), cur.revenue());
            if (fiscalYearReset) {
                out.add(new Figures(cur.fiscalPeriod(), cur.periodEnd(),
                        cur.revenue(), cur.operatingProfit(), cur.netIncome(), false));
            } else {
                out.add(new Figures(cur.fiscalPeriod(), cur.periodEnd(),
                        subtract(cur.revenue(), prev.revenue()),
                        subtract(cur.operatingProfit(), prev.operatingProfit()),
                        subtract(cur.netIncome(), prev.netIncome()),
                        false));
            }
        }
        return out;
    }

    /**
     * 누적 매출이 줄었으면 회계연도가 바뀐 것(= 이번 행이 새 회계연도 첫 분기).
     *
     * <p>둘 중 하나라도 결측이면 판단 불가 → false(=차감 경로). 차감 경로는 결측이 있으면
     * 어차피 null 을 내므로 유령 값이 만들어지지 않는다.
     */
    static boolean isFiscalYearReset(BigDecimal prevRevenue, BigDecimal curRevenue) {
        if (prevRevenue == null || curRevenue == null) return false;
        return curRevenue.compareTo(prevRevenue) < 0;
    }

    /** 한쪽이라도 null 이면 null — 결측을 0 으로 취급해 차액을 만들어내지 않는다(§4c). */
    static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.subtract(b);
    }

    /**
     * 두 회계기간 말일이 정확히 한 분기(3개월) 차이인지.
     *
     * <p>말일끼리라 일수(89~92일)가 들쭉날쭉하다 — {@code ChronoUnit.MONTHS} 로 세면
     * 2월 말(28/29일) 같은 경계도 정확하다.
     */
    public static boolean isAdjacentQuarter(LocalDate earlier, LocalDate later) {
        if (earlier == null || later == null) return false;
        if (!earlier.isBefore(later)) return false;
        return ChronoUnit.MONTHS.between(YearMonth.from(earlier), YearMonth.from(later)) == QUARTER_MONTHS;
    }

    /**
     * 개별 분기 목록에서 <b>비교 가능한 최신 인접 2분기</b>를 고른다.
     *
     * <p>가장 최근 것과 그 3개월 전 것이 모두 있어야 한다. 중간 분기가 결측이면
     * "전분기 대비"가 아니므로 비교하지 않는다(건너뛴 비교는 변화율을 뻥튀기한다).
     *
     * @return {@code [latest, previous]} 또는 조건 미달 시 null
     */
    public static Figures[] latestAdjacentPair(List<Figures> individuals) {
        if (individuals == null || individuals.size() < 2) return null;
        List<Figures> asc = new ArrayList<>(individuals);
        asc.removeIf(f -> f == null || f.periodEnd() == null);
        if (asc.size() < 2) return null;
        asc.sort(Comparator.comparing(Figures::periodEnd));

        Figures latest = asc.get(asc.size() - 1);
        Figures previous = asc.get(asc.size() - 2);
        if (!isAdjacentQuarter(previous.periodEnd(), latest.periodEnd())) return null;
        return new Figures[]{latest, previous};
    }
}
