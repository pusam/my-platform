package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.dto.JudgmentBoardDto;
import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import com.myplatform.backend.service.RecommendationService.StockScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 종합판단 보드 합집합 — 거래대금 하한 미달 발굴-only 행은 점수가 55를 넘어도 매수 후보가 아니라고 표시한다(2026-11-11 검토 7번).
 * 정렬은 그대로다.
 */
class JudgmentBoardLiquidityFloorTest {

    private static RecommendationService.Top5Response resp(List<RecommendationDto> items) {
        return new RecommendationService.Top5Response(items, "15:00 기준", true, Map.of());
    }

    private static StockScore score(String code, double eok) {
        StockScore s = new StockScore(code, code);
        s.earnings = 20; s.supplyDemand = 10; s.technical = 15; s.sectorMomentum = 8;
        s.avgTradedValueEok = BigDecimal.valueOf(eok);
        return s;
    }

    @Test
    @DisplayName("재현: 미달 종목 행에 belowLiquidityFloor=true, 통과 종목은 null")
    void belowRowsAreMarked() {
        RecommendationService rec = mock(RecommendationService.class);
        when(rec.getTop5()).thenReturn(resp(List.of()));
        when(rec.getValueTop10()).thenReturn(resp(List.of(
                RecommendationDto.builder().stockCode("007770").stockName("한일화학").build(),
                RecommendationDto.builder().stockCode("036710").stockName("심텍홀딩스").build())));
        when(rec.getGrowthTop10()).thenReturn(resp(List.of()));
        when(rec.getOversoldTop10()).thenReturn(resp(List.of()));
        when(rec.getEarningsTop10()).thenReturn(resp(List.of()));
        when(rec.getSmartMoneyTop10()).thenReturn(resp(List.of()));
        StockScore thin = score("007770", 0.23);
        StockScore ok = score("036710", 19.7);
        when(rec.categoryScoreSnapshot()).thenReturn(Map.of("007770", thin, "036710", ok));
        when(rec.hasCategoryScoreSnapshot()).thenReturn(true);
        RecommendationService real = mock(RecommendationService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(real, "minAvgTradedValueEok", 5.0);
        Boolean thinMark = real.belowLiquidityFloorOrNull(thin);   // 스터빙 밖에서 계산(Mockito 미완성 스터빙 방지)
        Boolean okMark = real.belowLiquidityFloorOrNull(ok);
        when(rec.belowLiquidityFloorOrNull(thin)).thenReturn(thinMark);
        when(rec.belowLiquidityFloorOrNull(ok)).thenReturn(okMark);

        ChartPatternClient chart = mock(ChartPatternClient.class);
        when(chart.getTimingSignals(any())).thenReturn(new ChartPatternClient.TimingFetch(false, List.of()));
        SectorStockConfig sectors = mock(SectorStockConfig.class);
        when(sectors.getAllSectors()).thenReturn(List.of());
        JudgmentBoardService board = mock(JudgmentBoardService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(board, "recommendationService", rec);
        ReflectionTestUtils.setField(board, "chartPatternClient", chart);
        ReflectionTestUtils.setField(board, "sectorStockConfig", sectors);

        JudgmentBoardDto dto = board.getBoard("union");

        Map<String, Boolean> marks = new java.util.HashMap<>();
        dto.getRows().forEach(r -> marks.put(r.getStockCode(), r.getBelowLiquidityFloor()));
        assertThat(marks.get("007770")).isTrue();
        assertThat(marks.get("036710")).isNull();
        assertThat(marks).containsKeys("007770", "036710");
    }
}
