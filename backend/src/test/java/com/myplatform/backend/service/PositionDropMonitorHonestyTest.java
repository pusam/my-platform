package com.myplatform.backend.service;

import com.myplatform.backend.dto.NewsSummaryDto;
import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.dto.StockPriceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실전 보유 종목 손실 알림 — 이름표·근거를 데이터대로(2026-10-03).
 *
 * <p>재현: 평단 대비 손익률 −3% 이하면 "🔴 보유 종목 급락"이라 보냈다 — 그날 오른 종목도 '급락'이었고 당일 등락률은
 * 현재가가 비었을 때만 붙었다. 관련 뉴스가 있어도 Gemini 가 실패하면 "관련 최근 뉴스 없음 — 시장 전반 요인 가능"이라
 * 했다. 휴장일 게이트도 없었다(MON-FRI cron 은 공휴일을 모른다).
 */
class PositionDropMonitorHonestyTest {

    private RealTradeService realTrade;
    private StockPriceService priceService;
    private NewsService newsService;
    private GeminiService gemini;
    private TelegramNotificationService telegram;
    private KoreaInvestmentService kis;
    private SchedulerLockService lock;
    private MarketCalendarService calendar;
    private PositionDropMonitorService monitor;

    @BeforeEach
    void setUp() {
        realTrade = mock(RealTradeService.class);
        priceService = mock(StockPriceService.class);
        newsService = mock(NewsService.class);
        gemini = mock(GeminiService.class);
        telegram = mock(TelegramNotificationService.class);
        kis = mock(KoreaInvestmentService.class);
        lock = mock(SchedulerLockService.class);
        calendar = mock(MarketCalendarService.class);
        when(kis.isRealTradingConfigured()).thenReturn(true);
        when(lock.tryLock(anyString(), any())).thenReturn(true);
        monitor = new PositionDropMonitorService(realTrade, priceService, newsService, gemini, telegram, kis, lock, calendar);
    }

    private static PortfolioItemDto holding(String rate) {
        return PortfolioItemDto.builder()
                .stockCode("009150").stockName("삼성전기").quantity(10)
                .currentPrice(new BigDecimal("150000")).profitRate(new BigDecimal(rate))
                .profitLoss(new BigDecimal("-50000"))
                .build();
    }

    private String sentMessage() {
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendRisk(msg.capture());
        return msg.getValue();
    }

    @Test
    @DisplayName("재현: 평단 대비 −3% 는 '급락'이 아니다 — 평가손실이라고 말하고 오늘 등락률을 같이 보인다")
    void labelSaysUnrealizedLossWithTodayChange() {
        when(realTrade.tryGetPortfolio()).thenReturn(Optional.of(List.of(holding("-3.5"))));
        StockPriceDto price = new StockPriceDto();
        price.setCurrentPrice(new BigDecimal("150000"));
        price.setChangeRate(new BigDecimal("1.20"));   // 그날은 올랐다
        when(priceService.getStockPrice("009150")).thenReturn(price);

        monitor.checkDrops();

        String m = sentMessage();
        assertThat(m).doesNotContain("급락");
        assertThat(m).contains("평가손실").contains("평단 대비");
        assertThat(m).contains("당일 +1.20%");
    }

    @Test
    @DisplayName("재현: 관련 뉴스가 있는데 Gemini 가 실패하면 '뉴스 없음'이 아니라 제목을 그대로")
    void geminiFailureListsTitles() {
        when(realTrade.tryGetPortfolio()).thenReturn(Optional.of(List.of(holding("-4.0"))));
        when(priceService.getStockPrice("009150")).thenReturn(null);
        NewsSummaryDto n = new NewsSummaryDto();
        n.setTitle("삼성전기, 3분기 실적 우려");
        when(newsService.getNewsSince(any())).thenReturn(List.of(n));
        when(gemini.chat(anyString())).thenReturn(null);

        monitor.checkDrops();

        String m = sentMessage();
        assertThat(m).doesNotContain("관련 최근 뉴스 없음");
        assertThat(m).contains("삼성전기, 3분기 실적 우려");
    }

    @Test
    @DisplayName("휴장일엔 돌지 않는다 — KIS 는 휴장일에도 직전 값을 준다")
    void holidayIsSkipped() {
        when(calendar.isMarketClosed(any())).thenReturn(true);

        monitor.checkDrops();

        verify(realTrade, never()).tryGetPortfolio();
        verify(telegram, never()).sendRisk(anyString());
    }
}
