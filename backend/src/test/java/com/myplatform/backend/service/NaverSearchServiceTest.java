package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 네이버 검색 URL 인코딩 회귀 테스트.
 *
 * <p>버그(2026-07-01): 한글 쿼리가 <b>이중 인코딩</b>(% → %25)돼 네이버가 무관 뉴스를 반환
 * → 종목명 필터 전부 탈락 → 재료 100% NONE. 이중 인코딩의 최종 원인은 RestTemplate.exchange 가
 * <b>String</b> URL 을 URI 템플릿으로 보고 재인코딩한 것 → buildSearchUrl 은 <b>URI</b> 를 반환해
 * RestTemplate 재인코딩을 차단하고, 그 URI 자체는 UTF-8 단일 인코딩이어야 한다.
 */
class NaverSearchServiceTest {

    @Test
    @DisplayName("검색 URI — 한글 쿼리 단일 인코딩(삼성전자=%EC%82%BC…), 이중 인코딩(%25) 없음")
    void buildSearchUrl_singleEncodesKorean() {
        URI url = NaverSearchService.buildSearchUrl("삼성전자");
        String s = url.toString();

        // 반환 타입이 URI 여야 RestTemplate 이 재인코딩하지 않는다(회귀 방지의 핵심)
        assertThat(url).isInstanceOf(URI.class);
        // '삼' = UTF-8 EC 82 BC → 단일 인코딩 흔적
        assertThat(s).contains("query=%EC%82%BC");
        // 이중 인코딩(%25)이 있으면 네이버가 한글을 못 알아듣는다 — 절대 없어야 함
        assertThat(s).doesNotContain("%25");
        // 고정 파라미터 유지
        assertThat(s).contains("sort=date");
        assertThat(s).contains("display=30");
    }

    @Test
    @DisplayName("검색 URI — 다른 한글 종목명도 %25 없이 단일 인코딩")
    void buildSearchUrl_noDoubleEncodingForOtherNames() {
        assertThat(NaverSearchService.buildSearchUrl("효성중공업").toString()).doesNotContain("%25");
        assertThat(NaverSearchService.buildSearchUrl("대한광통신").toString()).doesNotContain("%25");
    }
}
