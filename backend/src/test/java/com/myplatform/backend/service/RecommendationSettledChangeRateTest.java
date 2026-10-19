package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.service.RecommendationService.StockScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * 종합추천에서 점수·순서를 정하는 등락률은 '마감 확정된 마지막 봉' 하나다 — 10/7 섹터 점수 결정의 남은 세 곳(2026-10-19).
 *
 * <p>재현: 10/7 에 섹터 점수만 확정 봉 등락률로 바꾸고, 세 곳은 후보가 실어 온 등락률({@code StockScore.changeRate})을 그대로 썼다.
 * 그 값은 후보 출처마다 시각이 다르다 — 수급 후보는 투자자 매매 행 값(직전 거래일), AI 후보는 AI 스냅샷이 만들어진 시점 값(오늘 10시
 * 스윙·어제 18시 가치 등), 실적만으로 들어온 후보는 없음(null).
 * <ol>
 *   <li>동점 순서('덜 오른 종목 우선')가 종목마다 다른 시각의 등락률을 견줬다.</li>
 *   <li>봉이 5개 미만인 종목의 간편 기술 점수가 그 값으로 매겨졌다(같은 종목의 저장된 확정 봉이 있는데도).</li>
 *   <li>실시간 시세가 없을 때 수급 괴리 감점(기술 −3)이 그 값으로 판정됐다 — '실시간 교차검증'인데 실시간 값이 아니었다.</li>
 * </ol>
 */
class RecommendationSettledChangeRateTest {

    private static final MarketCalendarService CALENDAR = new MarketCalendarService();

    private static StockPriceHistory bar(String code, LocalDate date, String changeRate) {
        return StockPriceHistory.builder().stockCode(code).tradeDate(date).changeRate(new BigDecimal(changeRate)).build();
    }

    /** 점수가 같은(100점) 후보 — 실어 온 등락률과 확정 봉 등락률을 따로 준다. */
    private static StockScore tied(String code, String carried, String settled) {
        StockScore s = new StockScore(code, code);
        s.earnings = 20; s.supplyDemand = 20; s.technical = 20; s.sectorMomentum = 20;
        s.changeRate = carried == null ? null : new BigDecimal(carried);
        s.settledChangeRate = settled == null ? null : new BigDecimal(settled);
        return s;
    }

    private static RecommendationService serviceWith(List<StockPriceHistory> bars, Map<String, StockPriceDto> live) {
        RecommendationService service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        StockPriceHistoryRepository history = mock(StockPriceHistoryRepository.class);
        StockPriceService prices = mock(StockPriceService.class);
        when(history.findByStockCodesSince(anyList(), any())).thenReturn(bars);
        when(prices.getStockPricesFromCacheOnly(anyList())).thenReturn(live);
        ReflectionTestUtils.setField(service, "priceHistoryRepository", history);
        ReflectionTestUtils.setField(service, "stockPriceService", prices);
        ReflectionTestUtils.setField(service, "stockAnalysisService", mock(StockAnalysisService.class));
        ReflectionTestUtils.setField(service, "marketCalendar", CALENDAR);
        // 목(mock)은 필드 초기화를 건너뛴다 — 수집 예약 집합(2026-10-19)은 직접 넣는다
        ReflectionTestUtils.setField(service, "priceHistoryCollecting", java.util.concurrent.ConcurrentHashMap.newKeySet());
        return service;
    }

    @Test
    @DisplayName("재현 ①: 동점 순서는 마지막 확정 봉 등락률로 — AI 스냅샷 시점 +0.5%(확정 +9.0%)보다 확정 +1.0% 가 위")
    void tieBreakUsesSettledChange() {
        StockScore aiSeed = tied("AI", "0.5", "9.0");
        StockScore supply = tied("SUP", "5.0", "1.0");
        List<StockScore> list = new ArrayList<>(List.of(aiSeed, supply));

        list.sort(RecommendationService.recommendationComparator(Map.of()));

        assertThat(list).extracting(s -> s.stockCode).containsExactly("SUP", "AI");
    }

    @Test
    @DisplayName("확정 봉 등락률을 모르면 동점 순서 맨 뒤 — 실어 온 +0.1% 로 '덜 올랐다'고 메우지 않는다")
    void unknownSettledChangeGoesLast() {
        StockScore unknown = tied("UNK", "0.1", null);
        StockScore known = tied("KNOWN", "8.0", "6.0");
        List<StockScore> list = new ArrayList<>(List.of(unknown, known));

        list.sort(RecommendationService.recommendationComparator(Map.of()));

        assertThat(list).extracting(s -> s.stockCode).containsExactly("KNOWN", "UNK");
    }

    @Test
    @DisplayName("재현 ②: 봉 5개 미만 종목의 간편 기술 점수는 마지막 확정 봉(+3.0% → 8점)으로 — 실어 온 −5.0%(→ 1점)가 아니라")
    void simpleTechnicalUsesLatestSettledBar() {
        LocalDate today = LocalDate.now();
        StockScore s = new StockScore("NEW01", "신규상장");
        s.changeRate = new BigDecimal("-5.0");
        Map<String, StockScore> map = new LinkedHashMap<>(Map.of("NEW01", s));
        // 저장소는 tradeDate DESC 로 준다(findByStockCodesSince 계약)
        RecommendationService service = serviceWith(List.of(
                bar("NEW01", CALENDAR.minusTradingDays(today, 1), "3.0"),
                bar("NEW01", CALENDAR.minusTradingDays(today, 2), "-1.0"),
                bar("NEW01", CALENDAR.minusTradingDays(today, 3), "0.5")), Map.of());

        ReflectionTestUtils.invokeMethod(service, "scoreTechnical", map);

        assertThat(s.technical).isEqualTo(8);
        assertThat(s.tags).contains("기술(간편)");
    }

    /** 수급 점수 0 · 다른 세 축으로 유효 3개 — 실시간 교차검증 대상. */
    private static StockScore noSupplyCandidate(String code, String carried) {
        StockScore s = new StockScore(code, code);
        s.earnings = 20; s.technical = 10; s.sectorMomentum = 10; s.supplyDemand = 0;
        s.changeRate = carried == null ? null : new BigDecimal(carried);
        return s;
    }

    @Test
    @DisplayName("재현 ③: 실시간 시세가 없으면 수급 괴리를 판정하지 않는다 — 실어 온 +5.0%(AI 스냅샷 시점)로 −3 감점하지 않는다")
    void divergenceNeedsLiveQuote() {
        StockScore s = noSupplyCandidate("AI01", "5.0");
        RecommendationService service = serviceWith(List.of(), Map.of());

        ReflectionTestUtils.invokeMethod(service, "applyRealtimeChecks", new LinkedHashMap<>(Map.of("AI01", s)));

        assertThat(s.technical).isEqualTo(10);
        assertThat(s.tags).doesNotContain("⚠수급괴리");
    }

    @Test
    @DisplayName("실시간 시세 +5.0% 면 판정은 그대로 — 기술 −3·'⚠수급괴리'")
    void divergenceWithLiveQuoteStillPenalizes() {
        StockScore s = noSupplyCandidate("AI02", null);
        StockPriceDto live = new StockPriceDto();
        live.setChangeRate(new BigDecimal("5.0"));
        RecommendationService service = serviceWith(List.of(), Map.of("AI02", live));

        ReflectionTestUtils.invokeMethod(service, "applyRealtimeChecks", new LinkedHashMap<>(Map.of("AI02", s)));

        assertThat(s.technical).isEqualTo(7);
        assertThat(s.tags).contains("⚠수급괴리");
    }

    // ---------------------------------------------------------------- '오늘' 탭 후보 카드 등락률

    @Test
    @DisplayName("재현: 후보 카드 등락률은 실어 온 값(AI 스냅샷 시점 +5.0%)이 아니라 마지막 확정 봉(+1.2%)과 그 날짜 — 시세 캐시가 없을 때 시각 표시 없이 보였다")
    void dtoChangeRateIsSettledWithBasisDate() {
        StockScore s = tied("A", "5.0", "1.2");
        s.settledChangeDate = LocalDate.of(2026, 10, 16);
        RecommendationService service = mock(RecommendationService.class, CALLS_REAL_METHODS);

        RecommendationService.RecommendationDto dto = ReflectionTestUtils.invokeMethod(service, "toDto", s);

        assertThat(dto.getChangeRate()).isEqualByComparingTo("1.2");
        assertThat(dto.getChangeRateBasisDate()).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    @DisplayName("실시간 시세(캐시)가 있으면 그 값으로 덮고 기준일을 지운다 — 실시간 값")
    void liveQuoteOverridesAndClearsBasisDate() {
        RecommendationService.RecommendationDto dto = RecommendationService.RecommendationDto.builder().stockCode("A")
                .changeRate(new BigDecimal("1.2")).changeRateBasisDate(LocalDate.of(2026, 10, 16)).build();
        StockPriceDto live = new StockPriceDto();
        live.setChangeRate(new BigDecimal("3.3"));
        RecommendationService service = serviceWith(List.of(), Map.of("A", live));

        ReflectionTestUtils.invokeMethod(service, "refreshPrices", List.of(dto));

        assertThat(dto.getChangeRate()).isEqualByComparingTo("3.3");
        assertThat(dto.getChangeRateBasisDate()).isNull();
    }
}
