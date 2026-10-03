package com.myplatform.backend.service;

import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 복합 신호(멀티 컨빅션) — 순매도 행의 부호(2026-10-03 화면 점검).
 *
 * <p>수집기는 순매도 순위(SELL) 행을 <b>음수</b>로 저장한다(운영 30일 SELL 행 전부 음수). 예전 코드는 SELL 이면
 * {@code negate()} 로 한 번 더 뒤집어 순매도한 투자자를 '매수'로 셌다 — 외국인·연기금이 함께 판 종목이 '2중 동시 매수'.
 */
class MultiConvictionSignTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    private static InvestorDailyTrade row(String investor, String type, String net) {
        return InvestorDailyTrade.builder()
                .tradeDate(DAY).stockCode("000660").stockName("SK하이닉스").investorType(investor).tradeType(type)
                .netBuyAmount(new BigDecimal(net)).currentPrice(new BigDecimal("400000")).changeRate(new BigDecimal("-1.2"))
                .rankNum(1).build();
    }

    private MultiConvictionService serviceWith(List<InvestorDailyTrade> rows) {
        InvestorDailyTradeRepository repo = mock(InvestorDailyTradeRepository.class);
        when(repo.findByTradeDateOrderByInvestorTypeAscTradeTypeAscRankNumAsc(DAY)).thenReturn(rows);
        return new MultiConvictionService(repo);
    }

    @Test
    @DisplayName("재현: 외국인·연기금이 함께 판 종목(SELL 행 음수)이 '동시 매수'로 나오던 것 — 이제 '동시 매도'")
    void storedNegativeSellsAreSellers() {
        var result = serviceWith(List.of(row("FOREIGN", "SELL", "-500.0"), row("PENSION", "SELL", "-120.0"))).analyze(DAY);

        assertThat(result.getBuySignals()).isEmpty();
        assertThat(result.getSellSignals()).hasSize(1);
        assertThat(result.getSellSignals().get(0).getSellInvestors()).containsExactlyInAnyOrder("외국인 500.0", "연기금 120.0");
    }

    @Test
    @DisplayName("함께 산 종목은 그대로 '동시 매수'")
    void buyersStayBuyers() {
        var result = serviceWith(List.of(row("FOREIGN", "BUY", "300.0"), row("PENSION", "BUY", "80.0"))).analyze(DAY);

        assertThat(result.getSellSignals()).isEmpty();
        assertThat(result.getBuySignals()).hasSize(1);
    }
}
