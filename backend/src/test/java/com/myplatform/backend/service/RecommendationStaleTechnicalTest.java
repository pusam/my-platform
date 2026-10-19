package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.service.RecommendationService.StockScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 후보 대부분의 봉이 오래됐을 때의 첫 계산 — '관망'으로 발행하지 않고, 같은 종목을 두 번 받지 않는다(2026-10-19).
 *
 * <p>재현: 기술 점수 없이는 원점수가 최대 38점(실적 20 + 수급 상한 10 + 섹터 8) = 100점 환산 47점이라 55점 컷을 아무도 못 넘는다.
 * 10/7 '확정 봉만 저장' 뒤 18:30 보정이 비어 매 거래일 첫 계산(09:00)이 그 상태였고, 후보가 10종목 이상 채점됐으니 빈 결과가
 * '관망이 결론입니다'로 최대 30분 발행됐다 — 데이터가 아직 안 들어온 것을 시장 판단처럼 보였다(§4c). 서버가 며칠 꺼졌다 켜진
 * 첫날도 같다. 발행을 미루면 5분 가격 알림 크론이 getTop5 로 계산을 다시 부르는데, 계산마다 오래된 종목 수집 스레드를 새로
 * 띄워 같은 600여 종목을 KIS 에서 거듭 받았다(StockAnalysisService 의 중복 방지는 '지금 받는 중인 한 종목'만 막는다).
 */
class RecommendationStaleTechnicalTest {

    private static final MarketCalendarService CALENDAR = new MarketCalendarService();

    @Test
    @DisplayName("재현: 후보 40 중 30 이 봉 노후로 기술 미채점이면 빈 결과를 '관망'으로 발행하지 않는다")
    void staleHeavyEmptyResultIsNotPublished() {
        assertThat(RecommendationService.shouldPublishEmptyResult(40, 30)).isFalse();
        assertThat(RecommendationService.shouldPublishEmptyResult(40, 20)).as("절반이면 판단 보류").isFalse();
        assertThat(RecommendationService.shouldPublishEmptyResult(40, 5)).as("대부분 채점됐으면 정상 '관망'").isTrue();
        assertThat(RecommendationService.shouldPublishEmptyResult(9, 0)).as("후보 빈약 — 종전 규칙").isFalse();
    }

    @Test
    @DisplayName("기술 채점은 봉 노후로 건너뛴 후보 수를 남긴다")
    void scoreTechnicalCountsStaleCandidates() {
        RecommendationService service = service(mock(StockAnalysisService.class));
        LocalDate stale = CALENDAR.minusTradingDays(LocalDate.now(), 5);
        when(repo(service).findByStockCodesSince(anyList(), any())).thenReturn(List.of(bar("A", stale), bar("B", stale)));
        Map<String, StockScore> map = new LinkedHashMap<>();
        map.put("A", new StockScore("A", "A"));
        map.put("B", new StockScore("B", "B"));
        map.put("C", new StockScore("C", "C"));   // 봉 없음 — 노후와 같다

        ReflectionTestUtils.invokeMethod(service, "scoreTechnical", map);

        assertThat((int) ReflectionTestUtils.getField(service, "lastTechnicalStale")).isEqualTo(3);
    }

    @Test
    @DisplayName("재현: 앞 계산이 예약한 수집이 끝나기 전에 다시 계산해도 같은 종목을 또 받지 않는다")
    void staleCollectionIsNotScheduledTwice() throws Exception {
        StockAnalysisService analysis = mock(StockAnalysisService.class);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> { release.await(5, TimeUnit.SECONDS); return null; }).when(analysis).collectPriceHistory("A");
        RecommendationService service = service(analysis);
        when(repo(service).findByStockCodesSince(anyList(), any())).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(service, "scoreTechnical", new LinkedHashMap<>(Map.of("A", new StockScore("A", "A"))));
        verify(analysis, timeout(2000)).collectPriceHistory("A");   // 첫 수집이 진행 중
        ReflectionTestUtils.invokeMethod(service, "scoreTechnical", new LinkedHashMap<>(Map.of("A", new StockScore("A", "A"))));
        Thread.sleep(300);
        release.countDown();

        verify(analysis, timeout(2000).times(1)).collectPriceHistory("A");
        Thread.sleep(300);
        verify(analysis, times(1)).collectPriceHistory("A");
    }

    @Test
    @DisplayName("수집이 끝난 종목은 다음 계산에서 다시 예약할 수 있다(영구히 막히지 않는다)")
    void collectionCanBeScheduledAgainAfterItEnds() {
        StockAnalysisService analysis = mock(StockAnalysisService.class);
        RecommendationService service = service(analysis);
        when(repo(service).findByStockCodesSince(anyList(), any())).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(service, "scoreTechnical", new LinkedHashMap<>(Map.of("A", new StockScore("A", "A"))));
        verify(analysis, timeout(2000).times(1)).collectPriceHistory("A");
        waitUntilIdle(service);
        ReflectionTestUtils.invokeMethod(service, "scoreTechnical", new LinkedHashMap<>(Map.of("A", new StockScore("A", "A"))));

        verify(analysis, timeout(2000).times(2)).collectPriceHistory("A");
    }

    @Test
    @DisplayName("수집 결과 요약은 실제로 최신 봉이 생긴 종목 수로 센다 — 예외를 삼키는 수집기라 '예외 0' 은 늘 0 이었다")
    void freshCountReflectsSavedBars() {
        LocalDate cutoff = LocalDate.of(2026, 10, 16);
        Map<String, LocalDate> latest = Map.of("A", LocalDate.of(2026, 10, 16), "B", LocalDate.of(2026, 10, 14));
        assertThat(RecommendationService.countFresh(List.of("A", "B", "C"), latest, cutoff)).isEqualTo(1);
        assertThat(RecommendationService.countFresh(List.of("A", "B"), latest, null)).as("달력 실패 — 봉이 있으면 센다").isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private static StockPriceHistory bar(String code, LocalDate date) {
        return StockPriceHistory.builder().stockCode(code).tradeDate(date).closePrice(new BigDecimal("1000"))
                .changeRate(BigDecimal.ONE).build();
    }

    private static RecommendationService service(StockAnalysisService analysis) {
        RecommendationService service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "priceHistoryRepository", mock(StockPriceHistoryRepository.class));
        ReflectionTestUtils.setField(service, "stockAnalysisService", analysis);
        ReflectionTestUtils.setField(service, "marketCalendar", CALENDAR);
        ReflectionTestUtils.setField(service, "priceHistoryCollecting", java.util.concurrent.ConcurrentHashMap.newKeySet());
        return service;
    }

    private static StockPriceHistoryRepository repo(RecommendationService service) {
        return (StockPriceHistoryRepository) ReflectionTestUtils.getField(service, "priceHistoryRepository");
    }

    @SuppressWarnings("unchecked")
    private static void waitUntilIdle(RecommendationService service) {
        java.util.Set<String> inFlight = (java.util.Set<String>) ReflectionTestUtils.getField(service, "priceHistoryCollecting");
        long until = System.currentTimeMillis() + 2000;
        while (!inFlight.isEmpty() && System.currentTimeMillis() < until) Thread.onSpinWait();
    }
}
