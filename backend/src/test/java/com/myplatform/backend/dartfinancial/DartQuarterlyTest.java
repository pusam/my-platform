package com.myplatform.backend.dartfinancial;

import com.myplatform.backend.dartfinancial.ControllingEarnings.ReportCode;
import com.myplatform.backend.dartfinancial.ControllingEarnings.ReportKey;
import com.myplatform.backend.dartfinancial.DartQuarterly.Quarter;
import com.myplatform.backend.dartfinancial.DartQuarterly.Statement;
import com.myplatform.backend.entity.StockQuarterlyFinancial;
import com.myplatform.backend.service.QuarterlyFinancials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DART 보고서 → 개별 분기 — {@link DartQuarterly}(2026-11-05). KIS 분기는 연결재무제표 회사만 이어서 주므로(연결 2,083/2,095 최신,
 * 별도 11/488) 나머지 회사는 이 값이 유일한 최근 실적이다.
 */
class DartQuarterlyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static Statement st(int year, ReportCode code, String fs, String revenue, String operating, String net) {
        return new Statement(new ReportKey(year, code), fs, d(revenue), d(operating), d(net));
    }

    private static Quarter q(List<Quarter> out, String period) {
        return out.stream().filter(x -> x.fiscalPeriod().equals(period)).findFirst().orElse(null);
    }

    private static StockQuarterlyFinancial row(String period, String revenue, String operating, String net, String source) {
        return StockQuarterlyFinancial.builder().stockCode("058470").fiscalPeriod(period)
                .periodEnd(java.time.YearMonth.parse(period.substring(0, 4) + "-" + period.substring(4)).atEndOfMonth())
                .revenue(d(revenue)).operatingProfit(d(operating)).netIncome(d(net))
                .cumulative(!StockQuarterlyFinancial.SOURCE_DART.equals(source)).source(source).build();
    }

    @Test
    @DisplayName("누적 보고서 → 개별 분기 — 2분기 = 반기 − 1분기, 3분기 = 3분기 누적 − 반기, 4분기 = 연간 − 3분기 누적")
    void cumulativeBecomesIndividual() {
        List<Quarter> out = DartQuarterly.individualQuarters(List.of(
                st(2025, ReportCode.Q1, "OFS", "100", "30", "20"),
                st(2025, ReportCode.H1, "OFS", "220", "70", "50"),
                st(2025, ReportCode.Q3, "OFS", "360", "120", "90"),
                st(2025, ReportCode.FY, "OFS", "500", "150", "100"),
                st(2026, ReportCode.Q1, "OFS", "130", "40", "30")));

        assertThat(out).extracting(Quarter::fiscalPeriod).containsExactly("202503", "202506", "202509", "202512", "202603");
        assertThat(q(out, "202503").revenue()).isEqualByComparingTo("100");
        assertThat(q(out, "202506").revenue()).isEqualByComparingTo("120");
        assertThat(q(out, "202509").operatingProfit()).isEqualByComparingTo("50");
        assertThat(q(out, "202512").revenue()).isEqualByComparingTo("140");
        assertThat(q(out, "202512").netIncome()).isEqualByComparingTo("10");
        assertThat(q(out, "202603").revenue()).isEqualByComparingTo("130");
        assertThat(q(out, "202512").periodEnd()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    @DisplayName("1분기가 작년 연간보다 커도 회계연도를 틀리지 않는다 — KIS 경로는 '매출이 줄면 새 회계연도'로 짐작해 이런 회사를 잘못 뺀다")
    void hyperGrowthFirstQuarter() {
        List<Quarter> out = DartQuarterly.individualQuarters(List.of(
                st(2025, ReportCode.Q3, "OFS", "8", "1", "1"),
                st(2025, ReportCode.FY, "OFS", "10", "2", "2"),
                st(2026, ReportCode.Q1, "OFS", "12", "3", "3")));

        assertThat(q(out, "202603").revenue()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("매출 행이 없는 회사(은행)도 영업이익·순이익 분기는 나온다 — 매출로 회계연도를 가르지 않기 때문")
    void noRevenueAccount() {
        List<Quarter> out = DartQuarterly.individualQuarters(List.of(
                st(2026, ReportCode.Q1, "OFS", null, "300", "200"),
                st(2026, ReportCode.H1, "OFS", null, "800", "500")));

        assertThat(q(out, "202606").revenue()).isNull();
        assertThat(q(out, "202606").operatingProfit()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("§4c — 앞 누적이 없으면 그 분기를 모른다(반기 누적을 '2분기'로 쓰지 않는다)")
    void missingPreviousCumulative() {
        List<Quarter> out = DartQuarterly.individualQuarters(List.of(
                st(2026, ReportCode.H1, "OFS", "220", "70", "50"),
                st(2026, ReportCode.Q3, "OFS", "360", "120", "90")));

        assertThat(out).extracting(Quarter::fiscalPeriod).containsExactly("202609");
    }

    @Test
    @DisplayName("연결/별도가 다른 두 보고서는 빼지 않는다 — 정의가 다른 값의 차이는 분기 실적이 아니다")
    void differentBasisNotSubtracted() {
        List<Quarter> out = DartQuarterly.individualQuarters(List.of(
                st(2026, ReportCode.Q1, "OFS", "100", "10", "10"),
                st(2026, ReportCode.H1, "CFS", "900", "90", "90")));

        assertThat(out).extracting(Quarter::fiscalPeriod).containsExactly("202603");
    }

    @Test
    @DisplayName("한 칸만 없으면 그 칸만 모른다 — 다른 칸은 쓴다")
    void oneMissingFigure() {
        List<Quarter> out = DartQuarterly.individualQuarters(List.of(
                st(2026, ReportCode.Q1, "OFS", "100", null, "10"),
                st(2026, ReportCode.H1, "OFS", "220", "70", "50")));

        assertThat(q(out, "202606").operatingProfit()).isNull();
        assertThat(q(out, "202606").revenue()).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("채울 종목 — KIS 분기가 끊긴 회사(0·0·0 행뿐) · 분기 행이 아예 없는 회사")
    void blankKisRowsNeedFill() {
        List<ReportKey> keys = ControllingEarnings.candidates(TODAY, DartQuarterly.REPORTS_TO_KEEP);

        assertThat(DartQuarterly.needsFill(List.of(), keys, TODAY)).isTrue();
        assertThat(DartQuarterly.needsFill(List.of(row("202606", "0", "0", "0", StockQuarterlyFinancial.SOURCE_KIS)), keys, TODAY)).isTrue();
    }

    @Test
    @DisplayName("채우지 않을 종목 — KIS 분기가 다 있는 회사. 곧 나올 연결 회사까지 호출하지 않는다")
    void completeKisRowsDoNotNeedFill() {
        List<ReportKey> keys = ControllingEarnings.candidates(TODAY, DartQuarterly.REPORTS_TO_KEEP);
        List<StockQuarterlyFinancial> rows = new ArrayList<>();
        for (ReportKey key : keys) {
            rows.add(row(DartQuarterly.fiscalPeriod(key), "100", "10", "10", StockQuarterlyFinancial.SOURCE_KIS));
        }

        assertThat(DartQuarterly.needsFill(rows, keys, TODAY)).isFalse();
        // 기한(8/14)이 막 지난 8/20 — KIS 가 아직 안 준 202606 은 빈 분기가 아니다
        LocalDate justAfterDue = LocalDate.of(2026, 8, 20);
        List<ReportKey> early = ControllingEarnings.candidates(justAfterDue, DartQuarterly.REPORTS_TO_KEEP);
        rows.removeIf(r -> r.getFiscalPeriod().equals("202606"));
        rows.add(row("202403", "100", "10", "10", StockQuarterlyFinancial.SOURCE_KIS));
        assertThat(DartQuarterly.needsFill(rows, early, justAfterDue)).isFalse();
    }

    @Test
    @DisplayName("이미 DART 로 채운 종목은 새 보고서를 기다리지 않는다 — 별도 회사는 KIS 에 영영 안 나온다")
    void dartRowsKeepFilling() {
        List<ReportKey> keys = ControllingEarnings.candidates(TODAY, DartQuarterly.REPORTS_TO_KEEP);

        assertThat(DartQuarterly.needsFill(List.of(row("202606", "100", "10", "10", StockQuarterlyFinancial.SOURCE_DART)), keys, TODAY)).isTrue();
    }

    @Test
    @DisplayName("저장 행 읽기 — DART 행은 누적 판정 없이 개별 분기, 같은 기간은 DART 가 이기고 KIS 빈 행은 분기가 아니다")
    void storedRowsReader() {
        List<StockQuarterlyFinancial> rows = List.of(
                row("202503", "100", "30", "20", StockQuarterlyFinancial.SOURCE_DART),
                row("202506", "120", "40", "30", StockQuarterlyFinancial.SOURCE_DART),
                row("202509", "0", "0", "0", StockQuarterlyFinancial.SOURCE_KIS));

        List<QuarterlyFinancials.Figures> out = QuarterlyFinancials.usableFromStored(rows);

        assertThat(out).extracting(QuarterlyFinancials.Figures::fiscalPeriod).containsExactly("202503", "202506");
        assertThat(out.get(1).revenue()).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("DART 행 4분기로 TTM 이 나온다 — KIS 경로였으면 판정 불가로 비었다")
    void ttmFromDartRows() {
        List<StockQuarterlyFinancial> rows = List.of(
                row("202509", "100", "10", "9", StockQuarterlyFinancial.SOURCE_DART),
                row("202512", "110", "20", "8", StockQuarterlyFinancial.SOURCE_DART),
                row("202603", "120", "30", "7", StockQuarterlyFinancial.SOURCE_DART),
                row("202606", "130", "40", "6", StockQuarterlyFinancial.SOURCE_DART));

        BigDecimal[] ttm = QuarterlyFinancials.ttmSum(QuarterlyFinancials.usableFromStored(rows));

        assertThat(ttm[0]).isEqualByComparingTo("460");
        assertThat(ttm[1]).isEqualByComparingTo("100");
        assertThat(ttm[2]).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("재무상태표 — 가장 최근 보고서의 값, 묵었거나 자본이 없으면 모른다")
    void latestBalance() {
        DartControllingFinancial oldReport = DartControllingFinancial.builder().bsnsYear(2025).reprtCode("11011")
                .incomeParsed(true).totalEquity(d("100")).totalAssets(d("150")).totalLiabilities(d("50"))
                .filedOn(LocalDate.of(2026, 3, 31)).build();
        DartControllingFinancial latest = DartControllingFinancial.builder().bsnsYear(2026).reprtCode("11012")
                .incomeParsed(true).totalEquity(d("120")).totalAssets(d("180")).totalLiabilities(d("60"))
                .filedOn(LocalDate.of(2026, 8, 14)).build();

        DartQuarterly.Balance balance = DartQuarterly.latestBalance(List.of(oldReport, latest), TODAY);

        assertThat(balance.totalEquity()).isEqualByComparingTo("120");
        assertThat(balance.debtRatio()).isEqualByComparingTo("50.00");
        assertThat(DartQuarterly.latestBalance(List.of(oldReport), TODAY)).isNull();   // 219일 전 접수
        assertThat(new DartQuarterly.Balance(d("-10"), d("90"), d("100")).debtRatio()).isNull();
    }
}
