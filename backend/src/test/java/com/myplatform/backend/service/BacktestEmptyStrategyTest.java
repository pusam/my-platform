package com.myplatform.backend.service;

import com.myplatform.backend.dto.BacktestDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.AiStrategySnapshot;
import com.myplatform.backend.entity.AiStrategySnapshot.StrategyType;
import com.myplatform.backend.repository.AiStrategySnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 전략 성과 — 추천이 없는 전략의 적중률·평균 수익은 0% 가 아니라 모름(2026-10-07 화면 점검).
 *
 * <p>재현: 표본 0건 전략도 적중률 0%·평균 수익 0% 를 내려 발굴 → 백테스트에 '적중 0% · +0%'(전패·보합)처럼 보일 수 있었다.
 * 전체 평균은 이미 표본 0이면 null 이다(같은 규칙을 전략별에도).
 */
class BacktestEmptyStrategyTest {

    @Test
    @DisplayName("재현: 추천이 없는 전략은 적중률·평균 수익·최근 지표가 null — 있는 전략은 종전대로")
    void emptyStrategyIsUnknown() {
        AiStrategySnapshotRepository repo = mock(AiStrategySnapshotRepository.class);
        StockPriceService prices = mock(StockPriceService.class);
        for (StrategyType t : StrategyType.values()) {
            when(repo.findByStrategyTypeAndCreatedAtAfterOrderByCreatedAtAsc(eq(t), any())).thenReturn(List.of());
        }
        AiStrategySnapshot swingPick = AiStrategySnapshot.builder()
                .strategyType(StrategyType.SWING).stockCode("005930").stockName("삼성전자")
                .rankNum(1).currentPrice(new BigDecimal("100000")).createdAt(LocalDateTime.now().minusDays(2))
                .build();
        when(repo.findByStrategyTypeAndCreatedAtAfterOrderByCreatedAtAsc(eq(StrategyType.SWING), any()))
                .thenReturn(List.of(swingPick));
        StockPriceDto now = new StockPriceDto();
        now.setCurrentPrice(new BigDecimal("105000"));
        when(prices.getStockPrices(anyList())).thenReturn(Map.of("005930", now));

        BacktestDto.PerformanceResponse r = new BacktestService(repo, prices).getPerformance(7);

        BacktestDto.StrategyPerformance swing = r.getStrategies().stream()
                .filter(s -> "SWING".equals(s.getStrategyType())).findFirst().orElseThrow();
        assertThat(swing.getTotalPicks()).isEqualTo(1);
        assertThat(swing.getHitRate()).isEqualByComparingTo("100.0");
        assertThat(swing.getAvgReturn()).isNotNull();

        BacktestDto.StrategyPerformance value = r.getStrategies().stream()
                .filter(s -> "VALUE".equals(s.getStrategyType())).findFirst().orElseThrow();
        assertThat(value.getTotalPicks()).isZero();
        assertThat(value.getHitRate()).isNull();
        assertThat(value.getAvgReturn()).isNull();
        assertThat(value.getRecentHitRate()).isNull();
        assertThat(value.getRecentAvgReturn()).isNull();
    }
}
