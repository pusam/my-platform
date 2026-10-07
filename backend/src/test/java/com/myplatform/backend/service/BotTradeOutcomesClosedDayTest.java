package com.myplatform.backend.service;

import com.myplatform.backend.entity.VirtualTradeHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장이 닫힌 날 기록된 봇 거래는 봇 성적 게이트(⑧) 표본에서 뺀다(2026-10-07, 결정 대기 ⓐ — 추천안 진행).
 *
 * <p>재현(운영 가상계좌 25): 7/17(제헌절 — 2026년부터 휴장)에 모의 봇이 두 종목을 14:00 에 사고 15:20 에 팔았다 — 봇의 자체
 * 휴장일 목록에 7/17 이 없었다(10/7 달력으로 교체). 체결될 수 없는 거래인데 실현손익 −44,675원이 게이트 표본·누적 손익·낙폭에
 * 들어가 있었다. 행은 지우지 않고(사용자 데이터) 판정할 때만 빼고, 뺀 수를 센다(§4c — 조용히 빼지 않는다).
 */
class BotTradeOutcomesClosedDayTest {

    private static final MarketCalendarService CALENDAR = new MarketCalendarService();
    private static long seq = 1000;

    private static VirtualTradeHistory buy(String code, int qty, String price, String reason, LocalDateTime at) {
        BigDecimal p = new BigDecimal(price);
        return VirtualTradeHistory.builder().id(seq++).accountId(25L).stockCode(code).stockName(code)
                .tradeType("BUY").quantity(qty).price(p).totalAmount(p.multiply(BigDecimal.valueOf(qty)))
                .tradeReason(reason).tradeDate(at).build();
    }

    private static VirtualTradeHistory sell(String code, int qty, String price, String pnl, String reason, LocalDateTime at) {
        BigDecimal amount = new BigDecimal(price).multiply(BigDecimal.valueOf(qty));
        BigDecimal commission = amount.multiply(new BigDecimal("0.00015")).setScale(0, RoundingMode.CEILING);
        BigDecimal tax = amount.multiply(new BigDecimal("0.0015")).setScale(0, RoundingMode.CEILING);
        return VirtualTradeHistory.builder().id(seq++).accountId(25L).stockCode(code).stockName(code)
                .tradeType("SELL").quantity(qty).price(new BigDecimal(price)).totalAmount(amount)
                .commission(commission).tax(tax).profitLoss(new BigDecimal(pnl)).tradeReason(reason).tradeDate(at).build();
    }

    /** 7/16(거래일) 정상 한 쌍 + 7/17(휴장일) 운영 기록 두 쌍(id 1065~1068 값 그대로). */
    private static List<VirtualTradeHistory> rows() {
        return List.of(
                buy("005930", 10, "70000", "SWING_FOREIGN", LocalDateTime.of(2026, 7, 16, 10, 0)),
                sell("005930", 10, "71000", "8700", "REGULAR_SESSION_CLOSE", LocalDateTime.of(2026, 7, 16, 15, 20)),
                buy("298040", 1, "2789000", "SWING_FOREIGN", LocalDateTime.of(2026, 7, 17, 14, 0, 0)),
                buy("353200", 35, "138000", "SWING_INSTITUTION", LocalDateTime.of(2026, 7, 17, 14, 0, 5)),
                sell("298040", 1, "2758000", "-35970", "REGULAR_SESSION_CLOSE", LocalDateTime.of(2026, 7, 17, 15, 20, 0)),
                sell("353200", 35, "138000", "-8705", "REGULAR_SESSION_CLOSE", LocalDateTime.of(2026, 7, 17, 15, 20, 1)));
    }

    @Test
    @DisplayName("재현: 7/17 휴장일 두 쌍(−44,675원)은 결과·손익·낙폭에서 빠지고 2건으로 센다 — 7/16 거래일 한 쌍만 남는다")
    void closedDayTradesAreExcludedAndCounted() {
        BotTradeOutcomes.Result r = BotTradeOutcomes.build(rows(), CALENDAR::isMarketClosed);

        assertThat(r.outcomes()).hasSize(1);
        assertThat(r.outcomes().get(0).day()).isEqualTo(java.time.LocalDate.of(2026, 7, 16));
        assertThat(r.closedDayExcluded()).isEqualTo(2);
        assertThat(r.realizedPnlKrw()).isEqualByComparingTo("8700");
        assertThat(r.maxDrawdownKrw()).isEqualByComparingTo("0");
        assertThat(r.lastDay()).isEqualTo(java.time.LocalDate.of(2026, 7, 16));
    }

    @Test
    @DisplayName("휴장일에 산 물량을 거래일에 판 매도도 뺀다 — 매수가가 체결될 수 없는 값이라 손익이 가짜다")
    void sellOfClosedDayLotIsExcluded() {
        List<VirtualTradeHistory> rows = List.of(
                buy("298040", 1, "2789000", "SWING_FOREIGN", LocalDateTime.of(2026, 7, 17, 14, 0)),
                sell("298040", 1, "2800000", "8000", "TAKE_PROFIT", LocalDateTime.of(2026, 7, 20, 10, 0)));

        BotTradeOutcomes.Result r = BotTradeOutcomes.build(rows, CALENDAR::isMarketClosed);

        assertThat(r.outcomes()).isEmpty();
        assertThat(r.closedDayExcluded()).isEqualTo(1);
        assertThat(r.realizedPnlKrw()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("달력을 주지 않으면 종전 그대로 — 전부 센다")
    void withoutCalendarNothingChanges() {
        BotTradeOutcomes.Result r = BotTradeOutcomes.build(rows());

        assertThat(r.outcomes()).hasSize(3);
        assertThat(r.closedDayExcluded()).isZero();
        assertThat(r.realizedPnlKrw()).isEqualByComparingTo(new BigDecimal("8700").subtract(new BigDecimal("44675")));
    }
}
