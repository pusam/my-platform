package com.myplatform.backend.service;

import com.myplatform.backend.service.QuarterlyFinancials.Figures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KIS 분기 손익의 빈 행·묵은 4분기(2026-11-04, 네이버 대조로 발견).
 *
 * <p>KIS 분기 손익은 사실상 연결재무제표 회사만 이어서 준다(11/4 전 종목: 연결 2,095개 중 2,083개 최신 vs 별도 488개 중
 * 11개). 그 밖의 회사는 ① 자료가 없는 분기를 매출·영업이익·순이익 전부 <b>0</b> 으로 주고 ② 어느 시점부터 연간 행만 준다.
 * 그런데 0 행을 실적 0 으로 읽어 더했고, TTM 에 나이 제한이 없어 끊긴 지점의 4분기가 오늘 값으로 저장됐다.
 */
class QuarterlyBlankReportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final int MAX_AGE = EarningSurpriseService.QUARTER_MAX_AGE_DAYS;

    private static BigDecimal bd(String v) { return v == null ? null : new BigDecimal(v); }

    /** "기간,매출,영업이익,순이익" 누적 행. */
    private static List<Figures> cumulative(String... rows) {
        List<Figures> out = new ArrayList<>();
        for (String r : rows) {
            String[] c = r.split(",");
            YearMonth ym = QuarterlyFinancials.parseFiscalPeriod(c[0]);
            out.add(new Figures(c[0], QuarterlyFinancials.periodEnd(ym), bd(c[1]), bd(c[2]), bd(c[3]), true));
        }
        return out;
    }

    private static List<String> periods(List<Figures> rows) {
        return rows.stream().map(Figures::fiscalPeriod).toList();
    }

    /** 두산테스나 131970 운영 원본 — 2024-12 까지만 실제 값, 그 뒤 세 분기는 KIS 가 0 으로 준다. */
    private static final String[] DOOSAN_TESNA = {
            "202509,0,0,0", "202506,0,0,0", "202503,0,0,0",
            "202412,3731,379,368", "202409,2903,391,364", "202406,1916,280,304", "202403,918,112,69", "202312,3387,608,491"};

    @Test
    @DisplayName("두산테스나 — 0 으로 채운 세 분기를 실적으로 더하지 않는다(저장값 828·−12·4 = 2024년 4분기 한 분기 + 0·0·0)")
    void blankQuartersAreNotReportedZeros() {
        List<Figures> usable = QuarterlyFinancials.usableIndividualQuarters(cumulative(DOOSAN_TESNA));

        assertThat(periods(usable)).as("KIS 가 자료가 없을 때 주는 0 행은 분기가 아니다")
                .doesNotContain("202503", "202506", "202509");
        BigDecimal[] ttm = QuarterlyFinancials.ttmSum(usable);
        assertThat(ttm).as("남는 4분기는 2024년 연간 — 828 이 아니다").isNotNull();
        assertThat(ttm[0]).isEqualByComparingTo("3731");
    }

    @Test
    @DisplayName("그 4분기는 2년 묵었다 — 최근 4분기로 쓰지 않는다")
    void staleQuartersAreNotRecent() {
        List<Figures> usable = QuarterlyFinancials.usableIndividualQuarters(cumulative(DOOSAN_TESNA));

        assertThat(QuarterlyFinancials.isStale(usable, TODAY, MAX_AGE)).isTrue();
    }

    @Test
    @DisplayName("한화엔진 — 연간 값이 '4분기 한 분기'로 둔갑하지 않는다(저장값 20,786 = 0 + 13,711 + 3,452 + 3,623, 실제의 약 1.5배)")
    void annualFigureDoesNotBecomeFourthQuarter() {
        List<Figures> rows = QuarterlyFinancials.withDetectedCumulative(cumulative(
                "202606,7075,893,861", "202603,3452,514,529", "202512,13711,1301,1738",
                "202509,0,0,0", "202506,0,0,0", "202503,0,0,0", "202412,0,0,0", "202409,0,0,0", "202406,0,0,0", "202403,0,0,0",
                "202312,8544,87,-4", "202306,3884,75,60", "202303,1993,41,57", "202212,7642,-295,-403"));

        List<Figures> usable = QuarterlyFinancials.usableIndividualQuarters(rows);

        assertThat(periods(usable)).as("직전 분기를 모르면 누적을 개별로 환산할 수 없다").doesNotContain("202512");
        assertThat(QuarterlyFinancials.ttmSum(usable)).as("연속 4분기가 없다 — 모른다").isNull();
    }

    @Test
    @DisplayName("세 값 중 하나라도 있으면 빈 행이 아니다 — 매출 없는 바이오의 적자 분기는 실제 실적이다")
    void partialZeroIsAReport() {
        Figures biotech = new Figures("202606", LocalDate.of(2026, 6, 30), BigDecimal.ZERO, bd("-120"), bd("-110"), false);
        Figures blank = new Figures("202606", LocalDate.of(2026, 6, 30), BigDecimal.ZERO, BigDecimal.ZERO, null, false);

        assertThat(QuarterlyFinancials.isBlankReport(biotech)).isFalse();
        assertThat(QuarterlyFinancials.isBlankReport(blank)).isTrue();
    }

    @Test
    @DisplayName("나이 경계 — 200일째까지는 최근, 201일째부터 묵음. 분기가 없으면 판단하지 않는다")
    void ageBoundary() {
        List<Figures> latest202606 = cumulative("202606,100,10,10");
        LocalDate end = LocalDate.of(2026, 6, 30);

        assertThat(QuarterlyFinancials.isStale(latest202606, end.plusDays(MAX_AGE), MAX_AGE)).isFalse();
        assertThat(QuarterlyFinancials.isStale(latest202606, end.plusDays(MAX_AGE + 1L), MAX_AGE)).isTrue();
        assertThat(QuarterlyFinancials.isStale(List.of(), TODAY, MAX_AGE)).isFalse();
    }

    @Test
    @DisplayName("빈 행이 없는 정상 종목은 그대로 — 삼성전자형 누적 4분기")
    void normalStockIsUntouched() {
        List<Figures> rows = QuarterlyFinancials.withDetectedCumulative(cumulative(
                "202606,1000,100,80", "202603,500,50,40", "202512,2000,200,160", "202509,1500,150,120",
                "202506,1000,100,80", "202503,500,50,40", "202412,2000,200,160"));

        List<Figures> usable = QuarterlyFinancials.usableIndividualQuarters(rows);

        assertThat(QuarterlyFinancials.ttmSum(usable)[0]).isEqualByComparingTo("2000");
        assertThat(QuarterlyFinancials.isStale(usable, TODAY, MAX_AGE)).isFalse();
    }
}
