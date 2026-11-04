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
     * @return 판정 불가({@link #judgeCumulative} 가 null)도 false — 저장 칸({@code cumulative}, NOT NULL)용이다. 그 false 를
     *         '개별 분기'로 읽지 않도록 소비처는 {@link #usableIndividualQuarters} 를 쓴다(2026-10-19)
     */
    public static boolean detectCumulative(List<Figures> rows) {
        return Boolean.TRUE.equals(judgeCumulative(rows));
    }

    /**
     * 누적 여부 판정 — TRUE 누적 · FALSE 개별 · <b>null 판정 불가</b>(인접 분기 쌍이 {@link #CUMULATIVE_MIN_PAIRS} 미만 —
     * 상장 1년 미만, 매출 공란 분기)(2026-10-19). 판정 규칙은 {@link #detectCumulative} 설명 그대로다.
     */
    public static Boolean judgeCumulative(List<Figures> rows) {
        if (rows == null) return null;

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
        if (pairs < CUMULATIVE_MIN_PAIRS) return null;   // 판정 불가 — '개별'이 아니다
        if (risingRatios.isEmpty()) return false;
        if ((double) risingRatios.size() / pairs < CUMULATIVE_RISING_SHARE) return false;

        risingRatios.sort(Comparator.naturalOrder());
        BigDecimal median = risingRatios.get(risingRatios.size() / 2);
        return median.compareTo(CUMULATIVE_MEDIAN_RATIO) >= 0;
    }

    // ==================== 개별 분기 환산 ====================

    /**
     * 개별 분기로 쓸 수 있는 것만 — 누적 행은 {@link #toIndividualQuarters} 로 환산하고, 개별로 표시된 행은 '누적이 아니다'로
     * <b>판정될 때만</b> 그대로 쓴다(2026-10-19). 수집기 TTM·성장률 배치·실적 서프라이즈가 모두 이 함수를 거친다.
     *
     * <p>KIS 분기 손익은 사실상 전 종목이 누적(YTD)이라, 판정 불가(상장 1년 미만·매출 공란 분기)를 개별로 읽으면 반기 누적이
     * 'Q2'가 되어 가짜 실적 급증(+110%·최대 20점)이 나오고 누적값 4개를 더한 TTM 이 부푼다(영업이익 1,000 vs 실제 400) —
     * §4c(판정 못 하면 제외). 저장 칸은 판정 불가도 false 라 이 판정은 읽는 쪽에서 같은 함수({@link #judgeCumulative})로 다시 한다.
     */
    public static List<Figures> usableIndividualQuarters(List<Figures> rows) {
        if (rows == null || rows.isEmpty()) return new ArrayList<>();
        // 빈 행은 분기가 아니다(2026-11-04) — 빠진 분기는 연속이 끊긴 것으로 다뤄야 TTM·전년 동기가 지어낸 값이 되지 않는다
        List<Figures> reported = new ArrayList<>(rows);
        reported.removeIf(QuarterlyFinancials::isBlankReport);
        boolean anyCumulative = reported.stream().anyMatch(Figures::cumulative);
        if (!anyCumulative && !Boolean.FALSE.equals(judgeCumulative(reported))) return new ArrayList<>();
        return toIndividualQuarters(reported);
    }

    /**
     * KIS 가 자료가 없는 분기에 주는 <b>빈 행</b>인가 — 매출·영업이익·순이익이 전부 0(또는 공란)(2026-11-04).
     *
     * <p>KIS 분기 손익은 사실상 연결재무제표 회사만 이어서 준다(11/4 전 종목: 연결 2,095개 중 2,083개 최신 vs 별도 488개 중
     * 11개). 그 밖의 회사는 끊긴 뒤의 분기를 0 으로 준다. 세 값이 동시에 정확히 0 인 실적은 없다 — 실적 0 으로 읽어 더하면
     * 두산테스나 매출이 828억(2024년 4분기 + 0·0·0, 실제 최근 4분기 3,264억)이 되고, 누적 환산이 연간 값을 '4분기 한 분기'로
     * 읽어 한화엔진 매출이 실제의 약 1.5배가 된다. 하나라도 값이 있으면 실제 보고다(매출 없는 바이오의 적자 분기).
     */
    static boolean isBlankReport(Figures f) {
        return f == null || (isZeroOrMissing(f.revenue()) && isZeroOrMissing(f.operatingProfit())
                && isZeroOrMissing(f.netIncome()));
    }

    private static boolean isZeroOrMissing(BigDecimal v) {
        return v == null || v.signum() == 0;
    }

    /**
     * 개별 분기 목록의 최신 분기가 {@code maxAgeDays} 보다 오래됐는가 — 그런 '최근 4분기'는 자료가 끊긴 지점의 실적이다
     * (2026-11-04). {@link #ttmSum} 은 나이를 보지 않아, KIS 분기가 끊긴 회사의 몇 년 전 4분기가 오늘 값으로 저장됐다 —
     * 파미셀 순이익 −267억(2014-06 까지의 4분기, 실제 최근 4분기 +466억), HK이노엔 매출 8,465억(2022년 연간, 실제 1조 786억).
     * 분기가 없으면 false — 판단할 것이 없다.
     */
    public static boolean isStale(List<Figures> individuals, LocalDate today, int maxAgeDays) {
        if (individuals == null || today == null) return false;
        LocalDate latest = null;
        for (Figures f : individuals) {
            if (f != null && f.periodEnd() != null && (latest == null || f.periodEnd().isAfter(latest))) {
                latest = f.periodEnd();
            }
        }
        return latest != null && latest.isBefore(today.minusDays(maxAgeDays));
    }

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

    /**
     * {@code end} 월로 끝나는 <b>연속 4분기</b> 합 {@code [매출, 영업이익, 순이익]} — 한 분기라도 없으면 null.
     * {@link #ttmSum} 이 "가장 최근 4분기"라면 이건 "지정한 분기에서 끝나는 4분기"다(1년 전 TTM 용).
     */
    public static BigDecimal[] ttmSumEndingAt(List<Figures> individuals, YearMonth end) {
        if (individuals == null || end == null) return null;
        java.util.Map<YearMonth, Figures> byMonth = new java.util.HashMap<>();
        for (Figures f : individuals) {
            if (f != null && f.periodEnd() != null) byMonth.putIfAbsent(YearMonth.from(f.periodEnd()), f);
        }
        List<Figures> window = new ArrayList<>();
        for (int i = TTM_QUARTERS - 1; i >= 0; i--) {
            Figures f = byMonth.get(end.minusMonths((long) QUARTER_MONTHS * i));
            if (f == null) return null;
            window.add(f);
        }
        return new BigDecimal[]{
                sumOrNull(window, Figures::revenue),
                sumOrNull(window, Figures::operatingProfit),
                sumOrNull(window, Figures::netIncome)
        };
    }

    /**
     * TTM 전년 동기 대비 성장률 — 성장률 배치의 단일 산식(2026-09-29).
     *
     * <p>최신 개별 분기로 끝나는 TTM 과 그 12개월 전 분기로 끝나는 TTM 을 비교한다. 같은 길이·같은 계절끼리라
     * 단일 분기(계절성)나 누적값(YTD)을 섞을 수 없다. 이전 배치는 일별 행(TTM)을 "1년 전 ±30일"의 아무 행과
     * 비교해 한 분기짜리 행과 섞였고(운영 9/28: 매출 성장률이 채워진 종목의 95%가 ±200% 초과), 1년 전 행이 없으면 30일 전 행을 썼다.
     *
     * @return 최신 분기 말일 + 매출·순이익 성장률(%). 두 TTM 중 하나라도 못 만들면 성장률은 null.
     *         개별 분기가 하나도 없으면 null.
     */
    public static TtmGrowth ttmYearOverYear(List<Figures> individuals) {
        if (individuals == null) return null;
        Figures latest = null;
        for (Figures f : individuals) {
            if (f != null && f.periodEnd() != null && (latest == null || f.periodEnd().isAfter(latest.periodEnd()))) {
                latest = f;
            }
        }
        if (latest == null) return null;
        YearMonth end = YearMonth.from(latest.periodEnd());
        BigDecimal[] now = ttmSumEndingAt(individuals, end);
        BigDecimal[] ago = ttmSumEndingAt(individuals, end.minusYears(1));
        if (now == null || ago == null) return new TtmGrowth(latest.periodEnd(), null, null);
        return new TtmGrowth(latest.periodEnd(), growthPct(now[0], ago[0]), growthPct(now[2], ago[2]));
    }

    /** @param revenueGrowth 매출 TTM 증가율(%) · @param netIncomeGrowth 순이익 TTM 증가율(%) — 모르면 null */
    public record TtmGrowth(LocalDate latestPeriodEnd, BigDecimal revenueGrowth, BigDecimal netIncomeGrowth) {}

    /**
     * (지금 − 기준) / 기준 × 100, 소수 2자리. 기준이 0 이하이거나 결측이면 null —
     * 적자를 분모로 한 변화율은 성장률이 아니다(§4c, 적자 축소가 +90% 로 뒤집힌다).
     */
    public static BigDecimal growthPct(BigDecimal now, BigDecimal base) {
        if (now == null || base == null || base.signum() <= 0) return null;
        return now.subtract(base).multiply(BigDecimal.valueOf(100)).divide(base, 2, java.math.RoundingMode.HALF_UP);
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
     * {@code latest} 와 같은 분기의 <b>1년 전</b> 개별 분기(회계기간 말 월이 정확히 12개월 전) — 없으면 null.
     *
     * <p>직전 분기 대비는 계절성을 "급증"으로 읽는다(1분기가 매년 약한 회사는 2분기마다 서프라이즈).
     * 전년 동기는 같은 계절끼리 비교라 그 착시가 없다(2026-09-29, 베뉴지·주성엔지니어링).
     *
     * @param individuals {@link #toIndividualQuarters} 결과
     */
    public static Figures sameQuarterYearAgo(List<Figures> individuals, Figures latest) {
        if (individuals == null || latest == null || latest.periodEnd() == null) return null;
        YearMonth target = YearMonth.from(latest.periodEnd()).minusYears(1);
        for (Figures f : individuals) {
            if (f != null && f.periodEnd() != null && YearMonth.from(f.periodEnd()).equals(target)) return f;
        }
        return null;
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
