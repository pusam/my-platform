package com.myplatform.backend.service;

import com.myplatform.backend.controlroom.BotGateRules;
import com.myplatform.backend.entity.VirtualTradeHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 봇 거래 기록 → 매도 결과 — {@link BotTradeOutcomes}(2026-10-02, 봇 성적 게이트 입력).
 *
 * <p>전략은 매도 사유가 아니라 <b>짝이 되는 매수 사유</b>로 정해야 한다(손절·시간컷은 전략끼리 공유). 순수익률은 기록된
 * 실현손익(이미 수수료·세금 차감)을 같은 행에서 되짚은 투입금으로 나눈다.
 */
class BotTradeOutcomesTest {

    private static long seq = 1;

    private static VirtualTradeHistory buy(String code, int qty, String reason, LocalDateTime at) {
        return VirtualTradeHistory.builder().id(seq++).accountId(25L).stockCode(code).stockName(code)
                .tradeType("BUY").quantity(qty).price(new BigDecimal("1000"))
                .totalAmount(new BigDecimal(1000L * qty)).tradeReason(reason).tradeDate(at).build();
    }

    /** VirtualTradeService.sell 과 같은 산식: 손익 = (매도금액 − 수수료 − 세금) − 평단(매수수수료 포함) × 수량. */
    private static VirtualTradeHistory sell(String code, int qty, String price, String avgPrice,
                                            String reason, LocalDateTime at) {
        BigDecimal amount = new BigDecimal(price).multiply(BigDecimal.valueOf(qty));
        BigDecimal commission = amount.multiply(new BigDecimal("0.00015")).setScale(0, java.math.RoundingMode.CEILING);
        BigDecimal tax = amount.multiply(new BigDecimal("0.0015")).setScale(0, java.math.RoundingMode.CEILING);
        BigDecimal pnl = amount.subtract(commission).subtract(tax)
                .subtract(new BigDecimal(avgPrice).multiply(BigDecimal.valueOf(qty)));
        return VirtualTradeHistory.builder().id(seq++).accountId(25L).stockCode(code).stockName(code)
                .tradeType("SELL").quantity(qty).price(new BigDecimal(price)).totalAmount(amount)
                .commission(commission).tax(tax).profitLoss(pnl).tradeReason(reason).tradeDate(at).build();
    }

    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 7, day, hour, minute);
    }

    @Test
    @DisplayName("전략은 매수 사유로 — 스캘핑 매수의 손절은 스캘핑, 순수익률은 투입금(평단×수량) 대비")
    void strategyFromBuyAndNetReturnFromInvested() {
        List<VirtualTradeHistory> rows = List.of(
                buy("005930", 10, "SCALPING_ENTRY", at(3, 10, 0)),
                sell("005930", 10, "1010", "1000", "TIME_CUT", at(3, 10, 30)));

        BotTradeOutcomes.Result r = BotTradeOutcomes.build(rows);

        assertThat(r.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.strategy()).isEqualTo("SCALPING");
            assertThat(o.day()).isEqualTo(LocalDate.of(2026, 7, 3));
            // 매도 10,100 − 수수료 2 − 세금 16 − 투입 10,000 = 82 → 82 / 10,000 = 0.82%
            assertThat(o.netReturnPct()).isEqualByComparingTo("0.8200");
        });
        assertThat(r.realizedPnlKrw()).isEqualByComparingTo("82");
        assertThat(r.excluded()).isZero();
    }

    @Test
    @DisplayName("저장소는 최신순으로 준다 — 시간순으로 다시 세워야 매수가 매도보다 먼저 소진된다")
    void sortsNewestFirstInputBackIntoTimeOrder() {
        List<VirtualTradeHistory> rows = new ArrayList<>(List.of(
                buy("000660", 5, "SWING_FOREIGN", at(7, 9, 5)),
                sell("000660", 5, "980", "1000", "STOP_LOSS", at(8, 9, 30))));
        java.util.Collections.reverse(rows);   // ORDER BY tradeDate DESC 와 같은 모양

        BotTradeOutcomes.Result r = BotTradeOutcomes.build(rows);

        assertThat(r.outcomes()).singleElement()
                .satisfies(o -> assertThat(o.strategy()).isEqualTo("SWING"));
    }

    @Test
    @DisplayName("분할 매도는 매도 건마다 따로 — 둘 다 같은 매수의 전략을 물려받는다")
    void partialSellsInheritTheSameBuy() {
        List<VirtualTradeHistory> rows = List.of(
                buy("035420", 10, "SWING_INSTITUTION", at(7, 9, 5)),
                sell("035420", 5, "1050", "1000", "TAKE_PROFIT_HALF", at(8, 10, 0)),
                sell("035420", 5, "990", "1000", "STOP_LOSS", at(9, 10, 0)));

        BotTradeOutcomes.Result r = BotTradeOutcomes.build(rows);

        assertThat(r.outcomes()).extracting(BotGateRules.TradeOutcome::strategy).containsExactly("SWING", "SWING");
        assertThat(r.firstDay()).isEqualTo(LocalDate.of(2026, 7, 8));
        assertThat(r.lastDay()).isEqualTo(LocalDate.of(2026, 7, 9));
    }

    @Test
    @DisplayName("같은 종목을 다른 전략으로 두 번 샀으면 먼저 산 것부터 소진 — 처음 소진된 매수의 전략")
    void fifoAcrossStrategies() {
        List<VirtualTradeHistory> rows = List.of(
                buy("066570", 5, "SCALPING_ENTRY", at(10, 9, 30)),
                buy("066570", 5, "CLOSING_BUY", at(10, 14, 0)),
                sell("066570", 7, "1005", "1000", "TIME_CUT", at(10, 14, 30)),
                sell("066570", 3, "1005", "1000", "END_OF_DAY", at(11, 9, 5)));

        BotTradeOutcomes.Result r = BotTradeOutcomes.build(rows);

        assertThat(r.outcomes()).extracting(BotGateRules.TradeOutcome::strategy)
                .containsExactly("SCALPING", "CLOSING");
    }

    @Test
    @DisplayName("짝이 되는 매수가 없으면 UNKNOWN — 추측하지 않는다")
    void sellWithoutBuyIsUnknown() {
        BotTradeOutcomes.Result r = BotTradeOutcomes.build(List.of(
                sell("373220", 2, "1000", "990", "AUTO_SELL", at(15, 11, 0))));

        assertThat(r.outcomes()).singleElement()
                .satisfies(o -> assertThat(o.strategy()).isEqualTo("UNKNOWN"));
    }

    @Test
    @DisplayName("손익·금액이 없거나 투입금이 0 이하면 결과에서 빼고 센다(§4c) — 손익 없는 행은 손익 곡선에도 안 들어간다")
    void unusableRowsAreCountedNotHidden() {
        VirtualTradeHistory noPnl = sell("005380", 1, "1000", "1000", "TIME_CUT", at(16, 10, 0));
        noPnl.setProfitLoss(null);
        VirtualTradeHistory broken = sell("005380", 1, "1000", "1000", "TIME_CUT", at(16, 11, 0));
        broken.setProfitLoss(new BigDecimal("5000"));   // 매도금액보다 큰 이익 → 투입금 음수

        BotTradeOutcomes.Result r = BotTradeOutcomes.build(List.of(noPnl, broken));

        assertThat(r.outcomes()).isEmpty();
        assertThat(r.excluded()).isEqualTo(2);
        assertThat(r.realizedPnlKrw()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("실현손익 누적곡선의 최대 낙폭 — 고점 대비 가장 깊이 빠진 금액")
    void maxDrawdownOnRealizedCurve() {
        VirtualTradeHistory a = sell("A", 1, "1000", "1000", "TIME_CUT", at(20, 10, 0));
        a.setProfitLoss(new BigDecimal("100"));
        VirtualTradeHistory b = sell("B", 1, "1000", "1000", "STOP_LOSS", at(21, 10, 0));
        b.setProfitLoss(new BigDecimal("-300"));
        VirtualTradeHistory c = sell("C", 1, "1000", "1000", "TRAILING_STOP", at(22, 10, 0));
        c.setProfitLoss(new BigDecimal("50"));

        BotTradeOutcomes.Result r = BotTradeOutcomes.build(List.of(c, a, b));

        // 누적 100 → -200 → -150: 고점 100 에서 -200 까지 300 빠졌다
        assertThat(r.maxDrawdownKrw()).isEqualByComparingTo("300");
        assertThat(r.realizedPnlKrw()).isEqualByComparingTo("-150");
    }

    @Test
    @DisplayName("거래가 없으면 빈 결과 — 손익 0, 기간 없음")
    void emptyInput() {
        BotTradeOutcomes.Result r = BotTradeOutcomes.build(null);

        assertThat(r.outcomes()).isEmpty();
        assertThat(r.realizedPnlKrw()).isEqualByComparingTo("0");
        assertThat(r.maxDrawdownKrw()).isEqualByComparingTo("0");
        assertThat(r.firstDay()).isNull();
        assertThat(r.lastDay()).isNull();
    }

    @Test
    @DisplayName("매수 사유 → 전략 이름은 봇이 기록하는 실값과 같다")
    void strategyNamesMatchBotReasons() {
        assertThat(BotTradeOutcomes.strategyOf("SCALPING_ENTRY")).isEqualTo("SCALPING");
        assertThat(BotTradeOutcomes.strategyOf("SWING_FOREIGN")).isEqualTo("SWING");
        assertThat(BotTradeOutcomes.strategyOf("SWING_INSTITUTION")).isEqualTo("SWING");
        assertThat(BotTradeOutcomes.strategyOf("CLOSING_BUY")).isEqualTo("CLOSING");
        assertThat(BotTradeOutcomes.strategyOf("MANUAL")).isEqualTo("UNKNOWN");
        assertThat(BotTradeOutcomes.strategyOf(null)).isEqualTo("UNKNOWN");
    }
}
