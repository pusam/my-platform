package com.myplatform.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.myplatform.backend.entity.MarketDailyStatus;
import com.myplatform.backend.repository.MarketDailyStatusRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 폭락·급락 보정 WARN 은 상태가 바뀔 때만 남긴다(2026-11-03).
 *
 * <p>억제 키에 등락률 숫자까지 넣어서, 분마다 숫자가 바뀌는 장중엔 매 분 '새 상태'가 됐다 — 11/3 코스피 −3%대 출발 날
 * 09:04 부터 1분마다 "폭락 오버라이드 발동: KOSPI -3.19% 폭락", "-3.29%", "-3.52%" … 가 찍혔다(하루 300줄 넘게).
 * 상태는 보정 종류(폭락/급락)·걸린 시장·급락이면 시장 상태·지수 세션이다. 숫자는 처음 걸린 순간의 값만 남는다.
 */
class MarketTimingOverrideLogTest {

    private final MarketDailyStatusRepository repo = mock(MarketDailyStatusRepository.class);
    private LocalDateTime clock = LocalDate.of(2026, 11, 3).atTime(10, 0);
    private BigDecimal kospiLive;
    private BigDecimal kosdaqLive;

    private final MarketTimingService service = new MarketTimingService(repo, mock(TelegramNotificationService.class),
            mock(RedisCacheService.class), new MarketCalendarService(), mock(KoreaInvestmentService.class)) {
        @Override
        BigDecimal[] crawlIndexInfo(String marketType) {
            return null;
        }

        @Override
        BigDecimal[] fetchIndexFromNaverApi(String marketType) {
            BigDecimal rate = "KOSPI".equals(marketType) ? kospiLive : kosdaqLive;
            return new BigDecimal[]{new BigDecimal("2700.00"), rate, null};
        }

        @Override
        LocalDateTime now() {
            return clock;
        }
    };

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        // 저장값은 전 거래일(11/2) — 장중엔 실시간 보충 값이 덮는다. ADR 은 창이 모자라 판단 보류.
        when(repo.findLatestAll()).thenReturn(List.of(row("KOSPI"), row("KOSDAQ")));
        when(repo.findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(any(), any(), any()))
                .thenAnswer(inv -> List.of(row(inv.getArgument(0))));
        logger = (Logger) LoggerFactory.getLogger(MarketTimingService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private static MarketDailyStatus row(String market) {
        return MarketDailyStatus.builder()
                .marketType(market).tradeDate(LocalDate.of(2026, 11, 2))
                .advancingCount(600).decliningCount(300).unchangedCount(20).totalCount(920)
                .indexClose(new BigDecimal("2780.00")).indexChangeRate(new BigDecimal("2.24"))
                .build();
    }

    private void tick(String kospi, String kosdaq) {
        kospiLive = new BigDecimal(kospi);
        kosdaqLive = new BigDecimal(kosdaq);
        service.getCurrentMarketTiming();
    }

    private List<String> warns(String phrase) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(phrase))
                .toList();
    }

    @Test
    @DisplayName("재현: 같은 폭락이 숫자만 바뀌며 이어지면 한 줄 — 예전엔 분마다 한 줄")
    void sameCrashWithMovingNumbersIsLoggedOnce() {
        tick("-3.19", "-2.07");
        tick("-3.29", "-2.06");
        tick("-3.52", "-2.13");

        assertThat(warns("폭락 오버라이드 발동")).containsExactly("[시장 타이밍] 폭락 오버라이드 발동: KOSPI -3.19% 폭락");
    }

    @Test
    @DisplayName("재현: 같은 급락 보정도 한 줄")
    void sameDropWithMovingNumbersIsLoggedOnce() {
        tick("-2.79", "-2.07");
        tick("-2.58", "-2.06");

        assertThat(warns("급락 보정 발동")).hasSize(1);
    }

    @Test
    @DisplayName("상태가 바뀌면 다시 남긴다 — 급락 → 폭락, 코스닥까지 폭락")
    void stateChangesAreStillLogged() {
        tick("-2.79", "-2.07");
        tick("-3.19", "-2.50");
        tick("-3.29", "-3.10");

        assertThat(warns("급락 보정 발동")).hasSize(1);
        assertThat(warns("폭락 오버라이드 발동")).containsExactly(
                "[시장 타이밍] 폭락 오버라이드 발동: KOSPI -3.19% 폭락",
                "[시장 타이밍] 폭락 오버라이드 발동: KOSPI -3.29% 폭락 + KOSDAQ -3.10% 폭락");
    }

    @Test
    @DisplayName("보정이 풀렸다가 다시 걸리면 다시 남긴다")
    void recoveryResetsTheState() {
        tick("-3.19", "-2.07");
        tick("-1.00", "-0.50");
        tick("-3.05", "-2.00");

        assertThat(warns("폭락 오버라이드 발동")).hasSize(2);
    }

    @Test
    @DisplayName("재현: 장 시작 전엔 직전 거래일 세션 값이라 그 날짜를 붙이고, 개장 뒤 오늘 세션은 새 상태로 남긴다")
    void previousSessionIsLabeledAndTodayIsANewState() {
        clock = LocalDate.of(2026, 11, 3).atTime(8, 10);
        tick("-3.19", "-2.07");
        clock = LocalDate.of(2026, 11, 3).atTime(9, 10);
        tick("-3.29", "-2.06");

        assertThat(warns("폭락 오버라이드 발동")).containsExactly(
                "[시장 타이밍] 폭락 오버라이드 발동(11/02 세션): KOSPI -3.19% 폭락",
                "[시장 타이밍] 폭락 오버라이드 발동: KOSPI -3.29% 폭락");
    }
}
