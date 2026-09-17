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

    private static final java.time.Clock FIXED_CLOCK = java.time.Clock.fixed(
            java.time.ZonedDateTime.of(2026, 9, 17, 14, 0, 0, 0, java.time.ZoneId.of("Asia/Seoul")).toInstant(),
            java.time.ZoneId.of("Asia/Seoul"));

    private BuyChecklistService service;

    @BeforeEach
    void setUp() {
        // F5(2026-09-17): 노후 판정용 달력·시계가 추가됐다. 이 테스트의 기준 시각은 2026-09-17 14:00 이고
        // 기존 케이스는 전부 "신선" 전제이므로, 관련 stub 은 아래 freshDefaults() 가 채운다.
        service = new BuyChecklistService(
                stockStatusService, shortSellingService, investorTradeService,
                compositeSignalService, stockConclusionService,
                new MarketCalendarService(), FIXED_CLOCK);
        freshDefaults();
    }

    /**
     * 기존 케이스는 "데이터는 신선하다"를 전제로 쓰였다 — F5 가 추가한 기준일 축만 그 전제로 채운다.
     * 개별 테스트가 다시 stub 하면 그 값이 이긴다(LENIENT).
     */
    private void freshDefaults() {
        org.mockito.Mockito.lenient().when(shortSellingService.getShortSellingAsOf())
                .thenReturn(java.time.LocalDate.of(2026, 9, 16));
        org.mockito.Mockito.lenient().when(stockStatusService.activeStatus(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(StockStatusService.ActiveStatus.ACTIVE);
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

    @Test
    @DisplayName("공매도 미수집(null) → '미수집' 표기(passed 아님) + 필수 게이트 미차단 (AUDIT P1-3, §4c)")
    void shortSellingMissing_notFakePassed_notBlocking() {
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
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
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
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
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
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
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.HALTED); // 필수 실패
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
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
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
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
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
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
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
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
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
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.HALTED);
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
        when(stockStatusService.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
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
        when(stockStatusService.activeStatus(anyString())).thenThrow(new RuntimeException("DB down"));
        when(shortSellingService.getShortSellingRatio(anyString())).thenReturn(new BigDecimal("2.0"));
        when(investorTradeService.getConsecutiveBuyStocks(anyString(), anyInt()))
                .thenReturn(List.of(consecutive("005930")));
        when(compositeSignalService.evaluate(anyString())).thenReturn(composite(4));
        when(stockConclusionService.getConclusion(anyString()))
                .thenReturn(conclusion(StockConclusionDto.Level.BUY));

        BuyChecklistDto result = service.evaluate("005930");

        assertThat(result.getPassedCount()).isEqualTo(4); // tradable 은 판정 불가
        assertThat(result.getItems()).filteredOn(i -> "tradable".equals(i.getKey()))
                .extracting(BuyChecklistDto.ChecklistItem::getValue)
                .containsExactly("체크 불가");
        // ⚠ 기대값 변경(F5, 2026-09-17 감사): 조회 실패는 이제 dataMissing=true 다.
        //    예전엔 실패가 dataMissing 없이 passed=false 라 "미확인"이 "실제 미충족"과 같이 세어졌고,
        //    필수 항목 실패로 간주돼 NOT_RECOMMENDED 가 나왔다. 그런데 이 코드베이스의 명시적 불변식은
        //    "결측은 판정 불가이지 미충족이 아니다 — 결측을 근거로 차단하지 않는다"(§4c, decideRecommendation
        //    주석)이고, 공매도 미수집은 이미 그렇게 처리되고 있었다(shortSellingMissing_notFakePassed_notBlocking).
        //    두 필수 항목이 서로 다른 극성을 갖는 게 결함이므로 공매도 쪽에 맞췄다.
        //    ⚠ 이 변경은 "조회 실패 시 차단되지 않는다"는 뜻이다 — 요약 문구가 "(판정 불가 1개 제외)"로
        //    그 사실을 밝히지만, 정책상 차단이 옳다면 되돌릴 지점은 여기다(사용자 판단 대기).
        assertThat(result.getItems()).filteredOn(i -> "tradable".equals(i.getKey()))
                .allMatch(BuyChecklistDto.ChecklistItem::isDataMissing);
        assertThat(result.getRecommendation()).isEqualTo(Recommendation.STRONG);
        assertThat(result.getSummary()).contains("판정 불가 1개 제외");
    }
}
