package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 재무비율 API 필드 매핑 — {@code StockFinancialDataCollector.getFinancialRatios}(2026-09-30).
 *
 * <p>KIS 공식 샘플({@code koreainvestment/open-trading-api}
 * {@code examples_llm/domestic_stock/finance_financial_ratio/chk_finance_financial_ratio.py} 의 COLUMN_MAPPING)에서
 * {@code bsop_prfi_inrt} 는 '영업 이익 <b>증가율</b>', {@code ntin_inrt} 는 '순이익 <b>증가율</b>'이다. 수집기는 이 둘을
 * 영업이익률·순이익률로 담았다. TTM 손익이 있으면 영업이익률은 뒤에서 TTM 값으로 덮였지만, <b>TTM 이 없는 종목은
 * 증가율이 영업이익률로 저장</b>됐다 — 2026-09-29 운영 108행, 마법의 공식 후보 49종목. 진양제약 34.86·기도산업 81.65·
 * 대성창투 398.81 은 상반기 누적 영업이익의 전년 대비 증가율과 맞고 실제 영업이익률은 약 11%·4.6%·65% 다.
 * 두 종목은 AI 스윙 상위였다. 순이익 증가율은 '연간 순이익률'로 ROE 를 보정하는 데도 쓰였다.
 */
class FinancialRatioFieldMappingTest {

    private RestTemplate restTemplate;
    private StockFinancialDataCollector collector;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        collector = new StockFinancialDataCollector(mock(StockFinancialDataRepository.class),
                mock(StockQuarterlyFinancialRepository.class), mock(KoreaInvestmentService.class),
                restTemplate, new ObjectMapper(), mock(StockMasterService.class),
                mock(com.myplatform.backend.dartfinancial.DartControllingFinancialRepository.class),
                mock(com.myplatform.backend.dartfinancial.DartCompanyRepository.class));
    }

    private void respond(String path, String body) {
        when(restTemplate.exchange(contains(path), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    /** 기도산업 실측 모양 — 재무비율 API 가 준 값 그대로(증가율 81.65 · 순이익 증가율 41.77). */
    private static final String RATIO_BODY = "{\"rt_cd\":\"0\",\"output\":[{\"stac_yymm\":\"202606\",\"grs\":\"5.10\","
            + "\"bsop_prfi_inrt\":\"81.65\",\"ntin_inrt\":\"41.77\",\"roe_val\":\"22.81\",\"eps\":\"0\",\"sps\":\"0\","
            + "\"bps\":\"0\",\"rsrv_rate\":\"0\",\"lblt_rate\":\"50.10\"}]}";
    private static final String BALANCE_FAIL = "{\"rt_cd\":\"1\",\"msg1\":\"no data\"}";

    @Test
    @DisplayName("TTM 손익이 없으면 영업이익률은 모른다(null) — 영업이익 증가율 81.65 를 영업이익률로 담지 않는다")
    void growthRateIsNotStoredAsMargin() {
        respond("financial-ratio", RATIO_BODY);
        respond("income-statement", "{\"rt_cd\":\"0\",\"output\":[]}");
        respond("balance-sheet", BALANCE_FAIL);

        Map<String, BigDecimal> ratios = collector.getFinancialRatios("token", "282620");

        assertThat(ratios.get("operatingMargin")).as("bsop_prfi_inrt = 영업이익 증가율").isNull();
        assertThat(ratios.get("netMargin")).as("ntin_inrt = 순이익 증가율").isNull();
        assertThat(ratios.get("roe")).isEqualByComparingTo("22.81");
        assertThat(ratios.get("debtRatio")).isEqualByComparingTo("50.10");
    }

    @Test
    @DisplayName("TTM 손익이 있으면 영업이익률은 TTM 영업이익÷매출, ROE 는 순이익 증가율로 '보정'하지 않는다")
    void ttmMarginAndNoRoeCorrectionFromGrowth() {
        respond("financial-ratio", RATIO_BODY);
        // 개별 분기 5개 — 매출 700·영업이익 70·순이익 50 (누적 아님) → TTM 2,800·280·200, 영업이익률 10.00
        StringBuilder q = new StringBuilder("{\"rt_cd\":\"0\",\"output\":[");
        String[] periods = {"202606", "202603", "202512", "202509", "202506"};
        for (int i = 0; i < periods.length; i++) {
            if (i > 0) q.append(',');
            q.append("{\"stac_yymm\":\"").append(periods[i])
                    .append("\",\"sale_account\":\"700\",\"bsop_prti\":\"70\",\"thtr_ntin\":\"50\"}");
        }
        respond("income-statement", q.append("]}").toString());
        respond("balance-sheet", BALANCE_FAIL);

        Map<String, BigDecimal> ratios = collector.getFinancialRatios("token", "282620");

        assertThat(ratios.get("operatingMargin")).isEqualByComparingTo("10.00");
        assertThat(ratios.get("netIncome")).isEqualByComparingTo("200");
        assertThat(ratios.get("roe"))
                .as("고치기 전: 22.81 × TTM 순이익률 7.14 ÷ '연간 순이익률'(실은 순이익 증가율 41.77) = 3.90")
                .isEqualByComparingTo("22.81");
    }

    /** 손익계산서 응답 — 최신순. revenue 가 null 이면 sale_account 를 빈 문자열로(KIS 가 공란을 주는 모양). */
    private static String incomeBody(String[] periods, String[] revenue, String[] op, String[] net) {
        StringBuilder q = new StringBuilder("{\"rt_cd\":\"0\",\"output\":[");
        for (int i = 0; i < periods.length; i++) {
            if (i > 0) q.append(',');
            q.append("{\"stac_yymm\":\"").append(periods[i])
                    .append("\",\"sale_account\":\"").append(revenue[i] == null ? "" : revenue[i])
                    .append("\",\"bsop_prti\":\"").append(op[i] == null ? "" : op[i])
                    .append("\",\"thtr_ntin\":\"").append(net[i] == null ? "" : net[i]).append("\"}");
        }
        return q.append("]}").toString();
    }

    @Test
    @DisplayName("재현(2026-10-19): 매출이 비어 누적 여부를 판정 못 하면 TTM 을 만들지 않는다 — 누적값 4개를 더한 영업이익 1,000(실제 400)을 쓰지 않는다")
    void undecidedCumulativeMakesNoTtm() {
        respond("financial-ratio", RATIO_BODY);
        respond("income-statement", incomeBody(new String[]{"202512", "202509", "202506", "202503"},
                new String[]{null, null, null, null},
                new String[]{"400", "300", "200", "100"}, new String[]{"330", "250", "160", "80"}));
        respond("balance-sheet", BALANCE_FAIL);

        Map<String, BigDecimal> ratios = collector.getFinancialRatios("token", "282620");

        assertThat(ratios.get("operatingProfit")).as("고치기 전: 100+200+300+400 = 1,000").isNull();
        assertThat(ratios.get("netIncome")).isNull();
    }

    @Test
    @DisplayName("재현(2026-10-19): 영업이익 TTM 을 모르면(한 분기 공란) 영업이익률도 모른다 — 매출만 있다고 0.00% 를 만들지 않는다")
    void unknownOperatingProfitIsNotZeroMargin() {
        respond("financial-ratio", RATIO_BODY);
        // 개별 분기 5개(매출 700·순이익 50) — 202603 분기의 영업이익만 공란
        respond("income-statement", incomeBody(new String[]{"202606", "202603", "202512", "202509", "202506"},
                new String[]{"700", "700", "700", "700", "700"},
                new String[]{"70", null, "70", "70", "70"}, new String[]{"50", "50", "50", "50", "50"}));
        respond("balance-sheet", BALANCE_FAIL);

        Map<String, BigDecimal> ratios = collector.getFinancialRatios("token", "282620");

        assertThat(ratios.get("revenue")).isEqualByComparingTo("2800");
        assertThat(ratios.get("operatingProfit")).isNull();
        assertThat(ratios.get("operatingMargin")).as("고치기 전: 모르는 영업이익을 0 으로 나눠 0.00").isNull();
        assertThat(ratios.get("netMargin")).as("순이익은 아니까 순이익률은 그대로").isEqualByComparingTo("7.14");
    }

    /** 재무상태표 응답 — 분기(FID_DIV_CLS_CODE=1)·연간(0)을 따로. */
    private void respondBalance(String quarterlyBody, String annualBody) {
        when(restTemplate.exchange(contains("balance-sheet?FID_DIV_CLS_CODE=1"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(quarterlyBody));
        when(restTemplate.exchange(contains("balance-sheet?FID_DIV_CLS_CODE=0"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(annualBody));
    }

    @Test
    @DisplayName("재현(2026-10-19): 최신 분기 자본총계가 음수(자본잠식)면 그 값을 쓴다 — 작년 연간 자본(+300억)으로 대체하지 않는다")
    void capitalImpairmentIsNotReplacedByAnnualEquity() {
        respond("financial-ratio", RATIO_BODY);
        respond("income-statement", "{\"rt_cd\":\"0\",\"output\":[]}");
        respondBalance("{\"rt_cd\":\"0\",\"output\":[{\"total_aset\":\"500\",\"total_cptl\":\"-50\",\"total_lblt\":\"550\"}]}",
                "{\"rt_cd\":\"0\",\"output\":[{\"total_aset\":\"800\",\"total_cptl\":\"300\",\"total_lblt\":\"500\"}]}");

        Map<String, BigDecimal> ratios = collector.getFinancialRatios("token", "282620");

        assertThat(ratios.get("totalEquity")).as("고치기 전: 연간 자본 300 으로 대체").isEqualByComparingTo("-50");
        assertThat(ratios.get("debtRatio")).as("자본잠식이면 부채비율은 의미가 없다 — 연간 자본으로 만든 166.67% 도, 재무비율 API 값도 쓰지 않는다")
                .isNull();
    }

    @Test
    @DisplayName("자본총계 0·공란(파싱 실패)은 종전대로 결측 — 연간으로 넘어간다")
    void zeroEquityStillFallsBackToAnnual() {
        respond("financial-ratio", RATIO_BODY);
        respond("income-statement", "{\"rt_cd\":\"0\",\"output\":[]}");
        respondBalance("{\"rt_cd\":\"0\",\"output\":[{\"total_aset\":\"\",\"total_cptl\":\"\",\"total_lblt\":\"\"}]}",
                "{\"rt_cd\":\"0\",\"output\":[{\"total_aset\":\"800\",\"total_cptl\":\"300\",\"total_lblt\":\"500\"}]}");

        Map<String, BigDecimal> ratios = collector.getFinancialRatios("token", "282620");

        assertThat(ratios.get("totalEquity")).isEqualByComparingTo("300");
        assertThat(ratios.get("debtRatio")).isEqualByComparingTo("166.67");
    }
}
