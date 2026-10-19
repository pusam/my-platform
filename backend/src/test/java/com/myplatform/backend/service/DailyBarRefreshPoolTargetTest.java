package com.myplatform.backend.service;

import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.service.RecommendationService.StockScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 18:30 일봉 보정은 종합추천 후보 풀의 그날 확정 봉을 받는다(2026-10-19).
 *
 * <p>재현: 10/7 부터 마감 전 봉을 저장하지 않으면서({@code StockAnalysisService.isSettledBar}) 이 배치의 대상
 * ('그날 봉을 가진 종목')이 거의 비었다 — 예전엔 09:00 종합추천이 후보 풀 600여 종목의 형성 봉을 저장해 두었고 18:30 이 그걸
 * 확정값으로 덮었다. 이제 그날 확정 봉을 다음 날 09:00 까지 아무도 받지 않아 ① 09:00 계산(상승 가속 알림·아침 '오늘' 화면)은
 * 후보 대부분이 '봉 오래됨'으로 기술 점수 없이 매겨지고 ② 20:05 계산은 19:45 평가가 봉을 받아 둔 종목만 오늘 기준이라 기준일이 섞였다.
 */
class DailyBarRefreshPoolTargetTest {

    private static StockPriceHistoryRepository historyWith(List<String> codesWithBar) {
        StockPriceHistoryRepository repo = mock(StockPriceHistoryRepository.class);
        when(repo.findStockCodesByTradeDate(any())).thenReturn(codesWithBar);
        return repo;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RecommendationService> poolOf(String... codes) {
        RecommendationService rs = mock(RecommendationService.class);
        Map<String, StockScore> pool = new LinkedHashMap<>();
        for (String c : codes) pool.put(c, new StockScore(c, c));
        when(rs.categoryScoreSnapshot()).thenReturn(pool);
        ObjectProvider<RecommendationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rs);
        return provider;
    }

    private static DailyBarRefreshService service(StockPriceHistoryRepository repo, StockAnalysisService analysis,
                                                  ObjectProvider<RecommendationService> provider) {
        return new DailyBarRefreshService(repo, analysis, mock(SchedulerLockService.class), new MarketCalendarService(), provider);
    }

    @Test
    @DisplayName("재현: 그날 봉을 가진 종목이 없어도(마감 전 봉 미저장) 후보 풀 두 종목의 확정 봉을 받는다")
    void collectsCandidatePoolEvenWithoutSavedBars() {
        StockAnalysisService analysis = mock(StockAnalysisService.class);
        DailyBarRefreshService svc = service(historyWith(List.of()), analysis, poolOf("005930", "000660"));

        int done = svc.refreshBarsFor(LocalDate.of(2026, 10, 19));

        assertThat(done).isEqualTo(2);
        verify(analysis).collectPriceHistory("005930");
        verify(analysis).collectPriceHistory("000660");
    }

    @Test
    @DisplayName("후보 풀 ∪ 그날 봉을 가진 종목 — 겹치는 종목은 한 번만")
    void unionWithoutDuplicates() {
        StockAnalysisService analysis = mock(StockAnalysisService.class);
        DailyBarRefreshService svc = service(historyWith(List.of("000660", "035420")), analysis, poolOf("005930", "000660"));

        int done = svc.refreshBarsFor(LocalDate.of(2026, 10, 19));

        assertThat(done).isEqualTo(3);
        verify(analysis, times(1)).collectPriceHistory("000660");
        verify(analysis).collectPriceHistory("005930");
        verify(analysis).collectPriceHistory("035420");
    }

    @Test
    @DisplayName("재시작 뒤 계산 전이라 후보 풀이 없으면 종전대로 그날 봉을 가진 종목만")
    void noPoolFallsBackToSavedBars() {
        StockAnalysisService analysis = mock(StockAnalysisService.class);
        DailyBarRefreshService svc = service(historyWith(List.of("035420")), analysis, poolOf());

        int done = svc.refreshBarsFor(LocalDate.of(2026, 10, 19));

        assertThat(done).isEqualTo(1);
        verify(analysis).collectPriceHistory("035420");
    }

    @Test
    @DisplayName("후보 풀 조회가 실패해도 배치는 계속 — 그날 봉을 가진 종목은 받는다")
    @SuppressWarnings("unchecked")
    void poolFailureDoesNotStopBatch() {
        StockAnalysisService analysis = mock(StockAnalysisService.class);
        ObjectProvider<RecommendationService> broken = mock(ObjectProvider.class);
        when(broken.getIfAvailable()).thenThrow(new IllegalStateException("context closing"));
        DailyBarRefreshService svc = service(historyWith(List.of("035420")), analysis, broken);

        assertThat(svc.refreshBarsFor(LocalDate.of(2026, 10, 19))).isEqualTo(1);
        verify(analysis).collectPriceHistory("035420");
        verify(analysis, never()).collectPriceHistory("005930");
    }

    @Test
    @DisplayName("대상 목록(순수) — 풀 먼저, 빈 코드·중복 제거")
    void refreshTargetsIsOrderedUnion() {
        assertThat(DailyBarRefreshService.refreshTargets(List.of("A", "B", ""), List.of("B", "C", " ")))
                .containsExactly("A", "B", "C");
        assertThat(DailyBarRefreshService.refreshTargets(List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("대상이 하나도 없으면 KIS 를 부르지 않는다")
    void nothingToDo() {
        StockAnalysisService analysis = mock(StockAnalysisService.class);
        DailyBarRefreshService svc = service(historyWith(List.of()), analysis, poolOf());

        assertThat(svc.refreshBarsFor(LocalDate.of(2026, 10, 19))).isZero();
        verify(analysis, never()).collectPriceHistory(anyString());
    }
}
