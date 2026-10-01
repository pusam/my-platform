package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockCatalystRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 종합판단 보드 '이력' 열 — 현재 산식 표본(경계·교정 D+3·최초 기록)만 센다(2026-10-01 감사).
 * 예전엔 90일 레거시 평가값을 중복 제거 없이 더해 "90일 실측"이라 표시했다(이전 산식 표본이 그대로 섞였다).
 */
class JudgmentBoardTrackRecordTest {

    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

    private JudgmentBoardService board(SignalOutcomeRepository repo, SignalOutcomeService signalService) {
        ObjectProvider<SignalOutcomeService> sp = mock(ObjectProvider.class);
        when(sp.getIfAvailable()).thenReturn(signalService);
        return new JudgmentBoardService(mock(RecommendationService.class), mock(ChartPatternClient.class),
                mock(SectorStockConfig.class), mock(OvernightUsMarketService.class), provider(),
                mock(StockCatalystRepository.class), mock(StockPriceService.class), mock(RvolService.class),
                repo, mock(InvestorBuyStreakService.class), mock(StockPriceHistoryRepository.class), sp);
    }

    @Test
    @DisplayName("표본 시작일 미정이면 이력 열을 채우지 않는다 — 레거시 90일 집계가 있어도")
    void unsetBoundaryLeavesTrackEmpty() {
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);
        when(repo.aggregateTrackRecordByCodes(any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{"005930", 5L, 4L, new BigDecimal("1.234")}));
        when(repo.findD3OkSince(any(), anyString())).thenReturn(List.of());
        SignalOutcomeService signals = SignalOutcomeCurrentSampleTest.service(repo, unset());

        Map<String, JudgmentBoardService.TrackRecord> track = board(repo, signals).loadTrackRecords(List.of("005930"));

        assertThat(track).isEmpty();
    }

    @Test
    @DisplayName("경계 이후 교정 D+3 기준으로 종목별 적중을 센다 — 같은 날 승격은 1건")
    void countsCurrentSampleOnly() {
        LocalDate b = LocalDate.now().minusDays(20);
        List<SignalOutcome> rows = List.of(
                SignalOutcomeCurrentSampleTest.row(1, "BUY", "005930", b.minusDays(2), 70, true, true, "4.00", b.minusDays(2).atTime(11, 30)),
                SignalOutcomeCurrentSampleTest.row(2, "BUY", "005930", b.plusDays(1), 70, true, false, "-2.00", b.plusDays(1).atTime(11, 30)),
                SignalOutcomeCurrentSampleTest.row(3, "BUY", "005930", b.plusDays(3), 70, false, true, "3.00", b.plusDays(3).atTime(11, 30)),
                SignalOutcomeCurrentSampleTest.row(4, "STRONG_BUY", "005930", b.plusDays(3), 76, true, true, "3.00", b.plusDays(3).atTime(14, 0)));
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);
        when(repo.aggregateTrackRecordByCodes(any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{"005930", 4L, 3L, new BigDecimal("3.000")}));
        when(repo.findD3OkSince(any(), anyString())).thenReturn(rows);
        ObjectProvider<SignalSampleBoundary> bp = mock(ObjectProvider.class);
        when(bp.getIfAvailable()).thenReturn(new SignalSampleBoundary(b.toString(), true));
        SignalOutcomeService signals = SignalOutcomeCurrentSampleTest.service(repo, bp);

        Map<String, JudgmentBoardService.TrackRecord> track = board(repo, signals).loadTrackRecords(List.of("005930"));

        JudgmentBoardService.TrackRecord t = track.get("005930");
        assertThat(t).isNotNull();
        assertThat(t.count()).isEqualTo(2);
        assertThat(t.hitCount()).isEqualTo(1);
        assertThat(t.avgAlpha()).isEqualByComparingTo("0.00");   // (-0.50 + 0.50) / 2
    }

    private static ObjectProvider<SignalSampleBoundary> unset() {
        ObjectProvider<SignalSampleBoundary> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(new SignalSampleBoundary("", false));
        return p;
    }
}
