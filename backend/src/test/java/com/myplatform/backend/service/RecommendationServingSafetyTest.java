package com.myplatform.backend.service;

import com.myplatform.backend.controller.RecommendationController;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RecommendationServingSafetyTest {
    RecommendationService service;
    StockStatusService status;
    RecommendationSnapshotRepository snapshots;

    @BeforeEach
    void setUp() {
        service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        status = mock(StockStatusService.class);
        snapshots = mock(RecommendationSnapshotRepository.class);
        StockPriceService prices = mock(StockPriceService.class);
        when(prices.getStockPricesFromCacheOnly(anyList())).thenReturn(Map.of());
        when(status.isActive("005930")).thenReturn(true);
        when(status.isActive("294090")).thenReturn(false);
        ReflectionTestUtils.setField(service, "stockStatusService", status);
        ReflectionTestUtils.setField(service, "snapshotRepository", snapshots);
        ReflectionTestUtils.setField(service, "stockPriceService", prices);
        doReturn(CompletableFuture.completedFuture(List.of())).when(service).startOrJoinCalculation();
    }

    @ParameterizedTest
    @CsvSource({
        "getTop5,cachedTop5,cacheTime",
        "getValueTop10,cachedValueTop10,valueCacheTime",
        "getGrowthTop10,cachedGrowthTop10,growthCacheTime",
        "getOversoldTop10,cachedOversoldTop10,oversoldCacheTime",
        "getEarningsTop10,cachedEarningsTop10,earningsCacheTime",
        "getSmartMoneyTop10,cachedSmartMoneyTop10,smartMoneyCacheTime"
    })
    void cacheMustRecheckCurrentTradingStatus(String method, String cache, String time) {
        var items = List.of(candidate("294090"), candidate("005930"));
        ReflectionTestUtils.setField(service, cache, items);
        ReflectionTestUtils.setField(service, time, LocalDateTime.now());
        RecommendationService.Top5Response result = ReflectionTestUtils.invokeMethod(service, method);
        assertThat(result.getItems()).extracting(RecommendationService.RecommendationDto::getStockCode)
                .containsExactly("005930");
        assertThat(items).hasSize(2); // immutable shared cache is not edited by a reader
    }

    @Test
    void databaseSnapshotMustAlsoRecheckCurrentTradingStatus() {
        RecommendationSnapshot row = new RecommendationSnapshot();
        row.setStockCode("294090");
        row.setStockName("이오플로우");
        row.setTotalScore(80);
        row.setSnapshotAt(LocalDateTime.now().minusDays(1));
        row.setEarnings(20); row.setSupplyDemand(20); row.setTechnical(20); row.setSectorMomentum(20);
        when(snapshots.findLatestSnapshot()).thenReturn(List.of(row));
        assertThat(service.getTop5().getItems()).isEmpty();
    }

    @Test
    void unavailableInputsMustNotBecomeSuccessfulStandAsideAdvice() {
        when(snapshots.findLatestSnapshot()).thenThrow(new IllegalStateException("database unavailable"));
        var controller = new RecommendationController(service, mock(JudgmentBoardService.class));
        Map<?, ?> body = (Map<?, ?>) controller.getTop5().getBody();
        assertThat(body.get("success")).isEqualTo(false);
    }

    private RecommendationService.RecommendationDto candidate(String code) {
        return RecommendationService.RecommendationDto.builder()
                .stockCode(code).stockName(code).totalScore(80).tags(List.of()).build();
    }

    @ParameterizedTest
    @CsvSource({"getValueTop10", "getGrowthTop10", "getOversoldTop10"})
    void failedTrackWithoutFallbackMustBeUnavailable(String method) {
        // No input repositories are available: a failing calculation cannot certify zero candidates.
        RecommendationService.Top5Response result = ReflectionTestUtils.invokeMethod(service, method);
        assertThat(result.isDataAvailable()).isFalse();
    }
}
