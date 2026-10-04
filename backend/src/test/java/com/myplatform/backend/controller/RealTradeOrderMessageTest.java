package com.myplatform.backend.controller;

import com.myplatform.backend.dto.PaperTradingDto.TradeHistoryDto;
import com.myplatform.backend.dto.PaperTradingDto.TradeRequestDto;
import com.myplatform.backend.service.AutoTradingBotService;
import com.myplatform.backend.service.BotPerformanceService;
import com.myplatform.backend.service.DailyLossBreakerService;
import com.myplatform.backend.service.RealTradeService;
import com.myplatform.backend.service.VirtualTradeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 실전 수동 주문 응답 문구 — 접수는 체결이 아니다(2026-10-04).
 *
 * <p>재현: 실전 주문을 KIS 에 넣자마자 "실전 매수 주문이 체결되었습니다."라고 했다 — 지정가는 부분·미체결이 가능하고 이 경로는
 * 체결을 확인하지 않는다(봇 매도만 confirmFill). 모의 주문은 즉시 체결이라 종전 문구 그대로.
 */
class RealTradeOrderMessageTest {

    @Test
    @DisplayName("재현: 실전 매수·매도는 '접수' — '체결되었습니다'라고 하지 않는다")
    void realOrderIsAcceptedNotFilled() {
        RealTradeService real = mock(RealTradeService.class);
        when(real.buy(anyString(), any(), anyInt(), anyString())).thenReturn(new TradeHistoryDto());
        when(real.sell(anyString(), any(), anyInt(), anyString())).thenReturn(new TradeHistoryDto());
        PaperTradingController c = new PaperTradingController(mock(VirtualTradeService.class), real,
                mock(AutoTradingBotService.class), mock(BotPerformanceService.class), mock(DailyLossBreakerService.class));

        TradeRequestDto buy = new TradeRequestDto();
        buy.setStockCode("005930"); buy.setPrice(new BigDecimal("70000")); buy.setQuantity(1); buy.setTradeType("BUY");
        Map<String, Object> b = c.placeRealTrade(buy).getBody();
        TradeRequestDto sell = new TradeRequestDto();
        sell.setStockCode("005930"); sell.setPrice(new BigDecimal("70000")); sell.setQuantity(1); sell.setTradeType("SELL");
        Map<String, Object> s = c.placeRealTrade(sell).getBody();

        assertThat((String) b.get("message")).contains("접수").doesNotContain("체결되었습니다");
        assertThat((String) s.get("message")).contains("접수").doesNotContain("체결되었습니다");
    }
}
