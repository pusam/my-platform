package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dartfinancial.DartCompanyRepository;
import com.myplatform.backend.dartfinancial.DartControllingFinancialRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 묵은 KIS 재무 자료를 오늘 값으로 쓰지 않는다 — {@code StockFinancialDataCollector.getFinancialRatios}(2026-11-04).
 *
 * <p>별도재무제표 회사의 KIS 분기 자료는 어느 시점에서 끊긴다. TTM 에 나이 제한이 없어 끊긴 지점의 4분기가 오늘 값으로
 * 저장됐다 — 네이버와 대조한 운영 저장값: 파미셀 순이익 −267억(2014-06 까지의 4분기, 실제 최근 4분기 +466억) · HK이노엔
 * 매출 8,465억(2022년 연간, 실제 1조 786억) · 두산테스나 매출 828억(2024년 4분기 한 분기 + 0 세 분기, 실제 3,264억).
 * 재무상태표도 첫 행을 나이 확인 없이 썼다(별도 회사 353곳 중 자본총계가 DART 와 ±3% 안인 곳은 77곳뿐).
 */
class FinancialStaleReportTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private RestTemplate restTemplate;
    private StockFinancialDataCollector collector;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        collector = new StockFinancialDataCollector(mock(StockFinancialDataRepository.class),
                mock(StockQuarterlyFinancialRepository.class), mock(KoreaInvestmentService.class),
                restTemplate, new ObjectMapper(), mock(StockMasterService.class),
                mock(DartControllingFinancialRepository.class), mock(DartCompanyRepository.class));
        collector.clock = Clock.fixed(LocalDate.of(2026, 11, 4).atTime(15, 38).atZone(KST).toInstant(), KST);
        respond("financial-ratio", ratioBody("202512", "12.50", "60.00"));
        respond("balance-sheet", "{\"rt_cd\":\"1\",\"msg1\":\"no data\"}");
        respond("income-statement", "{\"rt_cd\":\"0\",\"output\":[]}");
    }

    private void respond(String path, String body) {
        when(restTemplate.exchange(contains(path), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    /** "기간,매출,영업이익,순이익" — KIS 처럼 최신순. */
    private static String incomeBody(String... rows) {
        StringBuilder q = new StringBuilder("{\"rt_cd\":\"0\",\"output\":[");
        for (int i = 0; i < rows.length; i++) {
            String[] c = rows[i].split(",");
            if (i > 0) q.append(',');
            q.append("{\"stac_yymm\":\"").append(c[0]).append("\",\"sale_account\":\"").append(c[1])
                    .append("\",\"bsop_prti\":\"").append(c[2]).append("\",\"thtr_ntin\":\"").append(c[3]).append("\"}");
        }
        return q.append("]}").toString();
    }

    private static String balanceBody(String period, String assets, String equity, String debt) {
        return "{\"rt_cd\":\"0\",\"output\":[{\"stac_yymm\":\"" + period + "\",\"total_aset\":\"" + assets
                + "\",\"total_cptl\":\"" + equity + "\",\"total_lblt\":\"" + debt + "\"}]}";
    }

    private static String ratioBody(String period, String roe, String debtRatio) {
        return "{\"rt_cd\":\"0\",\"output\":[{\"stac_yymm\":\"" + period + "\",\"roe_val\":\"" + roe
                + "\",\"lblt_rate\":\"" + debtRatio + "\"}]}";
    }

    private void assertNoIncome(Map<String, BigDecimal> ratios) {
        assertThat(ratios).doesNotContainKeys("revenue", "operatingProfit", "netIncome", "operatingMargin", "netMargin");
    }

    @Test
    @DisplayName("파미셀 — 2014-06 에 끝나는 4분기(순이익 −267억)를 오늘 값으로 저장하지 않는다(실제 최근 4분기 +466억)")
    void twelveYearOldQuartersAreNotToday() {
        respond("income-statement", incomeBody(
                "202412,649,47,63", "202312,562,13,36", "202212,602,89,83", "202112,518,79,95", "202012,378,72,52",
                "201912,325,20,43", "201812,284,3,-127", "201712,252,-46,-220", "201612,278,-19,-7", "201512,252,-55,-64",
                "201412,202,-84,-288", "201406,94,-48,-54", "201403,58,-20,-24", "201312,334,-76,-257",
                "201309,294,-24,-26", "201306,189,-35,-44", "201303,102,-11,-15", "201212,111,-140,-176",
                "201209,80,-95,-107", "201206,52,-67,-76", "201203,21,-34,-39"));

        assertNoIncome(collector.getFinancialRatios("token", "005690"));
    }

    @Test
    @DisplayName("HK이노엔 — 2022년 연간(매출 8,465억)을 오늘 값으로 저장하지 않는다(실제 최근 4분기 1조 786억)")
    void fourYearOldQuartersAreNotToday() {
        respond("income-statement", incomeBody(
                "202412,8971,882,616", "202312,8289,659,472", "202212,8465,525,381", "202209,6302,441,358",
                "202206,4321,219,209", "202203,1802,42,74", "202112,7698,503,247", "202109,5604,331,157",
                "202106,3718,160,74", "202103,1868,130,83", "202012,5984,870,279", "202009,4150,429,152",
                "202006,2637,283,33", "202003,1328,133,31"));

        assertNoIncome(collector.getFinancialRatios("token", "195940"));
    }

    @Test
    @DisplayName("두산테스나 — 2024년 4분기 한 분기에 0 세 분기를 더한 매출 828억을 저장하지 않는다")
    void blankQuartersAreNotAddedAsZero() {
        respond("income-statement", incomeBody(
                "202509,0.00,0.00,0.00", "202506,0.00,0.00,0.00", "202503,0.00,0.00,0.00",
                "202412,3731,379,368", "202409,2903,391,364", "202406,1916,280,304", "202403,918,112,69",
                "202312,3387,608,491", "202309,2500,450,380", "202306,1600,290,230", "202303,700,120,90"));

        assertNoIncome(collector.getFinancialRatios("token", "131970"));
    }

    @Test
    @DisplayName("한화엔진 — 연간 값을 4분기 하나로 읽은 매출 2조 786억(실제의 약 1.5배)을 저장하지 않는다")
    void annualReadAsFourthQuarterIsNotStored() {
        respond("income-statement", incomeBody(
                "202606,7075,893,861", "202603,3452,514,529", "202512,13711,1301,1738",
                "202509,0.00,0.00,0.00", "202506,0.00,0.00,0.00", "202503,0.00,0.00,0.00", "202412,0.00,0.00,0.00",
                "202409,0.00,0.00,0.00", "202406,0.00,0.00,0.00", "202403,0.00,0.00,0.00",
                "202312,8544,87,-4", "202306,3884,75,60", "202303,1993,41,57", "202212,7642,-295,-403"));

        assertNoIncome(collector.getFinancialRatios("token", "082740"));
    }

    @Test
    @DisplayName("연속 4분기가 최신이면 종전대로 — 삼성전자형 누적 원본")
    void freshQuartersStillWork() {
        respond("income-statement", incomeBody(
                "202606,1000,100,80", "202603,500,50,40", "202512,2000,200,160", "202509,1500,150,120",
                "202506,1000,100,80", "202503,500,50,40", "202412,2000,200,160", "202409,1500,150,120"));

        Map<String, BigDecimal> ratios = collector.getFinancialRatios("token", "005930");

        assertThat(ratios.get("revenue")).isEqualByComparingTo("2000");
        assertThat(ratios.get("netIncome")).isEqualByComparingTo("160");
        assertThat(ratios.get("operatingMargin")).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("재무상태표 — 분기 행이 묵었으면 연간으로 내려가고, 연간이 최신이면 그 값을 쓴다")
    void staleQuarterlyBalanceFallsBackToFreshAnnual() {
        respond("balance-sheet?FID_DIV_CLS_CODE=1", balanceBody("202412", "300", "100", "200"));
        respond("balance-sheet?FID_DIV_CLS_CODE=0", balanceBody("202512", "400", "200", "200"));

        Map<String, BigDecimal> ratios = collector.getFinancialRatios("token", "000001");

        assertThat(ratios.get("totalEquity")).isEqualByComparingTo("200");
        assertThat(ratios.get("debtRatio")).as("부채총계 ÷ 자본총계").isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("재무상태표 — 분기·연간 둘 다 묵었으면 자본·부채를 모른다(2년 전 자본을 오늘 값으로 쓰지 않는다)")
    void staleBalanceBothWaysIsUnknown() {
        respond("financial-ratio", ratioBody("202412", "12.50", "60.00"));
        respond("balance-sheet?FID_DIV_CLS_CODE=1", balanceBody("202412", "300", "100", "200"));
        respond("balance-sheet?FID_DIV_CLS_CODE=0", balanceBody("202412", "300", "100", "200"));

        Map<String, BigDecimal> ratios = collector.getFinancialRatios("token", "000001");

        assertThat(ratios).doesNotContainKeys("totalEquity", "totalAssets", "totalDebt", "debtRatio");
    }

    @Test
    @DisplayName("재무비율 API 도 같다 — 묵은 결산의 ROE·부채비율을 쓰지 않고, 최신 결산이면 그대로 쓴다")
    void ratioApiFollowsTheSameRule() {
        respond("financial-ratio", ratioBody("202412", "12.50", "60.00"));
        assertThat(collector.getFinancialRatios("token", "000001")).doesNotContainKeys("roe", "debtRatio");

        respond("financial-ratio", ratioBody("202512", "12.50", "60.00"));
        Map<String, BigDecimal> fresh = collector.getFinancialRatios("token", "000001");
        assertThat(fresh.get("roe")).isEqualByComparingTo("12.50");
        assertThat(fresh.get("debtRatio")).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("결산월을 못 읽으면 막지 않는다 — 나이를 모르는 것이지 묵은 것이 아니다")
    void unreadablePeriodIsNotStale() {
        respond("balance-sheet?FID_DIV_CLS_CODE=1",
                "{\"rt_cd\":\"0\",\"output\":[{\"total_aset\":\"300\",\"total_cptl\":\"100\",\"total_lblt\":\"200\"}]}");

        assertThat(collector.getFinancialRatios("token", "000001").get("totalEquity")).isEqualByComparingTo("100");
    }
}
