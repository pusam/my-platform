package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 네이버 레거시 금융 크롤 은퇴 가드(2026-09-23).
 *
 * <p><b>무엇이 있었나</b>: {@code FinancialDataCrawlerService} 의 크롤 세 가지(분기 재무제표 · 영업이익률 ·
 * 종목명)가 전부 {@code finance.naver.com/item/main.naver} 를 읽었는데, 네이버가 그 페이지를
 * {@code stock.naver.com} SPA 로 302 이전해 새 HTML 에 값이 없다(JS 렌더링). 예외가 없어 전부 "완료"로 끝났다:
 * <ul>
 *   <li>분기 재무제표 — 9/22 15:38 성공 0 / 실패 2,662 (→ KIS V55 {@code stock_quarterly_financial} 로 단일화)</li>
 *   <li>영업이익률 — 9/22·9/23 두 회차 성공 0 / 실패 366~367 (→ KIS 1단계 수집기가 이미 채움)</li>
 *   <li>종목명 — 리다이렉트된 페이지 제목이 "Npay 증권"(콜론 없음), 파싱 대상 {@code wrap_company}·
 *       {@code rate_info} 0개라 전 종목 null (→ 마스터 폴백이 이미 채움)</li>
 * </ul>
 * 셋 다 은퇴시키자 클래스엔 DB 카운트만 남아 "Crawler" 라는 이름이 거짓이 됐다 — 카운트는
 * {@code StockFinancialDataService} 로 옮기고 클래스를 지웠다.
 *
 * <p>⚠ 이 테스트가 깨지면 <b>그게 의도다.</b> 같은 이름·같은 기능으로 되살리면 어느 쪽이 진짜 출처인지
 * 다시 갈린다(이 저장소의 반복 결함 "같은 계산이 몇 벌인가"). 새 소스가 필요하면 KIS 경로에 모을 것.
 */
class NaverLegacyCrawlRetiredTest {

    @Test
    @DisplayName("종목 상세 수급의 네이버 폴백은 은퇴했다 — 11/3~4 41회 시도 0회 성공, 화면을 열어 둔 동안 15초마다 헛요청·WARN")
    void stockDetailHasNoNaverInvestorFallback() {
        assertThat(Arrays.stream(StockDetailService.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("fetchInvestorFromNaver", "isEmptySupplyDemand");
    }

    @Test
    @DisplayName("종목 상세의 죽은 페이지 읽기도 은퇴했다 — 네이버 메인(배당·목표주가)·기업개요·FnGuide. 배당·목표주가는 NaverConsensusService")
    void stockDetailHasNoDeadPageReads() {
        assertThat(Arrays.stream(StockDetailService.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("fetchNaverMainPage", "fetchFnGuideMetrics", "fetchNaverListedShares",
                        "enrichWithDividendYieldFromDoc", "enrichWithConsensusTargetFromDoc");
    }

    @Test
    @DisplayName("크롤러 클래스는 지워졌다 — 되살리지 말 것")
    void crawlerClassIsGone() {
        assertThatThrownBy(() -> Class.forName("com.myplatform.backend.service.FinancialDataCrawlerService"))
                .as("FinancialDataCrawlerService 가 다시 생겼다 — 분기는 V55, 영업이익률은 KIS 1단계, 종목명은 마스터 폴백이 채운다")
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("비동기 수집 서비스에 은퇴한 세 기능이 다시 붙지 않는다")
    void asyncServiceHasNoRetiredTasks() {
        assertThat(Arrays.stream(AsyncCrawlerService.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(n -> n.contains("Quarterly") || n.contains("OperatingMargin") || n.contains("StockNames"))
                .toList())
                .as("분기·영업이익률·종목명 크롤은 2026-09-23 은퇴했다")
                .isEmpty();
    }

    @Test
    @DisplayName("옮긴 카운트 3종은 재무 데이터 서비스에 있다 — 수집 상태 화면이 계속 쓴다")
    void statusCountsLiveInFinancialDataService() throws Exception {
        for (String name : new String[] {"countMissingOperatingMargin", "countWithOperatingMargin", "countWithGrowthData"}) {
            assertThat(StockFinancialDataService.class.getMethod(name).getReturnType()).isEqualTo(long.class);
        }
    }

    @Test
    @DisplayName("환율 네이버 폴백도 은퇴했다(2026-10-02) — 같은 SPA 이전으로 매번 rate=null 을 '조회 완료'로 남겼다")
    void exchangeRateNaverFallbackIsGone() {
        assertThat(Arrays.stream(ExchangeRateService.class.getDeclaredMethods()).map(Method::getName).toList())
                .as("finance.naver.com/marketindex 는 stock.naver.com SPA 로 302 — USD/KRW 출처는 수출입은행 하나")
                .doesNotContain("fetchFromNaver");
        assertThat(Arrays.stream(ExchangeRateService.class.getDeclaredFields()).map(f -> f.getName()).toList())
                .doesNotContain("NAVER_EXCHANGE_URL");
    }
}
