package com.myplatform.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockPriceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 일봉 저장의 종목별 로그는 DEBUG — 회차 요약은 호출부가 한 줄로(2026-10-07, §5).
 *
 * <p>재현: 09시 종합추천이 직전 봉이 없는 617종목의 일봉을 백그라운드로 받으며 종목마다 INFO
 * '종목 N 일봉 데이터 저장 완료 - 신규 …'를 남겼다(18:30 일봉 보정 400종목도 같은 줄). 저장 동작은 그대로다.
 */
class DailyBarSaveLogVolumeTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(StockAnalysisService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("재현: 일봉 수집은 저장하되 종목별 INFO 를 남기지 않는다")
    void noPerStockInfo() {
        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        StockPriceHistoryRepository history = mock(StockPriceHistoryRepository.class);
        StockPriceRepository prices = mock(StockPriceRepository.class);
        when(kis.getDailyOhlcv("005930", 60)).thenReturn(List.of(
                new KoreaInvestmentService.OhlcvData(new BigDecimal("270000"), new BigDecimal("275000"),
                        new BigDecimal("268000"), new BigDecimal("273000"), new BigDecimal("1000000"),
                        LocalDate.of(2026, 10, 6))));
        when(history.findByStockCodeAndTradeDate(anyString(), any())).thenReturn(Optional.empty());
        when(prices.findTopByStockCodeOrderByFetchedAtDesc(anyString())).thenReturn(Optional.empty());
        StockAnalysisService svc = new StockAnalysisService(mock(StockFinancialDataRepository.class),
                mock(InvestorDailyTradeRepository.class), history, prices,
                mock(TechnicalIndicatorService.class), kis);

        svc.collectPriceHistory("005930");

        verify(history, times(1)).save(any());   // 저장 동작은 그대로
        assertThat(appender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("일봉 데이터")))
                .isEmpty();
    }
}
