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
    @DisplayName("공매도 미수집(null) → '미수집' 표기(passed 아님) + 필수 게이트 미차단 (AUDIT P1-3, §4c)")
    void shortSellingMissing_notFakePassed_notBlocking() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(null); // 死피드/결측
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(List.of(consecutive("005930")));
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.STRONG_BUY));

        BuyChecklistDto dto = service.evaluate("005930");

        BuyChecklistDto.ChecklistItem item = dto.getItems().stream()
                .filter(i -> "shortSelling".equals(i.getKey())).findFirst().orElseThrow();
        // 가짜 "0.00% 충족" 금지 — passed 아님 + 미수집 명시
        assertThat(item.isPassed()).isFalse();
        assertThat(item.isDataMissing()).isTrue();
        assertThat(item.getValue()).isEqualTo("미수집");
        // 결측은 필수 게이트를 차단하지 않는다(봇 결측=통과와 동일 극성) — 가산 3/3 이면 STRONG
        assertThat(dto.getRecommendation()).isEqualTo(Recommendation.STRONG);
        assertThat(dto.getPassedCount()).isEqualTo(4); // 미수집은 충족 카운트에도 미포함
    }

    @Test
    @DisplayName("공매도 실측 초과(6.5%)는 여전히 필수 차단 → NOT_RECOMMENDED (미수집과 구분)")
    void shortSellingRealHighValue_stillBlocks() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("6.5"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(List.of(consecutive("005930")));
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.STRONG_BUY));

        BuyChecklistDto dto = service.evaluate("005930");

        BuyChecklistDto.ChecklistItem item = dto.getItems().stream()
                .filter(i -> "shortSelling".equals(i.getKey())).findFirst().orElseThrow();
        assertThat(item.isDataMissing()).isFalse();
        assertThat(dto.getRecommendation()).isEqualTo(Recommendation.NOT_RECOMMENDED);
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
    @DisplayName("필수(tradable) 미충족 → 가산 다 충족해도 NOT_RECOMMENDED (phase19)")
    void requiredFail_tradable_overridesBonuses() {
        when(stockStatusService.isActive(anyString())).thenReturn(false); // 필수 실패
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("2.5"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(List.of(consecutive("005930")));
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.STRONG_BUY));

        BuyChecklistDto result = service.evaluate("005930");

        // 가산 3개 모두 통과해도 필수 1개 실패면 즉시 NOT_RECOMMENDED
        assertThat(result.getRecommendation()).isEqualTo(Recommendation.NOT_RECOMMENDED);
    }

    @Test
    @DisplayName("필수(shortSelling) 미충족 → 가산 다 충족해도 NOT_RECOMMENDED (phase19)")
    void requiredFail_shortSelling_overridesBonuses() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("6.5")); // 필수 실패
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(List.of(consecutive("005930")));
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.STRONG_BUY));

        BuyChecklistDto result = service.evaluate("005930");

        assertThat(result.getRecommendation()).isEqualTo(Recommendation.NOT_RECOMMENDED);
    }

    @Test
    @DisplayName("필수 OK + 가산 2/3 → MODERATE (phase19)")
    void requiredOk_bonus2_moderate() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("2.0"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(Collections.emptyList()); // 가산 1개 실패
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.BUY));

        BuyChecklistDto result = service.evaluate("005930");

        assertThat(result.getRecommendation()).isEqualTo(Recommendation.MODERATE);
    }

    @Test
    @DisplayName("필수 OK + 가산 1/3 → CAUTION (phase19)")
    void requiredOk_bonus1_caution() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("2.0"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(2)); // 매칭 부족
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.BUY)); // 가산 1개 통과

        BuyChecklistDto result = service.evaluate("005930");

        assertThat(result.getRecommendation()).isEqualTo(Recommendation.CAUTION);
    }

    @Test
    @DisplayName("필수 OK + 가산 0/3 → NOT_RECOMMENDED (phase19)")
    void requiredOk_bonus0_notRecommended() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("2.0"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(2));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.WAIT));

        BuyChecklistDto result = service.evaluate("005930");

        // 필수만 통과는 진입 근거 부족 → NOT_RECOMMENDED
        assertThat(result.getRecommendation()).isEqualTo(Recommendation.NOT_RECOMMENDED);
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
    @DisplayName("외국인만 연속매수 매칭 → value '외국인' (단락평가로 '외국인+기관' 위장하던 버그)")
    void consecutiveBuy_foreignOnly_showsForeignOnly() {
        when(stockStatusService.isActive(anyString())).thenReturn(true);
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("2.0"));
        when(investorTradeService.getConsecutiveBuyStocks(org.mockito.ArgumentMatchers.eq("FOREIGN"), anyInt()))
                .thenReturn(List.of(consecutive("005930")));
        when(investorTradeService.getConsecutiveBuyStocks(org.mockito.ArgumentMatchers.eq("INSTITUTION"), anyInt()))
                .thenReturn(Collections.emptyList()); // 기관은 미매칭
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.BUY));

        BuyChecklistDto dto = service.evaluate("005930");

        BuyChecklistDto.ChecklistItem item = dto.getItems().stream()
                .filter(i -> "consecutiveBuy".equals(i.getKey())).findFirst().orElseThrow();
        assertThat(item.isPassed()).isTrue();          // 한쪽만 매칭돼도 통과는 유지
        assertThat(item.getValue()).isEqualTo("외국인"); // 근거 표시는 실제 매칭 주체만
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
        // 필수 항목(tradable) 실패 → phase19 룰에 따라 NOT_RECOMMENDED
        assertThat(result.getRecommendation()).isEqualTo(Recommendation.NOT_RECOMMENDED);
    }
}
