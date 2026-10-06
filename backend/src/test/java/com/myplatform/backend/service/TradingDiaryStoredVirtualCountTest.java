package com.myplatform.backend.service;

import com.myplatform.backend.entity.VirtualTradeHistory;
import com.myplatform.backend.entity.WeeklyTradingReport;
import com.myplatform.backend.repository.TradingAuditLogRepository;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import com.myplatform.backend.repository.WeeklyTradingReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 저장된 모의 주간 리포트의 매수·매도 횟수(2026-10-06 화면 점검).
 *
 * <p>재현: 10/3 이전에 만든 모의 주차 행은 횟수·금액을 감사 기록에서만 세어 0 으로 저장됐다 — 손익·승패는 체결 기록이라
 * 맞아서 매매 탭 '최근 12주 히스토리'가 "7/20~7/26 · −403,912원 · 매수/매도 0 / 0 · 승률 43.75%"처럼 서로 모순됐다.
 * 조회할 때 그런 행만 같은 체결 기록으로 다시 센다. 저장 행은 고치지 않는다.
 */
class TradingDiaryStoredVirtualCountTest {

    private VirtualTradeHistoryRepository historyRepo;
    private WeeklyTradingReportRepository reportRepo;
    private TradingDiaryService service;

    @BeforeEach
    void setUp() {
        historyRepo = mock(VirtualTradeHistoryRepository.class);
        reportRepo = mock(WeeklyTradingReportRepository.class);
        service = new TradingDiaryService(mock(TradingAuditLogRepository.class), historyRepo, reportRepo,
                mock(GeminiService.class), mock(TelegramNotificationService.class));
    }

    private static WeeklyTradingReport stored(String mode, LocalDate start, int buys, int sells,
                                              int wins, int losses, String pnl) {
        WeeklyTradingReport r = new WeeklyTradingReport();
        r.setMode(mode);
        r.setWeekStart(start);
        r.setWeekEnd(start.plusDays(6));
        r.setTotalBuys(buys);
        r.setTotalSells(sells);
        r.setWinCount(wins);
        r.setLossCount(losses);
        r.setRealizedPnl(new BigDecimal(pnl));
        return r;
    }

    private static VirtualTradeHistory trade(String type, String amount, String pnl, LocalDateTime at) {
        return VirtualTradeHistory.builder()
                .accountId(4L).stockCode("017670").stockName("SK텔레콤")
                .tradeType(type).quantity(46).price(new BigDecimal("99500"))
                .totalAmount(new BigDecimal(amount))
                .profitLoss(pnl == null ? null : new BigDecimal(pnl))
                .tradeReason("SELL".equals(type) ? "REGULAR_SESSION_LIQUIDATION" : "SWING_BUY_INSTITUTION")
                .tradeDate(at)
                .build();
    }

    @Test
    @DisplayName("재현: 손익·승패는 있는데 횟수가 0 인 모의 행 — 같은 주 체결 기록으로 매수·매도 횟수와 금액을 다시 센다")
    void storedVirtualRowWithMissingCountsIsRecounted() {
        LocalDate start = LocalDate.of(2026, 7, 27);
        WeeklyTradingReport row = stored("VIRTUAL", start, 0, 0, 0, 2, "-83588");
        when(reportRepo.findTop12ByModeOrderByWeekStartDesc("VIRTUAL")).thenReturn(new ArrayList<>(List.of(row)));
        when(historyRepo.findVirtualBetween(anyLong(), eq(start.atStartOfDay()), eq(start.plusDays(7).atStartOfDay())))
                .thenReturn(List.of(
                        trade("BUY", "4577000", null, LocalDateTime.of(2026, 7, 27, 14, 0)),
                        trade("BUY", "4614400", null, LocalDateTime.of(2026, 7, 27, 14, 0)),
                        trade("SELL", "4503400", "-81722", LocalDateTime.of(2026, 7, 27, 15, 20)),
                        trade("SELL", "4620800", "-1866", LocalDateTime.of(2026, 7, 27, 15, 20))));

        WeeklyTradingReport shown = service.getRecent("VIRTUAL").get(0);

        assertThat(shown.getTotalBuys()).isEqualTo(2);
        assertThat(shown.getTotalSells()).isEqualTo(2);
        assertThat(shown.getTotalBuyAmount()).isEqualByComparingTo("9191400");
        assertThat(shown.getTotalSellAmount()).isEqualByComparingTo("9124200");
        // 손익·승패는 원래 맞았다 — 저장값 그대로
        assertThat(shown.getRealizedPnl()).isEqualByComparingTo("-83588");
        assertThat(shown.getLossCount()).isEqualTo(2);
        verify(reportRepo, never()).save(any());
    }

    @Test
    @DisplayName("최신 리포트 카드도 같은 규칙")
    void latestUsesSameRule() {
        LocalDate start = LocalDate.of(2026, 7, 27);
        when(reportRepo.findFirstByModeOrderByWeekStartDesc("VIRTUAL"))
                .thenReturn(Optional.of(stored("VIRTUAL", start, 0, 0, 0, 1, "-1866")));
        when(historyRepo.findVirtualBetween(anyLong(), any(), any())).thenReturn(List.of(
                trade("BUY", "4614400", null, LocalDateTime.of(2026, 7, 27, 14, 0)),
                trade("SELL", "4620800", "-1866", LocalDateTime.of(2026, 7, 27, 15, 20))));

        WeeklyTradingReport shown = service.getLatest("VIRTUAL").orElseThrow();

        assertThat(shown.getTotalBuys()).isEqualTo(1);
        assertThat(shown.getTotalSells()).isEqualTo(1);
    }

    @Test
    @DisplayName("거래 없는 주(손익·승패도 0)와 횟수가 이미 있는 행은 다시 세지 않는다")
    void consistentRowsAreLeftAlone() {
        WeeklyTradingReport empty = stored("VIRTUAL", LocalDate.of(2026, 9, 28), 0, 0, 0, 0, "0");
        WeeklyTradingReport counted = stored("VIRTUAL", LocalDate.of(2026, 9, 21), 3, 3, 2, 1, "12000");
        when(reportRepo.findTop12ByModeOrderByWeekStartDesc("VIRTUAL"))
                .thenReturn(new ArrayList<>(List.of(empty, counted)));

        List<WeeklyTradingReport> shown = service.getRecent("VIRTUAL");

        assertThat(shown.get(0).getTotalBuys()).isZero();
        assertThat(shown.get(1).getTotalBuys()).isEqualTo(3);
        verify(historyRepo, never()).findVirtualBetween(anyLong(), any(), any());
    }

    @Test
    @DisplayName("실전 행은 감사 기록이 출처라 건드리지 않는다")
    void realRowsAreLeftAlone() {
        when(reportRepo.findTop12ByModeOrderByWeekStartDesc("REAL"))
                .thenReturn(new ArrayList<>(List.of(stored("REAL", LocalDate.of(2026, 7, 27), 0, 0, 0, 1, "-500"))));

        assertThat(service.getRecent("REAL").get(0).getTotalSells()).isZero();
        verify(historyRepo, never()).findVirtualBetween(anyLong(), any(), any());
    }

    @Test
    @DisplayName("그 주 체결 기록을 못 찾으면 고칠 근거가 없다 — 저장값 그대로")
    void noHistoryMeansNoRepair() {
        when(reportRepo.findTop12ByModeOrderByWeekStartDesc("VIRTUAL"))
                .thenReturn(new ArrayList<>(List.of(stored("VIRTUAL", LocalDate.of(2026, 7, 27), 0, 0, 0, 2, "-83588"))));
        when(historyRepo.findVirtualBetween(anyLong(), any(), any())).thenReturn(List.of());

        WeeklyTradingReport shown = service.getRecent("VIRTUAL").get(0);

        assertThat(shown.getTotalBuys()).isZero();
        assertThat(shown.getTotalSells()).isZero();
    }
}
