package com.myplatform.backend.scheduler;

import com.myplatform.backend.dto.PaperTradingDto.AccountSummaryDto;
import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.repository.AiStrategySnapshotRepository;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.SchedulerLockService;
import com.myplatform.backend.service.TelegramNotificationService;
import com.myplatform.backend.service.VirtualTradeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 16:00 일일 리포트 — 소수 자리를 줄일 때 반올림 방식을 주지 않아 'Rounding necessary' 로 터졌다
 * (2026-11-03 운영 로그). 승률은 소수 둘째 자리로 계산되는데 리포트는 첫째 자리로 줄이므로 끝자리가 0 이 아닌
 * 한 매일 실패했고, 그 실패가 '포트폴리오 데이터 없음'으로 보여 보유 종목이 텔레그램에서 빠졌다(§4c).
 */
class DailyReportRoundingTest {

    private final VirtualTradeService virtualTrade = mock(VirtualTradeService.class);
    private final AiStrategySnapshotRepository snapshots = mock(AiStrategySnapshotRepository.class);

    private final DailyReportScheduler scheduler = new DailyReportScheduler(virtualTrade, snapshots,
            mock(TelegramNotificationService.class), mock(SchedulerLockService.class),
            mock(MarketCalendarService.class));

    private AccountSummaryDto summary(String winRate, String totalRate) {
        return AccountSummaryDto.builder()
                .currentBalance(new BigDecimal("9000000"))
                .totalInvested(new BigDecimal("150000"))
                .totalEvaluation(new BigDecimal("148000"))
                .totalProfitLoss(new BigDecimal("-802000"))
                .totalProfitRate(new BigDecimal(totalRate))
                .totalTradeCount(114L).winCount(56L).loseCount(58L)
                .winRate(new BigDecimal(winRate))
                .build();
    }

    private PortfolioItemDto holding(String rate) {
        return PortfolioItemDto.builder()
                .stockCode("005930").stockName("삼성전자")
                .currentPrice(new BigDecimal("74000"))
                .profitRate(new BigDecimal(rate))
                .build();
    }

    @Test
    @DisplayName("승률·수익률 소수 자리가 길어도 보유 종목까지 리포트에 나간다 — 예전엔 'Rounding necessary'")
    void longDecimalsDoNotDropHoldings() {
        when(virtualTrade.getAccountSummary()).thenReturn(summary("49.12", "-8.0200"));
        when(virtualTrade.getPortfolio()).thenReturn(List.of(holding("-1.3345")));
        when(snapshots.findLatestByStrategyType(any())).thenReturn(List.of());

        String report = scheduler.buildDailyReport();

        assertThat(report).contains("승률: 49.1%").contains("(56승 58패)")
                .contains("(-8.02%)")
                .contains("보유 종목 (1개)").contains("삼성전자").contains("(-1.33%)")
                .doesNotContain("포트폴리오 조회 실패");
    }

    @Test
    @DisplayName("계좌는 모의투자라고 적는다 — 실전 계좌로 읽히지 않게")
    void accountIsLabelledAsPaperTrading() {
        when(virtualTrade.getAccountSummary()).thenReturn(summary("50.00", "0.00"));
        when(virtualTrade.getPortfolio()).thenReturn(List.of());
        when(snapshots.findLatestByStrategyType(any())).thenReturn(List.of());

        assertThat(scheduler.buildDailyReport()).contains("모의투자");
    }

    @Test
    @DisplayName("조회가 실패하면 '데이터 없음'이 아니라 실패라고 적는다")
    void failureIsNotShownAsNoData() {
        when(virtualTrade.getAccountSummary()).thenThrow(new IllegalStateException("db down"));
        when(snapshots.findLatestByStrategyType(any())).thenReturn(List.of());

        assertThat(scheduler.buildDailyReport())
                .contains("포트폴리오 조회 실패")
                .doesNotContain("포트폴리오 데이터 없음");
    }
}
