package com.myplatform.backend.service;

import com.myplatform.backend.dto.BotPerformanceDto;
import com.myplatform.backend.entity.VirtualTradeHistory;
import com.myplatform.backend.repository.BotConfigRepository;
import com.myplatform.backend.repository.VirtualAccountRepository;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 봇 성과 — 정의되지 않은 지표를 0 으로 채우지 않는다(2026-10-03).
 *
 * <p>재현: 이 기간 봇 매도가 0건이면 수익 팩터 0·승률 0%·최대 수익 0원이 내려가 화면이 'PF 0.00 · 1.0 미만: 손실'로
 * 칠했다. 이긴 거래가 없는 창의 '최대 수익 +0원'도 같은 부류다.
 */
class BotPerformanceEmptyWindowTest {

    private static VirtualTradeHistory sell(String pnl) {
        return VirtualTradeHistory.builder()
                .accountId(999999L).stockCode("005930").stockName("삼성전자")
                .tradeType("SELL").quantity(1).price(new BigDecimal("70000")).totalAmount(new BigDecimal("70000"))
                .profitLoss(new BigDecimal(pnl)).tradeReason("STOP_LOSS")
                .tradeDate(LocalDateTime.of(2026, 7, 21, 10, 0))
                .build();
    }

    private static BotPerformanceService service(List<VirtualTradeHistory> trades) {
        VirtualTradeHistoryRepository repo = mock(VirtualTradeHistoryRepository.class);
        when(repo.findBotTradesBetween(anyLong(), any(), any(), any())).thenReturn(trades);
        when(repo.findBotTrades(anyLong(), any())).thenReturn(trades);
        return new BotPerformanceService(repo, mock(VirtualAccountRepository.class), mock(BotConfigRepository.class), new MarketCalendarService());
    }

    @Test
    @DisplayName("재현: 매도 0건 창 — 수익 팩터·승률·최대 수익/손실·낙폭은 null(0·0% 아님)")
    void emptyWindowHasNoFabricatedRatios() {
        BotPerformanceDto p = service(List.of()).getPerformance(7, "REAL");

        assertThat(p.getTotalTrades()).isZero();
        assertThat(p.getProfitFactor()).isNull();
        assertThat(p.getWinRate()).isNull();
        assertThat(p.getMaxWin()).isNull();
        assertThat(p.getMaxLoss()).isNull();
        assertThat(p.getMaxDrawdown()).isNull();
    }

    @Test
    @DisplayName("진 거래만 있으면 수익 팩터 0.00 은 실제 값, 최대 수익은 모름(null)")
    void lossesOnly() {
        BotPerformanceDto p = service(List.of(sell("-1000"), sell("-500"))).getPerformance(7, "REAL");

        assertThat(p.getProfitFactor()).isEqualByComparingTo("0");
        assertThat(p.getMaxWin()).isNull();
        assertThat(p.getMaxLoss()).isEqualByComparingTo("-1000");
    }
}
