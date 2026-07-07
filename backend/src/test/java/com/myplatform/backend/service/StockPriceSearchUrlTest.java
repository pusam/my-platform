package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종목 검색 네이버 폴백 URL 인코딩 회귀 테스트 (AUDIT 2026-07-07 P1-2).
 *
 * <p>버그: {@code searchFromNaver} 가 URLEncoder + String.format 후 <b>String</b> 을
 * RestTemplate.exchange 에 전달 → 재인코딩(% → %25) → 한글 키워드 폴백 오작동
 * (2026-07-01 네이버 재료 사건과 동형). buildNaverSearchUrl 은 UTF-8 단일 인코딩
 * <b>URI</b> 를 반환해야 한다.
 */
class StockPriceSearchUrlTest {

    @Test
    @DisplayName("네이버 폴백 검색 URI — 한글 키워드 단일 인코딩(삼성전자=%EC%82%BC…), 이중 인코딩(%25) 없음")
    void buildNaverSearchUrl_singleEncodesKorean() {
        URI url = StockPriceService.buildNaverSearchUrl("삼성전자");
        String s = url.toString();

        // 반환 타입이 URI 여야 RestTemplate 이 재인코딩하지 않는다(회귀 방지의 핵심)
        assertThat(url).isInstanceOf(URI.class);
        assertThat(s).contains("query=%EC%82%BC");
        assertThat(s).doesNotContain("%25");
        // 고정 파라미터 유지
        assertThat(s).contains("target=stock");
    }

    @Test
    @DisplayName("네이버 폴백 검색 URI — 다른 한글 키워드도 %25 없이 단일 인코딩")
    void buildNaverSearchUrl_noDoubleEncodingForOtherKeywords() {
        assertThat(StockPriceService.buildNaverSearchUrl("효성중공업").toString()).doesNotContain("%25");
        assertThat(StockPriceService.buildNaverSearchUrl("대한광통신").toString()).doesNotContain("%25");
    }

    @Test
    @DisplayName("네이버 폴백 검색 URI — 영문/숫자 키워드는 원문 유지")
    void buildNaverSearchUrl_asciiKeywordUnchanged() {
        String s = StockPriceService.buildNaverSearchUrl("005930").toString();
        assertThat(s).contains("query=005930");
        assertThat(s).doesNotContain("%25");
    }
}
