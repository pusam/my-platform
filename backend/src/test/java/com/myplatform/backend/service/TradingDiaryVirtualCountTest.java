package com.myplatform.backend.service;

import com.myplatform.backend.entity.VirtualTradeHistory;
import com.myplatform.backend.entity.WeeklyTradingReport;
import com.myplatform.backend.repository.TradingAuditLogRepository;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import com.myplatform.backend.repository.WeeklyTradingReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 모의(VIRTUAL) 주간 리포트의 매수·매도 횟수 — 체결 기록에서 센다(2026-10-03).
 *
 * <p>재현: 모의 매매는 주문 감사 기록(trading_audit_log)을 남기지 않는데 횟수를 감사 기록에서만 셌다 → 거래가 있는 주도
 * "매수 0회·매도 0회"였고, 10/2 에 넣은 거래 없는 주 처리(AI 생략)와 겹쳐 리포트 본문이 "체결된 매매가 없습니다"가 됐다.
 */
class TradingDiaryVirtualCountTest {

    private static VirtualTradeHistory row(String type, String amount, String pnl, LocalDateTime at) {
        return VirtualTradeHistory.builder()
                .accountId(1L).stockCode("005930").stockName("삼성전자")
                .tradeType(type).quantity(10).price(new BigDecimal("70000"))
                .totalAmount(new BigDecimal(amount))
                .profitLoss(pnl == null ? null : new BigDecimal(pnl))
                .tradeReason("SELL".equals(type) ? "TAKE_PROFIT" : "AUTO_BUY")
                .tradeDate(at)
                .build();
    }

    @Test
    @DisplayName("재현: 감사 기록이 없는 모의 주라도 체결 기록의 매수·매도를 센다 — '체결된 매매가 없습니다'가 아니다")
    void virtualWeekCountsTradesFromHistory() {
        TradingAuditLogRepository auditRepo = mock(TradingAuditLogRepository.class);
        VirtualTradeHistoryRepository historyRepo = mock(VirtualTradeHistoryRepository.class);
        WeeklyTradingReportRepository reportRepo = mock(WeeklyTradingReportRepository.class);
        GeminiService gemini = mock(GeminiService.class);
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);

        LocalDate start = LocalDate.of(2026, 7, 20);
        LocalDate end = LocalDate.of(2026, 7, 26);
        when(historyRepo.findVirtualBetween(anyLong(), any(), any())).thenReturn(List.of(
                row("BUY", "700000", null, LocalDateTime.of(2026, 7, 21, 9, 5)),
                row("SELL", "735000", "33000", LocalDateTime.of(2026, 7, 22, 10, 30))));
        when(reportRepo.findFirstByWeekStartAndWeekEndAndModeOrderByCreatedAtDesc(any(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gemini.generateWeeklyTradingReport(anyString())).thenReturn("분석 본문");

        TradingDiaryService service = new TradingDiaryService(auditRepo, historyRepo, reportRepo, gemini, telegram);
        WeeklyTradingReport saved = service.generateForWeek(start, end, "VIRTUAL", "test");

        assertThat(saved.getTotalBuys()).isEqualTo(1);
        assertThat(saved.getTotalSells()).isEqualTo(1);
        assertThat(saved.getTotalBuyAmount()).isEqualByComparingTo("700000");
        assertThat(saved.getTotalSellAmount()).isEqualByComparingTo("735000");
        assertThat(saved.getAiReport()).doesNotContain("체결된 매매가 없습니다");
        verify(gemini).generateWeeklyTradingReport(anyString());
    }

    @Test
    @DisplayName("실전은 종전대로 감사 기록에서 센다 — 체결 기록을 한 번 더 세지 않는다(같은 거래 이중 계산 방지)")
    void realModeStillCountsFromAuditOnly() {
        TradingDiaryService.Stats stats = TradingDiaryService.aggregate("REAL", List.of(),
                List.of(row("BUY", "700000", null, LocalDateTime.of(2026, 7, 21, 9, 5))));

        assertThat(stats.totalBuys()).isZero();
        assertThat(stats.totalSells()).isZero();
    }

    @Test
    @DisplayName("모의 매수 시간대 분포도 체결 기록 시각으로")
    void virtualBuyHourFromHistory() {
        TradingDiaryService.Stats stats = TradingDiaryService.aggregate("VIRTUAL", List.of(), List.of(
                row("BUY", "700000", null, LocalDateTime.of(2026, 7, 21, 9, 5)),
                row("BUY", "100000", null, LocalDateTime.of(2026, 7, 21, 9, 40))));

        assertThat(stats.buyCountByHour()).containsEntry(9, 2);
        assertThat(stats.totalBuyAmount()).isEqualByComparingTo("800000");
    }
}
