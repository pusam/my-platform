package com.myplatform.backend.dartfinancial;

import com.myplatform.backend.dartfinancial.ControllingEarnings.ReportCode;
import com.myplatform.backend.dartfinancial.ControllingEarnings.ReportKey;
import com.myplatform.backend.entity.StockQuarterlyFinancial;
import com.myplatform.backend.service.QuarterlyFinancials;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DART 정기보고서 → 개별 분기(2026-11-05). 전부 부작용 없는 static 함수라 {@code DartQuarterlyTest} 로 고정한다.
 *
 * <p><b>왜</b>: KIS 분기 손익은 연결재무제표 회사만 이어서 준다(2026-11-04: 연결 2,083/2,095 최신, 별도 11/488). 나머지 약 490종목은
 * KIS 로는 매출·영업이익·순이익을 모른다 — 실적 축 20점을 받을 수 없어 구조적으로 불리했다. DART 는 그 회사들의 재무제표를 준다.
 *
 * <p><b>환산은 저장 전에 한다</b>: 보고서는 연초부터의 누적이고 어느 보고서인지 안다 — 2분기 = 반기 − 1분기, 3분기 = 3분기 누적 − 반기,
 * 4분기 = 연간 − 3분기 누적. KIS 처럼 "매출이 줄었으면 회계연도가 바뀌었다"고 짐작할 필요가 없어서, 매출 행이 없는 은행이나
 * 1분기가 작년 연간보다 큰 회사도 틀리지 않는다. 그래서 {@code stock_quarterly_financial} 의 DART 행은 cumulative=0 이다.
 *
 * <p><b>§4c</b>: 앞 누적이 없으면 그 분기를 모른다(누적을 개별인 척 쓰지 않는다). 연결/별도가 다른 두 보고서는 빼지 않는다.
 * 12월 결산만 다룬다 — 보고서 코드를 달력 분기로 옮기는 규칙이 12월 기준이다({@link ControllingEarnings#ttm} 과 같은 이유).
 */
public final class DartQuarterly {

    private DartQuarterly() {}

    /**
     * 종목당 지켜 두는 보고서 수 — 최근 분기가 8개 나오는 최소치다. 성장률(TTM 전년 동기)은 연속 8분기, 실적 판정은 전년 동기까지 쓴다.
     * 9개 중 가장 오래된 하나는 앞 누적이 없어 개별 분기가 되지 않을 수 있다.
     */
    public static final int REPORTS_TO_KEEP = 9;

    /**
     * KIS 가 새 분기를 반영하기까지 기다리는 날 — 제출 기한 + 이 기간이 지나도 실적이 없을 때만 빈 분기로 본다. 202606 분기가 KIS 에
     * 처음 나타난 날은 기한(8/14) 뒤 9/10 까지 이어졌다(운영 실측). 이 기간이 짧으면 곧 채워질 연결 회사까지 호출한다.
     */
    static final int KIS_LAG_DAYS = 30;

    /**
     * 자본총계가 시가총액의 이 배수를 넘으면 금액 단위를 잘못 공시한 보고서로 본다. 2026-11-05 전 종목(보고서 9,000여 건)에서 200배를 넘는 것은
     * 세 종목뿐이다: 진코스텍 2026 반기(728배 — 자본 26조, 시총 363억, 앞뒤 보고서의 1,000배), 다원넥스뷰 2024 반기(13만 배), SPAC 한 건(92만 배).
     * 그다음이 87배·34배·19배라 사이가 크게 비어 있다(숫자를 성적에 맞춰 옮기지 말 것).
     */
    static final BigDecimal MAX_EQUITY_TO_MARKET_CAP = BigDecimal.valueOf(200);

    /** 보고서 한 건 — 누적(억원). */
    public record Statement(ReportKey key, String fsDiv, BigDecimal revenue, BigDecimal operatingProfit, BigDecimal netIncome) {}

    /** 개별 분기(억원). */
    public record Quarter(String fiscalPeriod, LocalDate periodEnd, BigDecimal revenue, BigDecimal operatingProfit,
                          BigDecimal netIncome) {}

    /** 보고서 시점의 자본·자산·부채(억원). */
    public record Balance(BigDecimal totalEquity, BigDecimal totalAssets, BigDecimal totalLiabilities) {
        /** 부채비율(%) — 자본이 0 이하면 뜻이 없다(null). */
        public BigDecimal debtRatio() {
            if (totalLiabilities == null || totalEquity == null || totalEquity.signum() <= 0) return null;
            return totalLiabilities.multiply(BigDecimal.valueOf(100)).divide(totalEquity, 2, RoundingMode.HALF_UP);
        }
    }

    /** 같은 회계연도에서 앞선 보고서 — 1분기는 없다. */
    private static ReportCode previousOf(ReportCode code) {
        return switch (code) {
            case Q1 -> null;
            case H1 -> ReportCode.Q1;
            case Q3 -> ReportCode.H1;
            case FY -> ReportCode.Q3;
        };
    }

    /** 보고서(누적) → 개별 분기, 오래된 것부터. 앞 누적이 없는 분기는 빠지고, 세 값이 다 없으면 행을 만들지 않는다. */
    public static List<Quarter> individualQuarters(Collection<Statement> statements) {
        List<Quarter> out = new ArrayList<>();
        if (statements == null) return out;
        Map<ReportKey, Statement> byKey = new HashMap<>();
        for (Statement s : statements) byKey.put(s.key(), s);

        List<Statement> asc = new ArrayList<>(byKey.values());
        asc.sort(Comparator.comparing(Statement::key));
        for (Statement s : asc) {
            ReportCode previous = previousOf(s.key().code());
            BigDecimal revenue = s.revenue();
            BigDecimal operating = s.operatingProfit();
            BigDecimal net = s.netIncome();
            if (previous != null) {
                Statement before = byKey.get(new ReportKey(s.key().year(), previous));
                if (before == null || !Objects.equals(before.fsDiv(), s.fsDiv())) continue;
                revenue = minus(revenue, before.revenue());
                operating = minus(operating, before.operatingProfit());
                net = minus(net, before.netIncome());
            }
            if (revenue == null && operating == null && net == null) continue;
            // 누적 매출이 줄었다 = 나중 보고서가 중단영업을 재분류했다. 두 누적의 차이는 그 분기 실적이 아니다 — 모른다(188260 2025 4분기 −49억)
            if (previous != null && revenue != null && revenue.signum() < 0) continue;
            out.add(new Quarter(fiscalPeriod(s.key()), periodEnd(s.key()), revenue, operating, net));
        }
        return out;
    }

    private static BigDecimal minus(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.subtract(b);
    }

    /**
     * 최근 4분기 {@code [매출, 영업이익, 순이익]} = 최신 누적 + 직전 연간 − 전년 동기 누적(최신 보고서 자신의 비교 칸). 최신이 사업보고서면 그 연간값.
     *
     * <p>분기를 더하지 않는 이유: 연중에 연결을 시작한 회사(최근 보고서 안에서 78종목)는 앞 보고서와 기준이 달라 분기가 비고, 중단영업을 재분류한
     * 회사는 앞 누적이 옛 기준이라 합이 틀린다. 비교 칸은 최신 보고서와 기준이 같고 재분류도 반영돼 있다 — 지배주주 순이익 TTM 과 같은 식이다.
     * 최신 보고서와 직전 연간의 연결/별도가 다르면 모른다. 최신 분기가 {@link ControllingEarnings#MAX_REPORT_AGE_DAYS} 보다 오래됐어도 모른다.
     * 알 수 없으면 null — 항목별로는 그 항목만 null 이다.
     */
    public static BigDecimal[] ttm(Collection<DartControllingFinancial> reports, LocalDate today) {
        if (reports == null) return null;
        DartControllingFinancial latest = null;
        for (DartControllingFinancial r : reports) {
            if (r.isIncomeParsed() && (latest == null || r.key().compareTo(latest.key()) > 0)) latest = r;
        }
        if (latest == null || periodEnd(latest.key()).isBefore(today.minusDays(ControllingEarnings.MAX_REPORT_AGE_DAYS))) return null;
        if (latest.key().code().annual()) {
            return new BigDecimal[]{latest.getRevenue(), latest.getOperatingProfit(), latest.getTotalNetIncome()};
        }
        ReportKey priorKey = latest.key().priorAnnual();
        DartControllingFinancial prior = null;
        for (DartControllingFinancial r : reports) {
            if (r.isIncomeParsed() && r.key().equals(priorKey)) prior = r;
        }
        if (prior == null || !Objects.equals(prior.getFsDiv(), latest.getFsDiv())) return null;
        BigDecimal[] ttm = {
                rolled(latest.getRevenue(), prior.getRevenue(), latest.getRevenuePrev()),
                rolled(latest.getOperatingProfit(), prior.getOperatingProfit(), latest.getOperatingProfitPrev()),
                rolled(latest.getTotalNetIncome(), prior.getTotalNetIncome(), latest.getTotalNetIncomePrev())};
        return ttm[0] == null && ttm[1] == null && ttm[2] == null ? null : ttm;
    }

    private static BigDecimal rolled(BigDecimal latest, BigDecimal annual, BigDecimal priorSamePeriod) {
        return latest == null || annual == null || priorSamePeriod == null ? null : latest.add(annual).subtract(priorSamePeriod);
    }

    /** 금액 단위를 잘못 공시한 보고서로 보이는가 — 시가총액을 모르면 판단하지 않는다. */
    static boolean scaleSuspect(BigDecimal totalEquity, BigDecimal marketCap) {
        return totalEquity != null && marketCap != null && marketCap.signum() > 0
                && totalEquity.compareTo(marketCap.multiply(MAX_EQUITY_TO_MARKET_CAP)) > 0;
    }

    /** "202606" — 12월 결산 기준. */
    static String fiscalPeriod(ReportKey key) {
        return String.format("%04d%02d", key.year(), key.code().endMonth);
    }

    static LocalDate periodEnd(ReportKey key) {
        return YearMonth.of(key.year(), key.code().endMonth).atEndOfMonth();
    }

    /**
     * DART 로 채울 종목인가 — 이미 DART 행이 있거나, 기한이 넉넉히 지난 분기 중 실적 없는 분기가 있을 때.
     * 이미 채운 종목은 새 보고서를 KIS 처럼 기다리지 않는다(별도 회사는 KIS 에 영영 안 나온다).
     */
    public static boolean needsFill(Collection<StockQuarterlyFinancial> rows, List<ReportKey> keys, LocalDate today) {
        Set<String> reported = new HashSet<>();
        if (rows != null) {
            for (StockQuarterlyFinancial q : rows) {
                if (q.isDart()) return true;
                if (!QuarterlyFinancials.isBlankReport(q.getRevenue(), q.getOperatingProfit(), q.getNetIncome())) {
                    reported.add(q.getFiscalPeriod());
                }
            }
        }
        for (ReportKey key : keys) {
            if (key.dueDate().plusDays(KIS_LAG_DAYS).isAfter(today)) continue;
            if (!reported.contains(fiscalPeriod(key))) return true;
        }
        return false;
    }

    /**
     * 가장 최근 보고서의 자본·자산·부채 — 접수가 {@link ControllingEarnings#MAX_REPORT_AGE_DAYS} 안이고 자본총계가 있을 때만.
     * 재무상태표는 시점 값이라 결산월과 무관하다.
     */
    public static Balance latestBalance(Collection<DartControllingFinancial> reports, LocalDate today) {
        if (reports == null) return null;
        DartControllingFinancial latest = null;
        for (DartControllingFinancial r : reports) {
            if (!r.isIncomeParsed() || r.getTotalEquity() == null || r.getFiledOn() == null) continue;
            if (latest == null || r.key().compareTo(latest.key()) > 0) latest = r;
        }
        if (latest == null || latest.getFiledOn().isBefore(today.minusDays(ControllingEarnings.MAX_REPORT_AGE_DAYS))) return null;
        return new Balance(latest.getTotalEquity(), latest.getTotalAssets(), latest.getTotalLiabilities());
    }
}
