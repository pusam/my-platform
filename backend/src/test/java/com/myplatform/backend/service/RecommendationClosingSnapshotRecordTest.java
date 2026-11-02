package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 20:05 마감 스냅샷은 시그널을 기록하지 않는다(2026-11-03, 사용자 결정 (나)).
 *
 * <p>20:05 계산과 다음 거래일 장중 계산은 같은 일봉(그날 확정 봉)을 쓴다 — 장중엔 확정 전 봉을 저장하지
 * 않는다. 그래서 20:05 후보는 다음 거래일 장중 스냅샷에서 다시 채점돼 그때 기록되고, 20:05 에도 기록하면
 * 같은 신호가 이틀 치 표본으로 잡힌다. 20:05 의 가격(20:00 마감가)은 그 목록을 보고 살 수 있는 가격도 아니다.
 *
 * <p>고치기 전에도 20:05 기록은 9/1 이후 0건이었지만, 시세 캐시(5분)가 비어 있어서였을 뿐이다 — 20:00~20:05
 * 사이에 누가 그 종목 화면을 열어 시세가 받아져 있으면 그 종목만 기록됐다(관심에 따라 표본이 갈린다).
 */
class RecommendationClosingSnapshotRecordTest {
    static final String CODE = "003160";
    static final BigDecimal PRICE = new BigDecimal("12340");

    RecommendationService service;
    StockPriceService prices;
    SignalOutcomeService outcomes;
    RecommendationSnapshotRepository snapshots;

    @BeforeEach
    void setUp() {
        service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        prices = mock(StockPriceService.class);
        outcomes = mock(SignalOutcomeService.class);
        snapshots = mock(RecommendationSnapshotRepository.class);
        MarketCalendarService calendar = mock(MarketCalendarService.class);
        when(calendar.isMarketClosed()).thenReturn(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<SignalOutcomeService> outcomeProvider = mock(ObjectProvider.class);
        when(outcomeProvider.getIfAvailable()).thenReturn(outcomes);

        ReflectionTestUtils.setField(service, "stockPriceService", prices);
        ReflectionTestUtils.setField(service, "snapshotRepository", snapshots);
        ReflectionTestUtils.setField(service, "marketCalendar", calendar);
        ReflectionTestUtils.setField(service, "signalOutcomeProvider", outcomeProvider);
        // 11/2 20:05 실측 후보 — 디아이 58점(실적 19 · 수급 없음 · 기술 20 · 섹터 8)
        doReturn(List.of(RecommendationService.RecommendationDto.builder()
                .stockCode(CODE).stockName("디아이").totalScore(58)
                .aiStrategy(-1).earnings(19).supplyDemand(-1).technical(20).sectorMomentum(8)
                .valueStability(6).growth(-1).validCount(3).tags(List.of()).build()))
                .when(service).calculate();
    }

    /** 5분 안에 받아 둔 시세가 있다 — 20:00~20:05 사이에 누가 종목 화면을 열어 둔 경우. */
    private void warmPrice() {
        StockPriceDto quote = new StockPriceDto();
        quote.setStockCode(CODE);
        quote.setCurrentPrice(PRICE);
        quote.setChangeRate(new BigDecimal("4.12"));
        quote.setFetchedAt(LocalDateTime.now());
        when(prices.getStockPricesFromCacheOnly(anyList())).thenReturn(Map.of(CODE, quote));
    }

    @Test
    void closingSnapshotNeverRecordsSignalsEvenWhenAPriceHappensToBeCached() {
        warmPrice();

        service.saveClosingSnapshot();

        verify(snapshots).saveAll(anyList());   // 스냅샷(다음 날 아침 '오늘' 탭 목록)은 그대로 저장한다
        verify(outcomes, never()).record(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void intradaySnapshotStillRecordsAtTheCachedPrice() {
        warmPrice();

        service.saveIntradaySnapshot();

        verify(outcomes).record(eq("BUY"), eq(CODE), eq("디아이"), eq(58), eq(PRICE), eq(19), eq(-1), eq(20), eq(8));
    }

    @Test
    void intradaySnapshotWithoutAFreshPriceDoesNotRecord() {
        when(prices.getStockPricesFromCacheOnly(anyList())).thenReturn(Map.of());

        service.saveIntradaySnapshot();

        verify(snapshots).saveAll(anyList());
        verify(outcomes, never()).record(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }
}
