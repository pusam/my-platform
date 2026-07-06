package com.myplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.myplatform.backend.entity.BotConfig;
import com.myplatform.backend.repository.BotConfigRepository;
import com.myplatform.backend.service.AutoTradingBotService.SwingExitLevels;
import com.myplatform.backend.service.AutoTradingBotService.TradingMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/**
 * ATR 세트(V42, flag `bot.atr-trading.enabled`) 안전 계약 테스트.
 *
 * 계약: ① flag 기본 OFF ② <b>REAL 은 flag 무관 무조건 현행(하드 가드)</b> — 사이징(isAtrSetActive)과
 * 청산(resolveSwingExitLevels) 양쪽 ③ ATR 스냅샷 없는 포지션은 현행 고정 -3/+5 ④ riskBudget
 * 기본 = 브레이커 한도 ÷ 6, bot_config 'atr_trading' 행으로 오버라이드.
 */
class AutoTradingBotAtrSetTest {

    // ==================== isAtrSetActive — 사이징 게이트 ====================

    private AutoTradingBotService newBot(BotConfigRepository botConfigRepository) {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-11T01:00:00Z"), ZoneId.of("Asia/Seoul"));
        return new AutoTradingBotService(
                mock(VirtualTradeService.class), mock(RealTradeService.class),
                mock(com.myplatform.backend.repository.VirtualPortfolioRepository.class),
                mock(InvestorSurgeService.class), mock(ScalpingAnalysisService.class),
                mock(StockPriceService.class), mock(TelegramNotificationService.class),
                botConfigRepository, mock(TechnicalIndicatorService.class),
                mock(KoreaInvestmentService.class), mock(GlobalFuturesService.class),
                mock(SectorTradingService.class), mock(ShortSellingService.class),
                mock(StockStatusService.class), mock(InvestorTradeService.class),
                mock(GlobalMarketService.class),
                mock(com.myplatform.backend.repository.BotTradingPositionRepository.class),
                mock(com.myplatform.backend.repository.StockPriceHistoryRepository.class),
                mockProvider(), fixed,
                new BotLeaderElectionService(null, false, 30L, "test"),
                mockProvider(), mockProvider());
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> mockProvider() {
        org.springframework.beans.factory.ObjectProvider<T> p =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    @Test
    void atrSet_defaultOff_bothModes() {
        AutoTradingBotService bot = newBot(mock(BotConfigRepository.class));
        // @Value 기본 false(수동 생성 시 primitive 기본값) — OFF 면 어느 모드든 현행
        assertThat(bot.isAtrSetActive(TradingMode.VIRTUAL)).isFalse();
        assertThat(bot.isAtrSetActive(TradingMode.REAL)).isFalse();
    }

    @Test
    void atrSet_onlyVirtual_whenEnabled_realHardGuard() {
        AutoTradingBotService bot = newBot(mock(BotConfigRepository.class));
        bot.setAtrTradingEnabledForTest(true);
        assertThat(bot.isAtrSetActive(TradingMode.VIRTUAL)).isTrue();
        // ★ REAL 하드 가드 — flag ON 이어도 REAL 은 무조건 현행 ★
        assertThat(bot.isAtrSetActive(TradingMode.REAL)).isFalse();
    }

    // ==================== resolveSwingExitLevels — 청산 게이트(순수) ====================

    @Test
    void exitLevels_noSnapshot_currentFixed() {
        // ATR 스냅샷 없는 포지션(flag OFF·결측·기존 포지션) → 현행 -3/+5
        SwingExitLevels levels = AutoTradingBotService.resolveSwingExitLevels(TradingMode.VIRTUAL, null, null);
        assertThat(levels.stop()).isEqualByComparingTo("-3.0");
        assertThat(levels.target()).isEqualByComparingTo("5.0");
        // 스냅샷이 한쪽만 있는 비정상 상태도 현행(§4c 폴백)
        assertThat(AutoTradingBotService.resolveSwingExitLevels(
                TradingMode.VIRTUAL, new BigDecimal("-7.5"), null).stop()).isEqualByComparingTo("-3.0");
    }

    @Test
    void exitLevels_snapshotVirtual_atrLevels() {
        SwingExitLevels levels = AutoTradingBotService.resolveSwingExitLevels(
                TradingMode.VIRTUAL, new BigDecimal("-7.5"), new BigDecimal("12.5"));
        assertThat(levels.stop()).isEqualByComparingTo("-7.5");
        assertThat(levels.target()).isEqualByComparingTo("12.5");
    }

    @Test
    void exitLevels_real_alwaysCurrent_evenWithSnapshot() {
        // ★ REAL 이중 하드 가드 — 스냅샷이 있어도(비정상) 현행 고정 ★
        SwingExitLevels levels = AutoTradingBotService.resolveSwingExitLevels(
                TradingMode.REAL, new BigDecimal("-7.5"), new BigDecimal("12.5"));
        assertThat(levels.stop()).isEqualByComparingTo("-3.0");
        assertThat(levels.target()).isEqualByComparingTo("5.0");
    }

    // ==================== resolveAtrRiskBudget ====================

    @Test
    void riskBudget_default_breakerLimitDividedBy6() {
        BotConfigRepository repo = mock(BotConfigRepository.class);
        when(repo.findByConfigKey(anyString())).thenReturn(Optional.empty());
        // 설정 행 없음 → 브레이커 기본 한도 30만 ÷ 6 = 5만
        assertThat(newBot(repo).resolveAtrRiskBudget()).isEqualByComparingTo("50000");
    }

    @Test
    void riskBudget_followsBreakerLimit() {
        BotConfigRepository repo = mock(BotConfigRepository.class);
        when(repo.findByConfigKey(anyString())).thenReturn(Optional.empty());
        when(repo.findByConfigKey(eq("daily_loss_breaker"))).thenReturn(Optional.of(
                BotConfig.builder().configKey("daily_loss_breaker")
                        .dailyLossLimitKrw(new BigDecimal("600000")).build()));
        assertThat(newBot(repo).resolveAtrRiskBudget()).isEqualByComparingTo("100000");
    }

    @Test
    void riskBudget_configOverrideWins() {
        BotConfigRepository repo = mock(BotConfigRepository.class);
        when(repo.findByConfigKey(anyString())).thenReturn(Optional.empty());
        BotConfig atrRow = BotConfig.builder().configKey("atr_trading").build();
        atrRow.setAtrRiskBudgetKrw(new BigDecimal("80000"));
        when(repo.findByConfigKey(eq("atr_trading"))).thenReturn(Optional.of(atrRow));
        assertThat(newBot(repo).resolveAtrRiskBudget()).isEqualByComparingTo("80000");
    }

    @Test
    void riskBudget_repositoryFailure_conservativeDefault() {
        BotConfigRepository repo = mock(BotConfigRepository.class);
        when(repo.findByConfigKey(anyString())).thenThrow(new RuntimeException("DB down"));
        assertThat(newBot(repo).resolveAtrRiskBudget()).isEqualByComparingTo("50000");
    }
}
