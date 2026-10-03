package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.dto.AiAnalysisResponseDto;
import com.myplatform.backend.dto.SectorTradingDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 캐시 워머는 Redis 에서 읽은 값을 다시 넣지 않는다(2026-10-03 화면 점검).
 *
 * <p>섹터 거래대금·AI 분석의 화면용 getter 는 Redis 를 먼저 읽는다. 워머가 그 getter 로 값을 받아 다시 put 하면
 * TTL(10·15분)이 1~2분마다 연장돼 <b>그날 첫 계산(08:00·재시작 직후)에 멈춘다</b> — 섹터 히트맵·거래대금과 🎯 AI 분석이
 * 장중 내내 같은 값이었다. 워머는 메모리 계산값(getComputed…)을 넣는다.
 */
class CacheWarmerNoReputTest {

    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final SectorTradingService sector = mock(SectorTradingService.class);
    private final AiStockAnalysisService ai = mock(AiStockAnalysisService.class);

    private final MarketCacheWarmerService warmer = new MarketCacheWarmerService(
            redis, mock(InvestorTradeService.class), mock(InvestorSurgeService.class), sector,
            mock(SectorAnalysisService.class), ai, mock(SectorOpportunityService.class),
            mock(MarketIndicatorService.class), mock(MarketTimingService.class),
            mock(ChartPatternClient.class), mock(SectorStockConfig.class));

    @Test
    @DisplayName("재현: 섹터 워밍이 Redis 우선 getter 를 읽어 같은 값을 다시 넣던 것 — 이제 메모리 계산값")
    void sectorWarmUsesComputedValue() {
        List<SectorTradingDto> fresh = List.of(new SectorTradingDto());
        when(sector.getComputedSectorTrading(any())).thenReturn(fresh);

        warmer.warmSectorTrading();   // 기동 직후 2분 안이라 장 시간과 무관하게 돈다

        verify(sector, never()).getAllSectorTrading(any(TradingPeriod.class));
        verify(redis).put(eq(MarketCacheWarmerService.getCacheSectorTrading()), eq("TODAY"), eq(fresh), any());
    }

    @Test
    @DisplayName("AI 분석 워밍도 Redis 를 거치지 않는 계산값")
    void aiWarmUsesComputedValue() {
        AiAnalysisResponseDto fresh = AiAnalysisResponseDto.builder().build();
        when(ai.getComputedAnalysis()).thenReturn(fresh);

        warmer.warmAiStrategy();

        verify(ai, never()).getAnalysis();
        verify(redis).put(eq(MarketCacheWarmerService.getCacheAiStrategy()), eq("snapshot"), eq(fresh), any());
    }
}
