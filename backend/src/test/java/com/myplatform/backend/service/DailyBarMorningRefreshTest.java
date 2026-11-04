package com.myplatform.backend.service;

import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 아침 일봉 재수집 — 장이 열리는 날 첫 계산 전에 전 거래일 봉을 KRX 종가로 바꾼다(2026-11-04).
 *
 * <p>재현: 18:30 에 받은 그날 봉은 KRX 종가가 아니다. 11/3 삼성전자는 15:39 통합 시세·다음 날 기준가·야후가 모두 219,500 인데
 * 저녁에 받은 봉은 222,000 이었다(SK하이닉스 1,398,000 vs 1,414,000, 014710 은 9,400 vs 9,110 · 거래량 1,663 vs 969).
 * 다음 날 다시 받으면 KRX 종가로 바뀌지만 그 '다음 날'이 18:30 이라, 하루 내내 11:30·14:00·17:00 기술 채점이 저녁 값을 '전일 종가'로 읽었다.
 * KIS 종목마스터(매일 07:35 생성)에 기준가·전일거래량 최종값이 들어 있어 그 뒤로 잡았다.
 */
class DailyBarMorningRefreshTest {

    private static final LocalDate LAST_CLOSED = LocalDate.of(2026, 11, 4);

    private record Rig(DailyBarRefreshService svc, StockAnalysisService analysis, StockPriceHistoryRepository repo) {}

    @SuppressWarnings("unchecked")
    private static Rig rig(boolean closedToday, boolean enabled) {
        var repo = mock(StockPriceHistoryRepository.class);
        when(repo.findStockCodesByTradeDate(LAST_CLOSED)).thenReturn(List.of("005930", "000660"));
        var analysis = mock(StockAnalysisService.class);
        var lock = mock(SchedulerLockService.class);
        when(lock.tryLock(anyString(), any())).thenReturn(true);
        var calendar = mock(MarketCalendarService.class);
        when(calendar.isMarketClosed()).thenReturn(closedToday);
        when(calendar.lastClosedTradingDay(any())).thenReturn(LAST_CLOSED);
        when(calendar.minusTradingDays(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(LAST_CLOSED.minusDays(1));
        RecommendationService rs = mock(RecommendationService.class);
        when(rs.categoryScoreSnapshot()).thenReturn(Map.of());
        ObjectProvider<RecommendationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rs);
        var svc = new DailyBarRefreshService(repo, analysis, lock, calendar, provider);
        ReflectionTestUtils.setField(svc, "enabled", enabled);
        return new Rig(svc, analysis, repo);
    }

    @Test
    @DisplayName("재현: 장이 열리는 날 아침에 전 거래일 봉을 가진 종목을 다시 받는다 — 저녁에 받은 값을 KRX 종가로 바꾼다")
    void refreshesLastClosedDayBars() {
        Rig g = rig(false, true);

        g.svc().refreshBarsBeforeOpen();

        verify(g.repo()).findStockCodesByTradeDate(LAST_CLOSED);
        verify(g.analysis()).collectPriceHistory("005930");
        verify(g.analysis()).collectPriceHistory("000660");
    }

    @Test
    @DisplayName("장이 닫힌 날은 돌지 않는다 — 다음 거래일 아침이 받는다")
    void skipsOnClosedDay() {
        Rig g = rig(true, true);

        g.svc().refreshBarsBeforeOpen();

        verify(g.analysis(), never()).collectPriceHistory(anyString());
    }

    @Test
    @DisplayName("끈 상태(daily-bar.refresh.enabled=false)면 돌지 않는다")
    void respectsFlag() {
        Rig g = rig(false, false);

        g.svc().refreshBarsBeforeOpen();

        verify(g.analysis(), never()).collectPriceHistory(anyString());
    }

    @Test
    @DisplayName("시각: KIS 마스터 생성(07:35) 뒤, 08:00 전 — 초 필드는 :00 을 피한다(§4b)")
    void cronTiming() throws Exception {
        Scheduled s = DailyBarRefreshService.class.getMethod("refreshBarsBeforeOpen").getAnnotation(Scheduled.class);

        assertThat(s).isNotNull();
        String[] f = s.cron().split(" ");
        assertThat(f[0]).isNotEqualTo("0");
        assertThat(Integer.parseInt(f[2])).isEqualTo(7);
        assertThat(Integer.parseInt(f[1])).isGreaterThanOrEqualTo(36);
        assertThat(f[5]).isEqualTo("MON-FRI");
    }

    @Test
    @DisplayName("고친 봉이 거의 없으면 경고한다 — KIS 값이 아직 안 바뀐 시각일 수 있어서다(조용히 넘기면 이 배치가 헛도는 걸 모른다)")
    void warnsWhenAlmostNothingChanged() {
        assertThat(DailyBarRefreshService.tooFewCorrected(650, 3)).isTrue();
        assertThat(DailyBarRefreshService.tooFewCorrected(650, 500)).isFalse();
        assertThat(DailyBarRefreshService.tooFewCorrected(0, 0)).as("대상이 없으면 경고할 일도 없다").isFalse();
    }
}
