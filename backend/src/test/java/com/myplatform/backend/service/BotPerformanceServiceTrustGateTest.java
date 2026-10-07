package com.myplatform.backend.service;

import com.myplatform.backend.controlroom.TrustGateRules;
import com.myplatform.backend.entity.BotConfig;
import com.myplatform.backend.entity.VirtualAccount;
import com.myplatform.backend.entity.VirtualTradeHistory;
import com.myplatform.backend.repository.BotConfigRepository;
import com.myplatform.backend.repository.VirtualAccountRepository;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 봇 성적 게이트 조립 — {@link BotPerformanceService#trustGateSummary()}·{@link BotPerformanceService#trustGate(String)}.
 *
 * <p>모드별 계좌(실전 999999 / 모의 = 활성 가상계좌), 자본 대비 최대 낙폭, 봇 켜짐 여부 전달, 실패의 정직한 표시를 고정한다.
 */
class BotPerformanceServiceTrustGateTest {

    private final VirtualTradeHistoryRepository trades = mock(VirtualTradeHistoryRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final BotConfigRepository configs = mock(BotConfigRepository.class);
    private final BotPerformanceService service = new BotPerformanceService(trades, accounts, configs, new MarketCalendarService());

    private static VirtualTradeHistory row(String type, String reason, int qty, String pnl, LocalDateTime at) {
        BigDecimal amount = new BigDecimal(1000L * qty);
        return VirtualTradeHistory.builder().accountId(25L).stockCode("005930").stockName("삼성전자")
                .tradeType(type).quantity(qty).price(new BigDecimal("1000")).totalAmount(amount)
                .commission(BigDecimal.ZERO).tax(BigDecimal.ZERO)
                .profitLoss(pnl == null ? null : new BigDecimal(pnl))
                .tradeReason(reason).tradeDate(at).build();
    }

    private static VirtualAccount account(long id, String initial) {
        VirtualAccount a = new VirtualAccount();
        a.setId(id);
        a.setInitialBalance(new BigDecimal(initial));
        return a;
    }

    @Test
    @DisplayName("모의 = 활성 가상계좌의 봇 거래 — 최대 낙폭은 계좌 원금 대비 %")
    void virtualUsesActiveAccountAndCapitalForDrawdown() {
        when(accounts.findFirstByIsActiveTrueOrderByIdDesc()).thenReturn(Optional.of(account(25L, "10000000")));
        when(trades.findBotTrades(eq(25L), anyList())).thenReturn(List.of(
                row("BUY", "SCALPING_ENTRY", 100, null, LocalDateTime.of(2026, 7, 3, 10, 0)),
                row("SELL", "STOP_LOSS", 100, "-300000", LocalDateTime.of(2026, 7, 3, 10, 20))));

        BotPerformanceService.BotGateLine line = service.trustGate("VIRTUAL");

        assertThat(line.dataAvailable()).isTrue();
        assertThat(line.accountId()).isEqualTo(25L);
        assertThat(line.capitalKrw()).isEqualByComparingTo("10000000");
        assertThat(line.realizedPnlKrw()).isEqualByComparingTo("-300000");
        assertThat(line.verdict().trades()).isEqualTo(1);
        assertThat(line.verdict().maxDrawdownPct()).isEqualByComparingTo("-3.00");
        assertThat(line.verdict().state()).isEqualTo(TrustGateRules.State.COLLECTING);
    }

    @Test
    @DisplayName("실전 = 실계좌(999999) — 원금을 모르니 최대 낙폭은 판정하지 않는다(null)")
    void realUsesRealAccountWithoutCapital() {
        when(trades.findBotTrades(eq(999999L), anyList())).thenReturn(List.of());

        BotPerformanceService.BotGateLine line = service.trustGate("REAL");

        assertThat(line.accountId()).isEqualTo(999999L);
        assertThat(line.capitalKrw()).isNull();
        assertThat(line.verdict().maxDrawdownPct()).isNull();
        assertThat(line.verdict().trades()).isZero();
    }

    @Test
    @DisplayName("활성 가상계좌가 없으면 거래 0건으로 판정하고 그 이유를 적는다")
    void noActiveVirtualAccount() {
        when(accounts.findFirstByIsActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());

        BotPerformanceService.BotGateLine line = service.trustGate("VIRTUAL");

        assertThat(line.dataAvailable()).isTrue();
        assertThat(line.note()).isEqualTo("활성 가상계좌 없음 — 모의 봇 거래 0건");
        assertThat(line.verdict().state()).isEqualTo(TrustGateRules.State.COLLECTING);
    }

    @Test
    @DisplayName("조회가 실패하면 dataAvailable=false — '거래 없음'과 구분한다(§4c)")
    void repositoryFailureIsNotZeroTrades() {
        when(accounts.findFirstByIsActiveTrueOrderByIdDesc()).thenReturn(Optional.of(account(25L, "10000000")));
        when(trades.findBotTrades(eq(25L), anyList())).thenThrow(new IllegalStateException("DB down"));

        BotPerformanceService.BotGateLine line = service.trustGate("VIRTUAL");

        assertThat(line.dataAvailable()).isFalse();
        assertThat(line.verdict()).isNull();
        assertThat(line.note()).isEqualTo("집계 실패 (IllegalStateException)");
    }

    @Test
    @DisplayName("묶음: 현재 모드가 먼저, 다른 모드는 거래가 있을 때만 — 봇 켜짐 여부·마지막 상태 변경을 그대로 싣는다")
    void summaryOrdersCurrentModeFirstAndCarriesBotState() {
        BotConfig config = new BotConfig();
        config.setConfigKey("trading_bot");
        config.setTradingMode("VIRTUAL");
        config.setIsActive(false);
        config.setLastStatusChange(LocalDateTime.of(2026, 7, 27, 15, 20, 15));
        when(configs.findByConfigKey("trading_bot")).thenReturn(Optional.of(config));
        when(accounts.findFirstByIsActiveTrueOrderByIdDesc()).thenReturn(Optional.of(account(25L, "10000000")));
        when(trades.findBotTrades(eq(25L), anyList())).thenReturn(List.of(
                row("BUY", "SWING_FOREIGN", 10, null, LocalDateTime.of(2026, 7, 20, 9, 5)),
                row("SELL", "TIME_CUT", 10, "1500", LocalDateTime.of(2026, 7, 21, 10, 0))));
        when(trades.findBotTrades(eq(999999L), anyList())).thenReturn(List.of());

        BotPerformanceService.BotGateSummary s = service.trustGateSummary();

        assertThat(s.currentMode()).isEqualTo("VIRTUAL");
        assertThat(s.botActive()).isFalse();
        assertThat(s.botStatusChangedAt()).isEqualTo(LocalDateTime.of(2026, 7, 27, 15, 20, 15));
        assertThat(s.lines()).extracting(BotPerformanceService.BotGateLine::mode).containsExactly("VIRTUAL");
        assertThat(s.note()).isNull();
    }

    @Test
    @DisplayName("묶음: 실전 모드여도 모의 거래가 있으면 둘째 줄로 싣는다")
    void summaryIncludesOtherModeWhenItHasTrades() {
        BotConfig config = new BotConfig();
        config.setConfigKey("trading_bot");
        config.setTradingMode("REAL");
        config.setIsActive(true);
        when(configs.findByConfigKey("trading_bot")).thenReturn(Optional.of(config));
        when(trades.findBotTrades(eq(999999L), anyList())).thenReturn(List.of());
        when(accounts.findFirstByIsActiveTrueOrderByIdDesc()).thenReturn(Optional.of(account(25L, "10000000")));
        when(trades.findBotTrades(eq(25L), anyList())).thenReturn(List.of(
                row("BUY", "SCALPING_ENTRY", 10, null, LocalDateTime.of(2026, 7, 20, 9, 5)),
                row("SELL", "TIME_CUT", 10, "-200", LocalDateTime.of(2026, 7, 20, 9, 40))));

        BotPerformanceService.BotGateSummary s = service.trustGateSummary();

        assertThat(s.lines()).extracting(BotPerformanceService.BotGateLine::mode).containsExactly("REAL", "VIRTUAL");
    }

    @Test
    @DisplayName("봇 설정 조회가 실패하면 모의로 판정하고 그 사실을 적는다")
    void configFailureFallsBackToVirtualWithNote() {
        when(configs.findByConfigKey("trading_bot")).thenThrow(new IllegalStateException("DB down"));
        when(accounts.findFirstByIsActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());
        when(trades.findBotTrades(eq(999999L), anyList())).thenReturn(List.of());

        BotPerformanceService.BotGateSummary s = service.trustGateSummary();

        assertThat(s.currentMode()).isEqualTo("VIRTUAL");
        assertThat(s.botActive()).isNull();
        assertThat(s.note()).isEqualTo("봇 설정 조회 실패 — 모의 계좌로 판정");
    }
}
