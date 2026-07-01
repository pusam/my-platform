package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 네이버 검색 URL 인코딩 회귀 테스트.
 *
 * <p>버그(2026-07-01): URLEncoder 수동 인코딩 + UriComponentsBuilder.build(false) 조합이
 * 한글 쿼리를 <b>이중 인코딩</b>(% → %25)해 네이버가 무관 뉴스를 반환 → 종목명 필터 전부 탈락
 * → 재료 100% NONE. buildSearchUrl 은 UTF-8 단일 인코딩이어야 한다.
 */
class NaverSearchServiceTest {

    @Test
    @DisplayName("검색 URL — 한글 쿼리 단일 인코딩(삼성전자=%EC%82%BC…), 이중 인코딩(%25) 없음")
    void buildSearchUrl_singleEncodesKorean() {
        String url = NaverSearchService.buildSearchUrl("삼성전자");

        // '삼' = UTF-8 EC 82 BC → 단일 인코딩 흔적
        assertThat(url).contains("query=%EC%82%BC");
        // 이중 인코딩(%25)이 있으면 네이버가 한글을 못 알아듣는다 — 절대 없어야 함
        assertThat(url).doesNotContain("%25");
        // 고정 파라미터 유지
        assertThat(url).contains("sort=date");
        assertThat(url).contains("display=30");
    }

    @Test
    @DisplayName("검색 URL — 다른 한글 종목명도 %25 없이 단일 인코딩")
    void buildSearchUrl_noDoubleEncodingForOtherNames() {
        assertThat(NaverSearchService.buildSearchUrl("효성중공업")).doesNotContain("%25");
        assertThat(NaverSearchService.buildSearchUrl("대한광통신")).doesNotContain("%25");
    }
}
