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

    /** 누적 판정에 필요한 최소 인접쌍 수. 이보다 적으면 판정 불가(false, 종전 동작). */
    static final int CUMULATIVE_MIN_PAIRS = 3;

    /** 인접쌍 중 "증가" 비율이 이 값 미만이면 누적 아님. */
    static final double CUMULATIVE_RISING_SHARE = 0.5;

    /** 증가 구간의 <b>배율 중앙값</b>이 이 값 이상이어야 누적. 실측 누적 ≈1.75 / 개별 ≈1.10. */
    static final BigDecimal CUMULATIVE_MEDIAN_RATIO = new BigDecimal("1.25");

    /**
     * 원본 분기 행들이 <b>누적(YTD)</b> 인지 판정 — 이력 전체의 인접쌍을 <b>두 가지로</b> 본다.
     *
     * <p><b>왜 이렇게 바뀌었나(2026-08-27)</b>: 이전 판정은 <b>최신 3개</b>만 보고 단조증가를
     * 확인했는데, 회계연도 경계가 그 안에 들어오면 반드시 깨진다. 실측 삼성전자 최신 3분기가
     * {@code 202606(30,537) → 202603(13,387) → 202512(33,360)} 이라 202603 리셋에 걸려
     * "누적 아님"으로 판정됐다. 8월은 FY 리셋 직후라 <b>구조적으로 실패하는 시기</b>다.
     * 그 결과 2,523/2,618종목(96%)이 오판됐고 TTM 이 2배 넘게 부풀었다.
     *
     * <h4>판별자 두 개를 함께 쓰는 이유</h4>
     * "증가 비율"만으로는 <b>성장하는 개별 분기</b>와 안 갈린다 — 둘 다 60% 근처가 나온다.
     * 결정적인 차이는 <b>증가 배율</b>이다. 누적이면 분기가 더해지므로
     * Q1→Q2 ≈ 2.0배, Q2→Q3 ≈ 1.5배, Q3→Q4 ≈ 1.33배로 <b>크게</b> 뛴다.
     * 개별이면 연속 분기가 비슷한 규모라 ≈1.1배다. 실측 중앙값 — 누적 1.75 / 개별 1.10.
     *
     * <p>이력 전체를 쓰므로 <b>어느 시점에 조회해도 판정이 흔들리지 않는다</b>.
     *
     * @param rows 순서 무관 — 내부에서 periodEnd 오름차순 정렬. 매출 결측·비양수 행은 제외
     * @return 인접쌍이 {@link #CUMULATIVE_MIN_PAIRS} 미만이면 false(판정 불가 = 종전 동작)
     */
    public static boolean detectCumulative(List<Figures> rows) {
        if (rows == null) return false;

        List<Figures> asc = new ArrayList<>(rows);
        asc.removeIf(f -> f == null || f.periodEnd() == null || f.revenue() == null
                || f.revenue().signum() <= 0);
        asc.sort(Comparator.comparing(Figures::periodEnd));

        int pairs = 0;
        List<BigDecimal> risingRatios = new ArrayList<>();
        for (int i = 1; i < asc.size(); i++) {
            if (!isAdjacentQuarter(asc.get(i - 1).periodEnd(), asc.get(i).periodEnd())) continue;
            pairs++;
            BigDecimal prev = asc.get(i - 1).revenue();
            BigDecimal cur = asc.get(i).revenue();
            if (cur.compareTo(prev) > 0) {
                risingRatios.add(cur.divide(prev, 4, java.math.RoundingMode.HALF_UP));
            }
        }
        if (pairs < CUMULATIVE_MIN_PAIRS) return false;
        if (risingRatios.isEmpty()) return false;
        if ((double) risingRatios.size() / pairs < CUMULATIVE_RISING_SHARE) return false;

        risingRatios.sort(Comparator.naturalOrder());
        BigDecimal median = risingRatios.get(risingRatios.size() / 2);
        return median.compareTo(CUMULATIVE_MEDIAN_RATIO) >= 0;
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

    /** TTM 합산에 필요한 분기 수. */
    public static final int TTM_QUARTERS = 4;

    /**
     * TTM(최근 4분기 합) — <b>개별 분기로 환산한 뒤</b> 더한다.
     *
     * <p><b>왜 이 형태인가(2026-08-27)</b>: 이전 코드는 누적 판정이 true 일 때
     * {@code output.get(3)} 을 "연간 행"으로 가정해 그 값을 TTM 으로 썼다. 그런데 응답은
     * 최신순이라 4번째 항목이 연간이라는 보장이 없다 — 2026-08 기준 삼성전자는
     * {@code [202606, 202603, 202512, 202509]} 이라 4번째가 <b>3분기 누적</b>이었다.
     * 그리고 누적 판정이 실패하면(당시 96%) 누적값 4개를 그냥 더해 <b>2배 넘게</b> 부풀었다
     * (실측: 30,537+13,387+33,360+23,976 = 101,260 vs 진짜 48,527).
     *
     * <p>개별 분기로 환산한 뒤 최근 4개를 더하면 두 경우 모두 옳다. 삼성전자 실측으로 검산하면
     * 17,150+13,387+9,384+8,606 = 48,527 로 정공법(FY2025 + H1-2026 − H1-2025)과 일치한다.
     *
     * @param individuals {@link #toIndividualQuarters} 결과(개별 분기, 오름차순 가정 아님)
     * @return {@code [revenue, operatingProfit, netIncome]}. 4분기 미만이면 null.
     *         각 항목은 그 항목이 한 분기라도 결측이면 null(§4c — 결측을 0 으로 더하지 않는다)
     */
    public static BigDecimal[] ttmSum(List<Figures> individuals) {
        if (individuals == null || individuals.size() < TTM_QUARTERS) return null;
        List<Figures> asc = new ArrayList<>(individuals);
        asc.removeIf(f -> f == null || f.periodEnd() == null);
        if (asc.size() < TTM_QUARTERS) return null;
        asc.sort(Comparator.comparing(Figures::periodEnd));
        List<Figures> last = asc.subList(asc.size() - TTM_QUARTERS, asc.size());

        // 연속한 4분기여야 TTM 이다 — 중간이 비면 12개월치가 아니다.
        for (int i = 1; i < last.size(); i++) {
            if (!isAdjacentQuarter(last.get(i - 1).periodEnd(), last.get(i).periodEnd())) return null;
        }
        return new BigDecimal[]{
                sumOrNull(last, Figures::revenue),
                sumOrNull(last, Figures::operatingProfit),
                sumOrNull(last, Figures::netIncome)
        };
    }

    /** 한 분기라도 결측이면 null — 결측을 0 으로 더하면 TTM 이 조용히 축소된다(§4c). */
    private static BigDecimal sumOrNull(List<Figures> rows,
                                        java.util.function.Function<Figures, BigDecimal> getter) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Figures f : rows) {
            BigDecimal v = getter.apply(f);
            if (v == null) return null;
            sum = sum.add(v);
        }
        return sum;
    }

    /**
     * 원본 행 목록에 누적 판정 결과를 입혀 돌려준다 — 판정과 환산을 한 흐름으로 묶는 편의 함수.
     *
     * <p>수집기와 서프라이즈 판정이 <b>같은 경로</b>를 타게 해서 "저장할 때의 해석"과
     * "읽을 때의 해석"이 갈라지지 않게 한다.
     */
    public static List<Figures> withDetectedCumulative(List<Figures> rows) {
        boolean cumulative = detectCumulative(rows);
        List<Figures> out = new ArrayList<>();
        if (rows == null) return out;
        for (Figures f : rows) {
            if (f == null) continue;
            out.add(new Figures(f.fiscalPeriod(), f.periodEnd(), f.revenue(),
                    f.operatingProfit(), f.netIncome(), cumulative));
        }
        return out;
    }

    /**
     * 개별 분기 목록에서 <b>연속된 최신 3분기</b> — TURNAROUND 규칙 재설계 측정용(2026-08-28).
     *
     * <p>현재 규칙은 {@code prevOp < 0 && latestOp > 0}(직전 1분기만)이라
     * <b>한 분기 삐끗한 회사</b>도 턴어라운드로 잡는다. 실측 226건(커버리지의 9.9%)이 그 결과다.
     * "직전 2분기 연속 적자였나"를 보려면 한 분기 더 필요하다.
     *
     * <p>2분기 쌍과 같은 원칙 — <b>중간이 비면 null</b>. 건너뛴 비교는 연속성을 증명하지 못한다.
     *
     * @return {@code [latest, prev, prev2]} 또는 조건 미달 시 null
     */
    public static Figures[] latestAdjacentTriple(List<Figures> individuals) {
        if (individuals == null || individuals.size() < 3) return null;
        List<Figures> asc = new ArrayList<>(individuals);
        asc.removeIf(f -> f == null || f.periodEnd() == null);
        if (asc.size() < 3) return null;
        asc.sort(Comparator.comparing(Figures::periodEnd));

        Figures latest = asc.get(asc.size() - 1);
        Figures prev = asc.get(asc.size() - 2);
        Figures prev2 = asc.get(asc.size() - 3);
        if (!isAdjacentQuarter(prev.periodEnd(), latest.periodEnd())) return null;
        if (!isAdjacentQuarter(prev2.periodEnd(), prev.periodEnd())) return null;
        return new Figures[]{latest, prev, prev2};
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
