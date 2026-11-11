package com.myplatform.backend.service;

import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import com.myplatform.backend.service.RecommendationService.StockScore;
import com.myplatform.backend.dto.StockPriceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 거래대금 하한 배선(2026-11-11 검토 4번) — 평균을 채우는 한 줄·calculate()·스냅샷 저장·DB 복원이 실제로 하한을 거치는가.
 * 순수 판정만 테스트하면 평균을 채우는 줄을 지워도 전부 '판정 불가 = 통과'로 초록이었다.
 */
class RecommendationLiquidityWiringTest {

    private static final MarketCalendarService CALENDAR = new MarketCalendarService();

    private static StockPriceHistory bar(String code, LocalDate d, long close, long volume) {
        return StockPriceHistory.builder().stockCode(code).tradeDate(d).closePrice(BigDecimal.valueOf(close))
                .volume(BigDecimal.valueOf(volume)).changeRate(BigDecimal.ONE).build();
    }

    /** 오늘부터 거꾸로 20봉 — 하루 거래대금 eok 억(종가 10,000원). */
    private static List<StockPriceHistory> bars(String code, double eok) {
        List<StockPriceHistory> out = new ArrayList<>();
        for (int i = 0; i < 20; i++) out.add(bar(code, LocalDate.now().minusDays(i), 10_000, Math.round(eok * 1e8 / 10_000)));
        return out;
    }

    private static RecommendationService baseService() {
        RecommendationService service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "minAvgTradedValueEok", 5.0);
        ReflectionTestUtils.setField(service, "marketCalendar", CALENDAR);
        ReflectionTestUtils.setField(service, "priceHistoryRepository", mock(StockPriceHistoryRepository.class));
        ReflectionTestUtils.setField(service, "snapshotRepository", mock(RecommendationSnapshotRepository.class));
        StockStatusService status = mock(StockStatusService.class);
        when(status.isActive(anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "stockStatusService", status);
        return service;
    }

    @Test
    @DisplayName("재현: 기술 채점이 이미 읽은 봉으로 20일 평균 거래대금을 채운다")
    void scoreTechnicalFillsAverage() {
        RecommendationService service = baseService();
        ReflectionTestUtils.setField(service, "stockAnalysisService", mock(StockAnalysisService.class));
        ReflectionTestUtils.setField(service, "technicalIndicatorService", mock(TechnicalIndicatorService.class));
        ReflectionTestUtils.setField(service, "priceHistoryCollecting", java.util.concurrent.ConcurrentHashMap.newKeySet());
        ReflectionTestUtils.setField(service, "lastStaleCollectKey", new java.util.concurrent.atomic.AtomicReference<>(""));
        StockPriceHistoryRepository repo = (StockPriceHistoryRepository) ReflectionTestUtils.getField(service, "priceHistoryRepository");
        List<StockPriceHistory> all = new ArrayList<>(bars("THIN", 0.23));
        all.addAll(bars("OK", 19.7));
        when(repo.findByStockCodesSince(anyList(), any())).thenReturn(all);

        Map<String, StockScore> map = new LinkedHashMap<>();
        map.put("THIN", new StockScore("THIN", "THIN"));
        map.put("OK", new StockScore("OK", "OK"));
        ReflectionTestUtils.invokeMethod(service, "scoreTechnical", map);

        assertThat(map.get("THIN").avgTradedValueEok).isEqualByComparingTo("0.23");
        assertThat(map.get("OK").avgTradedValueEok).isEqualByComparingTo("19.7");
    }

    /** 채점 단계를 대신 채운다 — 미달 둘(점수 최상위) + 통과 하나 + 판정 불가 하나. */
    private static void stubStages(RecommendationService service) {
        doAnswer(inv -> {
            Map<String, StockScore> m = inv.getArgument(0);
            m.put("007770", stock("007770", 20, 0.23));
            m.put("452160", stock("452160", 19, 0.24));
            m.put("036710", stock("036710", 15, 19.7));
            m.put("UNKNWN", stock("UNKNWN", 14, null));
            return 0;
        }).when(service).scoreAllStages(anyMap(), anyMap());
    }

    private static StockScore stock(String code, int technical, Double eok) {
        StockScore s = new StockScore(code, code);
        s.earnings = 20; s.supplyDemand = 10; s.technical = technical; s.sectorMomentum = 8;
        s.avgTradedValueEok = eok == null ? null : BigDecimal.valueOf(eok);
        return s;
    }

    @Test
    @DisplayName("calculate(): 55점을 넘는 미달 종목은 결과에 없고, 판정 불가는 남는다")
    void calculateExcludesBelowFloor() {
        RecommendationService service = baseService();
        stubStages(service);

        List<RecommendationDto> r = service.calculate();

        assertThat(r.stream().map(RecommendationDto::getStockCode).toList()).containsExactly("036710", "UNKNWN");
        assertThat(service.lastCalculationAllBelowLiquidityFloor()).isZero();
    }

    @Test
    @DisplayName("장중 스냅샷: 미달 종목은 저장·시그널 기록되지 않는다")
    @SuppressWarnings("unchecked")
    void intradaySnapshotDoesNotSaveOrRecordBelowFloor() {
        RecommendationService service = baseService();
        stubStages(service);
        StockPriceService prices = mock(StockPriceService.class);
        Map<String, StockPriceDto> quotes = new HashMap<>();
        for (String c : List.of("007770", "452160", "036710", "UNKNWN")) {
            StockPriceDto q = new StockPriceDto();
            q.setStockCode(c);
            q.setCurrentPrice(new BigDecimal("10000"));
            q.setFetchedAt(LocalDateTime.now());
            quotes.put(c, q);
        }
        when(prices.getStockPricesFromCacheOnly(anyList())).thenReturn(quotes);
        when(prices.getStockPrices(anyList())).thenReturn(quotes);
        ReflectionTestUtils.setField(service, "stockPriceService", prices);
        SignalOutcomeService outcomes = mock(SignalOutcomeService.class);
        ObjectProvider<SignalOutcomeService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(outcomes);
        ReflectionTestUtils.setField(service, "signalOutcomeProvider", provider);

        ReflectionTestUtils.invokeMethod(service, "saveSnapshotInternal", true);

        RecommendationSnapshotRepository snapshots =
                (RecommendationSnapshotRepository) ReflectionTestUtils.getField(service, "snapshotRepository");
        ArgumentCaptor<List<RecommendationSnapshot>> saved = ArgumentCaptor.forClass(List.class);
        verify(snapshots).saveAll(saved.capture());
        assertThat(saved.getValue().stream().map(RecommendationSnapshot::getStockCode).toList())
                .containsExactly("036710", "UNKNWN");
        verify(outcomes, never()).record(anyString(), eq("007770"), anyString(), any(), any(), any(), any(), any(), any());
        verify(outcomes, never()).record(anyString(), eq("452160"), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("재현: DB 에서 되살린 목록에도 하한을 다시 건다 — 미달은 빼고, 판정 불가는 남기고, 평균을 채운다")
    void restoredListIsReJudged() {
        RecommendationService service = baseService();
        LiquidityFloorService floor = mock(LiquidityFloorService.class);
        when(floor.assess(anyList())).thenReturn(Map.of(
                "007770", new LiquidityFloorService.Assessment(new BigDecimal("0.2311"), LiquidityFloor.Verdict.BELOW),
                "036710", new LiquidityFloorService.Assessment(new BigDecimal("19.7"), LiquidityFloor.Verdict.PASS),
                "UNKNWN", new LiquidityFloorService.Assessment(null, LiquidityFloor.Verdict.UNKNOWN)));
        ReflectionTestUtils.setField(service, "liquidityFloorService", floor);
        List<RecommendationDto> items = new ArrayList<>();
        for (String c : List.of("007770", "036710", "UNKNWN")) items.add(RecommendationDto.builder().stockCode(c).build());

        List<RecommendationDto> r = service.applyLiquidityFloorToRestored(items);

        assertThat(r.stream().map(RecommendationDto::getStockCode).toList()).containsExactly("036710", "UNKNWN");
        assertThat(r.get(0).getAvgTradedValueEok()).isEqualByComparingTo("19.70");
        assertThat(r.get(1).getAvgTradedValueEok()).isNull();
    }

    @Test
    @DisplayName("DB 복원: 봉 조회 실패(null)면 막지 않고 그대로")
    void restoredListKeptWhenJudgeFails() {
        RecommendationService service = baseService();
        LiquidityFloorService floor = mock(LiquidityFloorService.class);
        when(floor.assess(anyList())).thenReturn(null);
        ReflectionTestUtils.setField(service, "liquidityFloorService", floor);
        List<RecommendationDto> items = List.of(RecommendationDto.builder().stockCode("007770").build());
        assertThat(service.applyLiquidityFloorToRestored(items)).hasSize(1);
    }

    @Test
    @DisplayName("재현: 리스크 공시·실시간 검증 대상 선정에서 미달 종목이 자리를 먹지 않는다")
    void verificationTargetsSkipBelowFloor() {
        RecommendationService service = baseService();
        assertThat(service.notBelowLiquidityFloor(stock("A", 20, 0.23))).isFalse();
        assertThat(service.notBelowLiquidityFloor(stock("B", 20, 19.7))).isTrue();
        assertThat(service.notBelowLiquidityFloor(stock("C", 20, null))).isTrue();
        ReflectionTestUtils.setField(service, "minAvgTradedValueEok", 0.0);
        assertThat(service.notBelowLiquidityFloor(stock("A", 20, 0.23))).as("끄면 종전과 같다").isTrue();

        // 리스크 공시 검사: 상위 30 — 미달 종목은 검사 대상에서 빠진다
        ReflectionTestUtils.setField(service, "minAvgTradedValueEok", 5.0);
        RiskManagementService risk = mock(RiskManagementService.class);
        ReflectionTestUtils.setField(service, "riskManagementService", risk);
        Map<String, StockScore> map = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) map.put("T" + i, stock("T" + i, 20, 0.5));
        map.put("OK", stock("OK", 5, 19.7));   // 미달 30 개보다 점수가 낮다 — 예전엔 31위라 검사 밖
        ReflectionTestUtils.invokeMethod(service, "applyRiskPenalty", map);
        verify(risk).quickDangerCheck(eq("OK"), anyString());
        verify(risk, never()).quickDangerCheck(eq("T0"), anyString());
    }
}
