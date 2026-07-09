package com.myplatform.backend.service;

import com.myplatform.backend.entity.InvestorDailyTrade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StockAnalysisService.sumNet5Days 순수 함수 — StockDetail AI 프롬프트가 재사용하는 5일 누적 수급 합산.
 * per-date (BUY금액 − SELL금액), 지정 투자자만. analyzeSupplyDemand 진단 산식과 동일 정의.
 */
class StockAnalysisSupplyTest {

    private InvestorDailyTrade t(LocalDate date, String investor, String type, String krw) {
        return InvestorDailyTrade.builder()
                .tradeDate(date).investorType(investor).tradeType(type)
                .netBuyAmount(new BigDecimal(krw)).build();
    }

    @Test
    @DisplayName("per-date BUY-SELL 합산, 지정 투자자만, 지정 일자만")
    void sumsBuyMinusSellForInvestor() {
        LocalDate d1 = LocalDate.of(2026, 7, 8), d2 = LocalDate.of(2026, 7, 9);
        List<InvestorDailyTrade> trades = List.of(
                t(d1, "FOREIGN", "BUY", "100"), t(d1, "FOREIGN", "SELL", "30"),   // net d1 = 70
                t(d2, "FOREIGN", "BUY", "50"),                                      // net d2 = 50
                t(d1, "INSTITUTION", "BUY", "999"),                                 // 다른 투자자 — 무시
                t(LocalDate.of(2026, 7, 1), "FOREIGN", "BUY", "888")               // 지정 일자 밖 — 무시
        );

        BigDecimal net = StockAnalysisService.sumNet5Days(trades, List.of(d2, d1), "FOREIGN");

        assertThat(net).isEqualByComparingTo("120");   // 70 + 50
    }

    @Test
    @DisplayName("순매도(SELL 우위)면 음수")
    void netSellIsNegative() {
        LocalDate d = LocalDate.of(2026, 7, 9);
        List<InvestorDailyTrade> trades = List.of(
                t(d, "INSTITUTION", "BUY", "20"), t(d, "INSTITUTION", "SELL", "140"));

        assertThat(StockAnalysisService.sumNet5Days(trades, List.of(d), "INSTITUTION"))
                .isEqualByComparingTo("-120");
    }

    @Test
    @DisplayName("빈 일자/거래 → 0")
    void emptyIsZero() {
        assertThat(StockAnalysisService.sumNet5Days(List.of(), List.of(), "FOREIGN"))
                .isEqualByComparingTo("0");
    }
}
