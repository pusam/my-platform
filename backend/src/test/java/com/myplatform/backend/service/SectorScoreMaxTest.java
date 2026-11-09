package com.myplatform.backend.service;

import com.myplatform.backend.dto.AiStrategySnapshotDto;
import com.myplatform.backend.dto.SectorRotationDto;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.service.RecommendationService.StockScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 100점 환산의 분모는 실제 만점을 따른다(2026-11-10, 사용자 결정 '만점 58로 환산').
 *
 * <p>정규화(normalizeScore)는 섹터 점수의 최댓값을 상수(SECTOR_SCORE_MAX)로 안다 — 섹터 규칙(등락률 가점·강세장 일괄 가산)을
 * 바꾸고 이 상수를 그대로 두면 분모가 틀어져 등급 경계(55/75)의 뜻이 다시 어긋난다. 그래서 실제 섹터 채점이 낼 수 있는
 * 최댓값과 상수를 대조한다.
 */
class SectorScoreMaxTest {

    private static final MarketCalendarService CALENDAR = new MarketCalendarService();

    @Test
    @DisplayName("강세장 + 마지막 확정 봉 크게 상승 = 섹터 최댓값 — 정규화가 쓰는 SECTOR_SCORE_MAX 와 같다")
    void bullTopEqualsSectorScoreMax() {
        RecommendationService service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        SectorTradingService sector = mock(SectorTradingService.class);
        AiStrategySnapshotService ai = mock(AiStrategySnapshotService.class);
        StockPriceHistoryRepository history = mock(StockPriceHistoryRepository.class);
        // 전 종목 평균 +2% — 강세장(BULL, 기준 +1%)
        when(sector.getSectorRotation()).thenReturn(List.of(
                SectorRotationDto.builder().sectorCode("A").rawAvgChangeRate(new BigDecimal("2.0")).build()));
        when(ai.getAllLatestSnapshots()).thenReturn(AiStrategySnapshotDto.AllStrategiesResponse.builder()
                .strategies(Map.of()).build());
        LocalDate settled = CALENDAR.minusTradingDays(LocalDate.now(), 1);
        when(history.findByStockCodesSince(anyList(), any())).thenReturn(List.of(
                StockPriceHistory.builder().stockCode("005930").tradeDate(settled).changeRate(new BigDecimal("9.9")).build()));
        ReflectionTestUtils.setField(service, "sectorTradingService", sector);
        ReflectionTestUtils.setField(service, "aiStrategyService", ai);
        ReflectionTestUtils.setField(service, "priceHistoryRepository", history);
        ReflectionTestUtils.setField(service, "marketCalendar", CALENDAR);

        Map<String, StockScore> map = new LinkedHashMap<>();
        map.put("005930", new StockScore("005930", "삼성전자"));
        ReflectionTestUtils.invokeMethod(service, "scoreSectorMomentum", map);

        assertThat(map.get("005930").sectorMomentum).isEqualTo(RecommendationService.SECTOR_SCORE_MAX);
    }

    @Test
    @DisplayName("원점수 만점 = 실적 20 + 수급 10(캡 기본값) + 기술 20 + 섹터 8 = 58")
    void rawMaxIs58() {
        assertThat(RecommendationService.RAW_SCORE_MAX).isEqualTo(58);
        assertThat(RecommendationService.SUPPLY_CONTRIBUTION_MAX).isEqualTo(10);
    }
}
