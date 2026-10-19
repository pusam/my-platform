package com.myplatform.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Map;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 재무 수집(08:30·15:38) 뒤 실적 서프라이즈 당일 캐시를 비운다(2026-10-19, 리뷰 에이전트 지적).
 *
 * <p>재현: 서프라이즈 판정은 하루 캐시인데 {@code refreshCache()} 를 부르는 곳이 없었다 — 08:00 재료 워밍이 만든 캐시를 하루 종일 써서,
 * 08:30·15:38 수집이 분기 원본(V55)에 새 분기를 적재해도 그날 11:30·14:00·17:00 종합추천·실적 TOP10·AI 전략은 08:00 판정을
 * 썼다(새 실적이 다음 거래일에야 반영 — 값이 틀린 것은 아니고 최대 1거래일 늦다).
 */
class AsyncCrawlerSurpriseCacheRefreshTest {

    private StockFinancialDataService data;
    private EarningSurpriseService surprise;
    private AsyncCrawlerService crawler;

    @BeforeEach
    void setUp() {
        data = mock(StockFinancialDataService.class);
        when(data.collectAllStocksFinancialData()).thenReturn(Map.of("successCount", 1, "failCount", 0));
        surprise = mock(EarningSurpriseService.class);
        crawler = new AsyncCrawlerService(data, mock(StockFinancialDataCollector.class), mock(SseEmitterService.class),
                mock(BatchJobMonitorService.class), surprise);
    }

    @Test
    @DisplayName("재현: 스케줄 수집(비동기 경로) — 1단계(분기 원본 적재) 뒤 서프라이즈 캐시를 비운다")
    void asyncCollectionRefreshesSurpriseCache() {
        crawler.collectAllInOneAsync();

        InOrder order = inOrder(data, surprise);
        order.verify(data).collectAllStocksFinancialData();
        order.verify(surprise).refreshCache();
    }

    @Test
    @DisplayName("동기 경로도 같다")
    void syncCollectionRefreshesSurpriseCache() {
        crawler.collectAllInOneSync();

        InOrder order = inOrder(data, surprise);
        order.verify(data).collectAllStocksFinancialData();
        order.verify(surprise).refreshCache();
    }
}
