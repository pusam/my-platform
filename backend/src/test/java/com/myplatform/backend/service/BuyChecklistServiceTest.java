package com.myplatform.backend.service;

import com.myplatform.backend.dto.BuyChecklistDto;
import com.myplatform.backend.dto.BuyChecklistDto.Recommendation;
import com.myplatform.backend.dto.CompositeSignalDto;
import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.StockConclusionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class BuyChecklistServiceTest {

    @Mock private StockStatusService stockStatusService;
    @Mock private ShortSellingService shortSellingService;
    @Mock private InvestorTradeService investorTradeService;
    @Mock private CompositeSignalService compositeSignalService;
    @Mock private StockConclusionService stockConclusionService;

    private BuyChecklistService service;

    @BeforeEach
    void setUp() {
        service = new BuyChecklistService(
                stockStatusService, shortSellingService, investorTradeService,
                compositeSignalService, stockConclusionService);
    }

    private ConsecutiveBuyDto consecutive(String stockCode) {
        ConsecutiveBuyDto dto = new ConsecutiveBuyDto();
        dto.setStockCode(stockCode);
        return dto;
    }

    private CompositeSignalDto composite(int matched) {
        return CompositeSignalDto.builder()
                .stockCode("005930").stockName("삼성전자")
                .matchedCount(matched).totalCount(5)
                .signals(Collections.emptyList())
                .build();
    }

    private StockConclusionDto conclusion(StockConclusionDto.Level level) {
        return StockConclusionDto.builder()
                .stockCode("005930").stockName("삼성전자")
                .level(level).dataAvailable(true)
                .factors(Collections.emptyList())
                .build();
    }

    @Test
    @DisplayName("5/5 모두 충족 → STRONG")
    void allPassed_strong() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("2.5"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(List.of(consecutive("005930")));
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.STRONG_BUY));

        BuyChecklistDto result = service.evaluate("005930");

        assertThat(result.getPassedCount()).isEqualTo(5);
        assertThat(result.getRecommendation()).isEqualTo(Recommendation.STRONG);
        assertThat(result.getItems()).allMatch(BuyChecklistDto.ChecklistItem::isPassed);
    }

    @Test
    @DisplayName("거래정지 → tradable 미충족")
    void tradingHalted() {
        when(stockStatusService.isActive(anyString())).thenReturn(false);
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("2.5"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(List.of(consecutive("005930")));
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.STRONG_BUY));

        BuyChecklistDto result = service.evaluate("005930");

        assertThat(result.getPassedCount()).isEqualTo(4);
        assertThat(result.getRecommendation()).isEqualTo(Recommendation.MODERATE);
        assertThat(result.getItems()).filteredOn(i -> "tradable".equals(i.getKey()))
                .extracting(BuyChecklistDto.ChecklistItem::isPassed).containsExactly(false);
    }

    @Test
    @DisplayName("공매도 6% → shortSelling 미충족, 3/5 CAUTION")
    void highShortSelling_caution() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("6.5"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(Collections.emptyList()); // 연속매수 X
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.BUY));

        BuyChecklistDto result = service.evaluate("005930");

        assertThat(result.getPassedCount()).isEqualTo(3);
        assertThat(result.getRecommendation()).isEqualTo(Recommendation.CAUTION);
    }

    @Test
    @DisplayName("모두 미충족 → NOT_RECOMMENDED")
    void allFailed_notRecommended() {
        when(stockStatusService.isActive(anyString())).thenReturn(false);
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("8.0"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(1));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.WAIT));

        BuyChecklistDto result = service.evaluate("005930");

        assertThat(result.getPassedCount()).isZero();
        assertThat(result.getRecommendation()).isEqualTo(Recommendation.NOT_RECOMMENDED);
    }

    @Test
    @DisplayName("의존 서비스 예외 → 해당 항목만 체크 불가, 나머지 정상")
    void dependencyFailure_partialEvaluation() {
        when(stockStatusService.isActive(anyString())).thenThrow(new RuntimeException("DB down"));
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("2.0"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(List.of(consecutive("005930")));
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.BUY));

        BuyChecklistDto result = service.evaluate("005930");

        assertThat(result.getPassedCount()).isEqualTo(4); // tradable 만 실패
        assertThat(result.getItems()).filteredOn(i -> "tradable".equals(i.getKey()))
                .extracting(BuyChecklistDto.ChecklistItem::getValue)
                .containsExactly("체크 불가");
    }
}
