package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주식당일분봉조회(FHKST03010200, inquire-time-itemchartprice) 요청 URL 순수 함수 — buildMinuteChartUrl.
 *
 * KIS 공식 샘플(koreainvestment/open-trading-api, examples_llm/domestic_stock/inquire_time_itemchartprice)은
 * 파라미터 5개를 보낸다: FID_ETC_CLS_CODE("") · FID_COND_MRKT_DIV_CODE · FID_INPUT_ISCD · FID_INPUT_HOUR_1 ·
 * FID_PW_DATA_INCU_YN. FID_ETC_CLS_CODE 가 빠지면 KIS 는 HTTP 200 + rt_cd≠0 으로
 * "ERROR INPUT FIELD NOT FOUND [FID_ETC_CLS_CODE]" 를 돌려주고(2026-09-01 prod 실측), VWAP 와
 * 종목상세 '1일' 탭(IntradayChartService — 같은 메서드)이 조용히 죽는다. 이 테스트가 그 회귀를 고정한다.
 */
class KoreaInvestmentMinuteChartUrlTest {

    private static final String BASE = "https://openapi.koreainvestment.com:9443";

    @Test
    @DisplayName("공식 샘플 파라미터 5개 전부 포함 — 특히 FID_ETC_CLS_CODE(빈값)")
    void containsAllFiveOfficialParams() {
        String url = KoreaInvestmentService.buildMinuteChartUrl(BASE, "005930", "093000");

        assertThat(url).startsWith(BASE + "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice?");
        assertThat(url).contains("FID_COND_MRKT_DIV_CODE=UN");   // KRX+NXT 통합(ca14792) — 되돌리지 말 것
        assertThat(url).contains("FID_INPUT_ISCD=005930");
        assertThat(url).contains("FID_INPUT_HOUR_1=093000");
        assertThat(url).contains("FID_PW_DATA_INCU_YN=Y");
        assertThat(url).contains("FID_ETC_CLS_CODE=");            // 필수(빈값) — 없으면 INPUT FIELD NOT FOUND
    }

    @Test
    @DisplayName("FID_ETC_CLS_CODE 는 빈값이어야 한다 — 다른 값을 붙이지 않는다")
    void etcClsCodeIsEmpty() {
        String url = KoreaInvestmentService.buildMinuteChartUrl(BASE, "000660", "150000");

        // "FID_ETC_CLS_CODE=" 뒤에 다른 파라미터가 바로 오거나 문자열이 끝나야 한다(값 없음).
        int idx = url.indexOf("FID_ETC_CLS_CODE=");
        assertThat(idx).isGreaterThan(0);
        String after = url.substring(idx + "FID_ETC_CLS_CODE=".length());
        assertThat(after.isEmpty() || after.startsWith("&")).isTrue();
    }
}
