package com.myplatform.backend.service;

import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
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
 * 일봉 보정은 KIS 가 봉을 돌려준 종목만 '받음'으로 센다 — 실패는 한 번 더 시도하고, 그래도 실패면 그 수를 말한다(2026-11-19).
 *
 * <p>재현: 11/19 아침 07:40 갱신에서 KIS 가 '호출 후처리(MCI전송) 오류'를 섞어 줘 462종목 중 144종목이 실패했는데
 * 완료 로그는 '462종목 재수집'이었다. 실패한 종목은 11/18 저녁 값(KRX 종가가 아님)이 그대로 남아, 10:20 외부 대조에서
 * 처음으로 종가 불일치 9건이 나왔다(9건 모두 아침에 실패한 종목). 그날 기술 채점도 그 값을 '전일 종가'로 읽었다.
 */
class DailyBarRefreshFailureCountTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 18);

    @SuppressWarnings("unchecked")
    private static DailyBarRefreshService service(StockAnalysisService analysis, List<String> codes) {
        StockPriceHistoryRepository repo = mock(StockPriceHistoryRepository.class);
        when(repo.findStockCodesByTradeDate(any())).thenReturn(codes);
        RecommendationService rs = mock(RecommendationService.class);
        when(rs.categoryScoreSnapshot()).thenReturn(Map.of());
        ObjectProvider<RecommendationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rs);
        DailyBarRefreshService svc = new DailyBarRefreshService(repo, analysis, mock(SchedulerLockService.class),
                new MarketCalendarService(), provider);
        svc.retryPauseMs = 0;
        return svc;
    }

    @Test
    @DisplayName("재현: 끝까지 못 받은 종목은 '받음'에 넣지 않는다")
    void persistentFailureIsNotCounted() {
        StockAnalysisService analysis = mock(StockAnalysisService.class);
        when(analysis.tryCollectPriceHistory("005930")).thenReturn(true);
        when(analysis.tryCollectPriceHistory("092870")).thenReturn(false);

        int done = service(analysis, List.of("005930", "092870")).refreshBarsFor(DAY);

        assertThat(done).isEqualTo(1);
        verify(analysis, times(2)).tryCollectPriceHistory("092870");   // 처음 + 한 번 더
        verify(analysis, times(1)).tryCollectPriceHistory("005930");   // 받은 종목은 다시 부르지 않는다
    }

    @Test
    @DisplayName("처음 실패한 종목은 한 번 더 시도해 받으면 '받음'으로 센다")
    void retryRecoversTransientFailure() {
        StockAnalysisService analysis = mock(StockAnalysisService.class);
        when(analysis.tryCollectPriceHistory("005930")).thenReturn(true);
        when(analysis.tryCollectPriceHistory("092870")).thenReturn(false, true);

        int done = service(analysis, List.of("005930", "092870")).refreshBarsFor(DAY);

        assertThat(done).isEqualTo(2);
        verify(analysis, times(2)).tryCollectPriceHistory("092870");
    }

    @Test
    @DisplayName("다시 시도는 한 번뿐 — KIS 를 계속 두드리지 않는다")
    void retriesOnlyOnce() {
        StockAnalysisService analysis = mock(StockAnalysisService.class);
        when(analysis.tryCollectPriceHistory(anyString())).thenReturn(false);

        int done = service(analysis, List.of("A00001", "A00002")).refreshBarsFor(DAY);

        assertThat(done).isZero();
        verify(analysis, times(2)).tryCollectPriceHistory("A00001");
        verify(analysis, times(2)).tryCollectPriceHistory("A00002");
    }

    @Test
    @DisplayName("전부 받으면 다시 시도하지 않는다")
    void noRetryWhenAllSucceed() {
        StockAnalysisService analysis = mock(StockAnalysisService.class);
        when(analysis.tryCollectPriceHistory(anyString())).thenReturn(true);

        assertThat(service(analysis, List.of("005930", "000660")).refreshBarsFor(DAY)).isEqualTo(2);
        verify(analysis, times(1)).tryCollectPriceHistory("005930");
        verify(analysis, times(1)).tryCollectPriceHistory("000660");
        verify(analysis, never()).collectPriceHistory(anyString());
    }
}
