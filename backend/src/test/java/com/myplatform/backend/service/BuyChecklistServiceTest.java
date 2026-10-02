package com.myplatform.backend.service;

import com.myplatform.backend.dto.BuyChecklistDto;
import com.myplatform.backend.dto.BuyChecklistDto.Recommendation;
import com.myplatform.backend.dto.CompositeSignalDto;
import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.StockConclusionDto;
import com.myplatform.backend.shortselling.ShortSellingTradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    @Mock private ShortSellingTradeService shortSellingTradeService;
    @Mock private InvestorTradeService investorTradeService;
    @Mock private CompositeSignalService compositeSignalService;
    @Mock private StockConclusionService stockConclusionService;

    private static final java.time.Clock FIXED_CLOCK = java.time.Clock.fixed(
            java.time.ZonedDateTime.of(2026, 9, 17, 14, 0, 0, 0, java.time.ZoneId.of("Asia/Seoul")).toInstant(),
            java.time.ZoneId.of("Asia/Seoul"));

    private BuyChecklistService service;

    @BeforeEach
    void setUp() {
        // F5(2026-09-17): 노후 판정용 달력·시계가 추가됐다. 이 테스트의 기준 시각은 2026-09-17 14:00 이고
        // 기존 케이스는 전부 "신선" 전제이므로, 관련 stub 은 아래 freshDefaults() 가 채운다.
        service = new BuyChecklistService(
                stockStatusService, shortSellingTradeService, investorTradeService,
                compositeSignalService, stockConclusionService,
                new MarketCalendarService(), FIXED_CLOCK);
        freshDefaults();
    }

    /**
     * 기존 케이스는 "데이터는 신선하다"를 전제로 쓰였다. 개별 테스트가 다시 stub 하면 그 값이 이긴다(LENIENT).
     * 공매도는 2026-10-02 부터 거래 비중 <b>참고 항목</b>이다 — 기본값은 직전 마감일 2% 로 둔다.
     */
    private void freshDefaults() {
        org.mockito.Mockito.lenient().when(shortSellingTradeService.stockShare(anyString()))
                .thenReturn(share("2.00"));
        org.mockito.Mockito.lenient().when(stockStatusService.activeStatus(anyString()))
                .thenReturn(StockStatusService.ActiveStatus.ACTIVE);
    }

    private static ShortSellingTradeService.StockShare share(String volumeShare) {
        return new ShortSellingTradeService.StockShare(true, LocalDate.of(2026, 9, 16),
                new BigDecimal(volumeShare), 1_000L, new BigDecimal(volumeShare), null);
    }

    /** F5(2026-09-17): endDate 신선도를 보므로 기존 "연속매수 있음" 케이스는 최신 거래일을 넣는다. */
    private ConsecutiveBuyDto consecutive(String stockCode) {
        ConsecutiveBuyDto dto = new ConsecutiveBuyDto();
        dto.setStockCode(stockCode);
        dto.setEndDate(java.time.LocalDate.of(2026, 9, 16));
        return dto;
    }

    private CompositeSignalDto composite(int matched) {
        return CompositeSignalDto.builder()
                .stockCode("005930").stockName("삼성전자")
                .matchedCount(matched).totalCount(5)
                .signals(Collections.emptyList())
                .build();
    }

    /** F4(2026-09-17): 결론 DTO 에 currentlyValid 가 생겼다 — 기존 케이스는 "현재 유효" 전제다. */
    private StockConclusionDto conclusion(StockConclusionDto.Level level) {
        return StockConclusionDto.builder()
                .stockCode("005930").stockName("삼성전자")
                .level(level).dataAvailable(true).currentlyValid(true)
                .factors(Collections.emptyList())
                .build();
    }

    private void allBonusesPass() {
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(List.of(consecutive("005930")));
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.STRONG_BUY));
    }

    private static BuyChecklistDto.ChecklistItem shortItem(BuyChecklistDto dto) {
        return dto.getItems().stream().filter(i -> "shortSelling".equals(i.getKey())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("공매도는 참고 항목 — 값이 높아도(8%) 권고를 막지 않는다(거래 비중 · 기준 미검증, 2026-10-02 사용자 결정)")
    void highShortShareDoesNotBlock() {
        when(shortSellingTradeService.stockShare(anyString())).thenReturn(share("8.00"));
        allBonusesPass();

        BuyChecklistDto dto = service.evaluate("005930");

        BuyChecklistDto.ChecklistItem item = shortItem(dto);
        assertThat(item.isInformational()).isTrue();
        assertThat(item.isPassed()).isFalse();
        assertThat(item.isDataMissing()).isFalse();
        assertThat(item.getValue()).isEqualTo("8.00%");
        assertThat(dto.getRecommendation()).isEqualTo(Recommendation.STRONG);
    }

    @Test
    @DisplayName("공매도 조회 실패도 참고 항목 — '조회 실패'로 보이고 판정 불가 개수에 안 들어간다")
    void shortShareFailureIsInformational() {
        when(shortSellingTradeService.stockShare(anyString())).thenReturn(
                new ShortSellingTradeService.StockShare(false, null, null, null, null, "KIS 응답 없음"));
        allBonusesPass();

        BuyChecklistDto dto = service.evaluate("005930");

        BuyChecklistDto.ChecklistItem item = shortItem(dto);
        assertThat(item.isInformational()).isTrue();
        assertThat(item.getValue()).isEqualTo("조회 실패");
        assertThat(dto.getRecommendation()).isEqualTo(Recommendation.STRONG);
        assertThat(dto.getSummary()).doesNotContain("판정 불가");
    }

    @Test
    @DisplayName("필수 항목은 거래 가능 상태 하나 — 충족 개수·분모에 참고 항목은 안 들어간다")
    void allDecidablePassed_strong() {
        allBonusesPass();

        BuyChecklistDto result = service.evaluate("005930");

        assertThat(result.getPassedCount()).isEqualTo(4);
        assertThat(result.getTotalCount()).isEqualTo(4);
        assertThat(result.getRecommendation()).isEqualTo(Recommendation.STRONG);
        assertThat(result.getItems()).filteredOn(i -> !i.isInformational())
                .allMatch(BuyChecklistDto.ChecklistItem::isPassed);
    }

    @Test
    @DisplayName("필수(tradable) 미충족 → 가산 다 충족해도 NOT_RECOMMENDED (phase19)")
    void requiredFail_tradable_overridesBonuses() {
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.HALTED); // 필수 실패
        allBonusesPass();

        BuyChecklistDto result = service.evaluate("005930");

        // 가산 3개 모두 통과해도 필수 1개 실패면 즉시 NOT_RECOMMENDED
        assertThat(result.getRecommendation()).isEqualTo(Recommendation.NOT_RECOMMENDED);
    }

    @Test
    @DisplayName("필수 OK + 가산 2/3 → MODERATE (phase19)")
    void requiredOk_bonus2_moderate() {
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
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.HALTED);
        when(shortSellingTradeService.stockShare(anyString())).thenReturn(share("8.00"));
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
        when(stockStatusService.activeStatus(anyString())).thenThrow(new RuntimeException("DB down"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(List.of(consecutive("005930")));
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.BUY));

        BuyChecklistDto result = service.evaluate("005930");

        assertThat(result.getPassedCount()).isEqualTo(3); // tradable 은 판정 불가, 공매도는 참고
        assertThat(result.getItems()).filteredOn(i -> "tradable".equals(i.getKey()))
                .extracting(BuyChecklistDto.ChecklistItem::getValue)
                .containsExactly("체크 불가");
        // ⚠ 기대값 변경(F5, 2026-09-17 감사): 조회 실패는 dataMissing=true 다 — 결측은 판정 불가이지 미충족이 아니다
        //    (§4c, decideRecommendation 주석). 정책상 차단이 옳다면 되돌릴 지점은 여기다(사용자 판단 대기).
        assertThat(result.getItems()).filteredOn(i -> "tradable".equals(i.getKey()))
                .allMatch(BuyChecklistDto.ChecklistItem::isDataMissing);
        assertThat(result.getRecommendation()).isEqualTo(Recommendation.STRONG);
        assertThat(result.getSummary()).contains("판정 불가 1개 제외");
    }
}
