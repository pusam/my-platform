package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Google News RSS URL 인코딩 회귀 테스트 (AUDIT 2026-07-07 P1-1).
 *
 * <p>버그: {@code URLEncoder.encode(stockName + " 주식")} 후 <b>String</b> 을
 * RestTemplate.exchange 에 전달 → URI 템플릿으로 보고 재인코딩(% → %25) → 이중 인코딩.
 * {@code " 주식"} 상수가 항상 붙어 <b>매 호출</b> 이중 인코딩이었다(2026-07-01 네이버 사건과 동형).
 * buildSearchUrl 은 UTF-8 단일 인코딩 <b>URI</b> 를 반환해야 한다.
 */
class GoogleNewsServiceTest {

    @Test
    @DisplayName("검색 URI — 한글 단일 인코딩(삼성전자=%EC%82%BC…, 주식=%EC%A3%BC%EC%8B%9D), 이중 인코딩(%25) 없음")
    void buildSearchUrl_singleEncodesKorean() {
        URI url = GoogleNewsService.buildSearchUrl("삼성전자");
        String s = url.toString();

        // 반환 타입이 URI 여야 RestTemplate 이 재인코딩하지 않는다(회귀 방지의 핵심)
        assertThat(url).isInstanceOf(URI.class);
        // '삼' = UTF-8 EC 82 BC → 단일 인코딩 흔적
        assertThat(s).contains("q=%EC%82%BC");
        // 상수 접미 " 주식" 도 단일 인코딩
        assertThat(s).contains("%EC%A3%BC%EC%8B%9D");
        // 이중 인코딩(%25)이 있으면 쿼리가 %25EC… 로 나간다 — 절대 없어야 함
        assertThat(s).doesNotContain("%25");
        // 고정 파라미터 유지
        assertThat(s).contains("hl=ko");
        assertThat(s).contains("gl=KR");
        assertThat(s).contains("ceid=KR");
    }

    @Test
    @DisplayName("검색 URI — 다른 한글 종목명도 %25 없이 단일 인코딩")
    void buildSearchUrl_noDoubleEncodingForOtherNames() {
        assertThat(GoogleNewsService.buildSearchUrl("효성중공업").toString()).doesNotContain("%25");
        assertThat(GoogleNewsService.buildSearchUrl("대한광통신").toString()).doesNotContain("%25");
    }

    // 2026-10-07 화면 점검(에코프로 상세 '관련 뉴스'): 요약에 '…약보합 마감&nbsp;&nbsp;비즈니스포스트'가 그대로 찍혔다 —
    // &nbsp; 를 풀지 않았고, Google News 의 요약은 '제목 + 언론사'라 제목을 한 번 더 보일 뿐이었다
    @Test
    @DisplayName("재현: 제목을 되풀이하는 요약은 비우고(&nbsp; 포함), 진짜 요약은 엔터티를 푼다")
    void cleansDescription() {
        String title = "에코프로 사장단 인사 2개월 앞당겨…최상운·박종환 사장 승진 - 디지털투데이";
        String raw = "<a href=\"https://news.google.com/x\">에코프로 사장단 인사 2개월 앞당겨…최상운·박종환 사장 승진</a>"
                + "&nbsp;&nbsp;<font color=\"#6f6f6f\">디지털투데이</font>";
        assertThat(GoogleNewsService.cleanDescription(raw, title)).isEmpty();

        String real = "<p>2차전지&nbsp;소재 수요가&#160;늘며 &amp; 실적이 개선됐다</p>";
        assertThat(GoogleNewsService.cleanDescription(real, "다른 제목 - 매체")).isEqualTo("2차전지 소재 수요가 늘며 & 실적이 개선됐다");
        assertThat(GoogleNewsService.cleanDescription(null, title)).isEmpty();
        // 줄바꿈·탭도 공백 하나로(정규식이 '공백 여러 개'만 잡으면 안 된다)
        assertThat(GoogleNewsService.cleanDescription("수주\n\t확대", "다른 제목 - 매체")).isEqualTo("수주 확대");
    }
}

