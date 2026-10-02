package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KIS 공매도 API 두 개의 요청 URL 순수 함수(2026-10-02).
 *
 * <p>KIS 는 틀린 요청에도 HTTP 200 + rt_cd≠0 을 준다(분봉 FID_ETC_CLS_CODE 누락·재무 tr_id 어긋남 선례) —
 * 그래서 파라미터 목록을 공식 샘플(koreainvestment/open-trading-api) 그대로 고정한다.
 * <ul>
 *   <li>공매도 상위종목[국내주식-133] {@code examples_llm/domestic_stock/short_sale/short_sale.py} — 파라미터 10개</li>
 *   <li>공매도 일별추이[국내주식-134] {@code examples_llm/domestic_stock/daily_short_sale/daily_short_sale.py} — 4개</li>
 * </ul>
 */
class KoreaInvestmentShortSaleUrlTest {

    private static final String BASE = "https://openapi.koreainvestment.com:9443";

    private static List<String> paramNames(String url) {
        String query = url.substring(url.indexOf('?') + 1);
        return Arrays.stream(query.split("&")).map(p -> p.substring(0, p.indexOf('='))).toList();
    }

    @Test
    @DisplayName("상위종목: 경로·공식 파라미터 10개 그대로 — 전체 시장(0000)·일(D)·1일(0)·화면키 20482")
    void rankingUrlHasTheTenOfficialParams() {
        String url = KoreaInvestmentService.buildShortSaleRankingUrl(BASE);

        assertThat(url).startsWith(BASE + "/uapi/domestic-stock/v1/ranking/short-sale?");
        assertThat(paramNames(url)).containsExactlyInAnyOrder(
                "FID_APLY_RANG_VOL", "FID_COND_MRKT_DIV_CODE", "FID_COND_SCR_DIV_CODE", "FID_INPUT_ISCD",
                "FID_PERIOD_DIV_CODE", "FID_INPUT_CNT_1", "FID_TRGT_EXLS_CLS_CODE", "FID_TRGT_CLS_CODE",
                "FID_APLY_RANG_PRC_1", "FID_APLY_RANG_PRC_2");
        assertThat(url).contains("FID_COND_MRKT_DIV_CODE=J");
        assertThat(url).contains("FID_COND_SCR_DIV_CODE=20482");
        assertThat(url).contains("FID_INPUT_ISCD=0000");
        assertThat(url).contains("FID_PERIOD_DIV_CODE=D");
        assertThat(url).contains("FID_INPUT_CNT_1=0");
    }

    @Test
    @DisplayName("상위종목: 가격 범위로 종목을 거르지 않는다 — 샘플 시험값(0~1,000,000)을 쓰면 100만원 넘는 종목이 조용히 빠진다")
    void rankingUrlDoesNotCapPrice() {
        String url = KoreaInvestmentService.buildShortSaleRankingUrl(BASE);

        assertThat(url).doesNotContain("FID_APLY_RANG_PRC_2=1000000");
        assertThat(url).contains("FID_APLY_RANG_PRC_1=&").contains("FID_APLY_RANG_PRC_2=");
    }

    @Test
    @DisplayName("일별추이: 경로·공식 파라미터 4개 — 날짜는 yyyyMMdd")
    void dailyUrlHasTheFourOfficialParams() {
        String url = KoreaInvestmentService.buildDailyShortSaleUrl(BASE, "005930",
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 10, 1));

        assertThat(url).startsWith(BASE + "/uapi/domestic-stock/v1/quotations/daily-short-sale?");
        assertThat(paramNames(url)).containsExactlyInAnyOrder(
                "FID_COND_MRKT_DIV_CODE", "FID_INPUT_ISCD", "FID_INPUT_DATE_1", "FID_INPUT_DATE_2");
        assertThat(url).contains("FID_COND_MRKT_DIV_CODE=J");
        assertThat(url).contains("FID_INPUT_ISCD=005930");
        assertThat(url).contains("FID_INPUT_DATE_1=20260911");
        assertThat(url).contains("FID_INPUT_DATE_2=20261001");
    }
}
