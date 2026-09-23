package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 크롤 배치 tx 경계 회귀 가드 — 클래스 @Transactional 재추가 방지.
 * 전종목 크롤은 sleep+Jsoup 를 수천 회 반복: 클래스 tx 면 커넥션 1개를 수십 분~시간 pin
 * + 배치 끝 일괄 커밋(도중 크래시 시 진행분 전부 유실). 각 save 는 자체 짧은 tx 가 맞다.
 */
class FinancialDataCrawlerServiceTest {

    @Test
    @DisplayName("클래스/크롤 메서드에 @Transactional 없음 — 장시간 크롤이 DB 커넥션 pin 금지")
    void crawler_mustNotBeTransactional() throws Exception {
        assertThat(FinancialDataCrawlerService.class.isAnnotationPresent(Transactional.class))
                .as("클래스 @Transactional 재추가 금지 — 전종목 크롤 tx 롱홀드")
                .isFalse();
        // 남은 장시간 전종목 크롤은 종목명 보정뿐이다(영업이익률·분기 크롤은 2026-09-23 은퇴).
        assertThat(FinancialDataCrawlerService.class.getMethod("fixAllStockNames")
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    /**
     * 영업이익률 크롤 은퇴 가드(2026-09-23).
     *
     * <p>올인원 2단계였다. 소스가 분기 크롤과 같은 죽은 페이지라 9/22·9/23 두 회차 모두 성공 0 / 실패 366~367.
     * 대상이 정확히 operating_margin 이 없는 종목이었는데, 그 값은 1단계 KIS 수집기가 이미 채운다 —
     * 영업이익률의 단일 출처는 KIS 다.
     *
     * <p>이름에 {@code OperatingMargin} 이 든 카운트 메서드(DB 상태 조회)는 남았으므로 부분 문자열로는 못 막는다.
     * 그래서 "crawl 로 시작하는 public 메서드는 종목명 보정 하나뿐"으로 고정한다.
     */
    @Test
    @DisplayName("영업이익률 크롤은 은퇴했다 — 남은 crawl* 메서드는 crawlStockName 뿐")
    void operatingMarginCrawl_isRetired() {
        assertThat(java.util.Arrays.stream(FinancialDataCrawlerService.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .filter(n -> n.startsWith("crawl"))
                .distinct()
                .toList())
                .as("영업이익률/재무비율 크롤이 다시 생겼다 — 영업이익률은 KIS 1단계 수집기가 채운다")
                .containsExactly("crawlStockName");
    }

    /**
     * 분기 크롤 은퇴 가드(2026-09-23).
     *
     * <p>네이버가 레거시 금융 페이지를 SPA 로 옮기면서 소스가 죽었다 — 2026-09-22 15:38 배치 실측
     * 분기 수집 성공 0 / 실패 2,662. 분기 재무 단일 출처는 KIS V55(stock_quarterly_financial)다.
     *
     * <p>⚠ 같은 이름으로 되살리면 이 테스트가 깨진다 — <b>그게 의도다.</b> 두 번째 분기 경로가
     * 생기면 어느 쪽이 진짜인지 다시 갈리고, 이 저장소가 반복해서 겪은 "같은 계산이 몇 벌인가"가
     * 또 생긴다. 새 소스가 필요하면 V55 와 같은 테이블로 모을 것.
     */
    @Test
    @DisplayName("네이버 분기 크롤은 은퇴했다 — 같은 이름으로 되살리지 말 것")
    void quarterlyCrawl_isRetired() {
        assertThat(java.util.Arrays.stream(FinancialDataCrawlerService.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .filter(n -> n.toLowerCase().contains("quarterly"))
                .toList())
                .as("분기 크롤 메서드가 다시 생겼다 — V55(stock_quarterly_financial)로 모을 것")
                .isEmpty();
    }
}
