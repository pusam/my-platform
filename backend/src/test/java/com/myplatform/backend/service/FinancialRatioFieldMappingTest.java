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
                mock(com.myplatform.backend.dartfinancial.DartControllingFinancialRepository.class));
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
}
