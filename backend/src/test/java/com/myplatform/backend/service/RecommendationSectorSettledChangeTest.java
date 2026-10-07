package com.myplatform.backend.service;

import com.myplatform.backend.dto.AiStrategySnapshotDto;
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
 * 섹터 점수(종목 등락률 가점)는 전 종목 '마감 확정된 마지막 봉'의 등락률로 매긴다(2026-10-07, 사용자 결정 '추천으로').
 *
 * <p>재현(10/7 운영): 등락률 출처가 후보 출처마다 달랐다 — 수급 후보는 투자자 매매 행의 등락률(직전 거래일 종가 기준), AI 후보는
 * AI 스냅샷이 만들어진 시점 값(오늘 10시 스윙·어제 18시 가치 등, 그 종목이 스냅샷 어디에든 있으면 그 값이 우선), 실적만으로 들어온
 * 후보는 출처가 없어 가점 0(장중 최소 2점). 한 스냅샷 안에서 같은 축이 서로 다른 시각을 쟀다 — 그날 고친 '형성 봉'(기술 축)과 같은
 * 부류다. 이제 기술 채점과 같은 봉(저장된 최신 확정 봉, 직전 거래일보다 오래되면 모름)의 등락률 하나로 맞춘다. 장중엔 마감 전 봉이
 * 저장되지 않으므로 전 종목이 직전 거래일 등락률로 같다 — 오늘 급등은 과열 감점에서 빠지므로 가점에서도 빠져야 추격 편향이 없다.
 */
class RecommendationSectorSettledChangeTest {

    private static final MarketCalendarService CALENDAR = new MarketCalendarService();

    private static StockPriceHistory bar(String code, LocalDate date, String changeRate) {
        return StockPriceHistory.builder().stockCode(code).tradeDate(date).changeRate(new BigDecimal(changeRate)).build();
    }

    private static Map<String, StockScore> score(List<AiStrategySnapshotDto> aiSnapshots, List<StockPriceHistory> bars,
                                                 Map<String, StockScore> scoreMap) {
        RecommendationService service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        SectorTradingService sector = mock(SectorTradingService.class);
        AiStrategySnapshotService ai = mock(AiStrategySnapshotService.class);
        StockPriceHistoryRepository history = mock(StockPriceHistoryRepository.class);
        when(sector.getSectorRotation()).thenReturn(List.of());   // 국면 UNKNOWN — 섹터 일괄 가산 없음
        when(ai.getAllLatestSnapshots()).thenReturn(AiStrategySnapshotDto.AllStrategiesResponse.builder()
                .strategies(Map.of("SWING", aiSnapshots)).build());
        when(history.findByStockCodesSince(anyList(), any())).thenReturn(bars);
        ReflectionTestUtils.setField(service, "sectorTradingService", sector);
        ReflectionTestUtils.setField(service, "aiStrategyService", ai);
        ReflectionTestUtils.setField(service, "priceHistoryRepository", history);
        ReflectionTestUtils.setField(service, "marketCalendar", CALENDAR);
        ReflectionTestUtils.invokeMethod(service, "scoreSectorMomentum", scoreMap);
        return scoreMap;
    }

    private static StockScore seed(String code, String changeRate) {
        StockScore s = new StockScore(code, "종목" + code);
        s.changeRate = changeRate == null ? null : new BigDecimal(changeRate);
        return s;
    }

    @Test
    @DisplayName("재현: AI 시드도 스냅샷 시점 등락률(+3.5%)이 아니라 마지막 확정 봉 등락률(+0.8%)로 — 4점이 아니라 2점")
    void aiSeedUsesSettledBar() {
        LocalDate settled = CALENDAR.minusTradingDays(LocalDate.now(), 1);
        Map<String, StockScore> map = new LinkedHashMap<>();
        map.put("086980", seed("086980", "3.5"));   // scoreAiStrategy 가 스냅샷 값을 실어 둔 상태
        AiStrategySnapshotDto snap = AiStrategySnapshotDto.builder().stockCode("086980").stockName("쇼박스")
                .changeRate(new BigDecimal("3.5")).build();

        score(List.of(snap), List.of(bar("086980", settled, "0.8")), map);

        assertThat(map.get("086980").sectorMomentum).isEqualTo(2);
    }

    @Test
    @DisplayName("등락률 출처가 없던 후보(실적만)도 같은 봉으로 — 마지막 확정 봉 +3.5% 면 4점")
    void seedWithoutChangeRateUsesSettledBar() {
        LocalDate settled = CALENDAR.minusTradingDays(LocalDate.now(), 1);
        Map<String, StockScore> map = new LinkedHashMap<>();
        map.put("005930", seed("005930", null));

        score(List.of(), List.of(bar("005930", settled, "3.5")), map);

        assertThat(map.get("005930").sectorMomentum).isEqualTo(4);
    }

    @Test
    @DisplayName("직전 거래일보다 오래된 봉은 쓰지 않는다 — 노후는 모름(가점 없음), 후보가 실어 온 등락률로 메우지도 않는다")
    void staleBarIsUnknown() {
        LocalDate stale = CALENDAR.minusTradingDays(LocalDate.now(), 10);
        Map<String, StockScore> map = new LinkedHashMap<>();
        map.put("294090", seed("294090", "8.7"));   // 수급 후보가 실어 온 값

        score(List.of(), List.of(bar("294090", stale, "5.0")), map);

        assertThat(map.get("294090").sectorMomentum).as("가점 없음 — 장중이면 최소 2점, 아니면 0").isLessThanOrEqualTo(2);
    }
}
