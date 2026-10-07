package com.myplatform.backend.service;

import com.myplatform.backend.repository.BotConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 봇의 '장 닫힘' 판정은 휴장일 달력 한 곳({@link MarketCalendarService})을 쓴다(2026-10-07).
 *
 * <p>재현: 봇이 자기 휴장일 목록(고정 양력 8개 + 2025·2026 표)을 따로 들고 있어서, 달력엔 있는 <b>근로자의날(5/1)·석탄일
 * 대체공휴일(5/25)·지방선거(6/3)·제헌절(7/17, 2026~)·연말폐장(12/31)</b>이 빠져 있었다 — 봇을 켜 두면 그날 정규장이 열린 줄
 * 알고 진입·청산을 시도한다(다음 해당일 2026-12-31). 이 저장소의 반복 결함 '같은 계산이 몇 벌인가'의 한 벌이다.
 * 시각 판정(KRX 09:00~15:30)은 봇 고유라 그대로다(§2·§4d — 달력의 15:40 과 다르다).
 */
class AutoTradingBotHolidayCalendarTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static AutoTradingBotService botAt(LocalDateTime kst) {
        Clock fixed = Clock.fixed(kst.atZone(KST).toInstant(), KST);
        return new AutoTradingBotService(
                mock(VirtualTradeService.class), mock(RealTradeService.class),
                mock(com.myplatform.backend.repository.VirtualPortfolioRepository.class),
                mock(InvestorSurgeService.class), mock(ScalpingAnalysisService.class),
                mock(StockPriceService.class), mock(TelegramNotificationService.class),
                mock(BotConfigRepository.class), mock(TechnicalIndicatorService.class),
                mock(KoreaInvestmentService.class), mock(GlobalFuturesService.class),
                mock(SectorTradingService.class), mock(ShortSellingService.class),
                mock(StockStatusService.class), mock(InvestorTradeService.class),
                mock(GlobalMarketService.class),
                mock(com.myplatform.backend.repository.BotTradingPositionRepository.class),
                mock(com.myplatform.backend.repository.StockPriceHistoryRepository.class),
                mockProvider(), fixed,
                new BotLeaderElectionService(null, false, 30L, "test"),
                mockProvider(), mockProvider(), mockProvider(),
                new MarketCalendarService());
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> mockProvider() {
        org.springframework.beans.factory.ObjectProvider<T> p = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    private static boolean closedAt(String isoLocal) {
        Boolean closed = ReflectionTestUtils.invokeMethod(botAt(LocalDateTime.parse(isoLocal)), "isMarketClosed");
        return Boolean.TRUE.equals(closed);
    }

    @ParameterizedTest(name = "{0} {1}")
    @DisplayName("재현: 봇 목록에 없던 휴장일 — 정규장 시각이어도 닫힘")
    @CsvSource({
            "2026-05-01T10:00, 근로자의날(금)",
            "2026-05-25T10:00, 석탄일 대체공휴일(월)",
            "2026-06-03T10:00, 지방선거(수)",
            "2026-07-17T10:00, 제헌절(금 — 2026년부터 공휴일)",
            "2026-12-31T10:00, 연말폐장(목)"
    })
    void holidaysMissingFromBotList(String at, String name) {
        assertThat(closedAt(at)).as(name).isTrue();
    }

    @ParameterizedTest(name = "{0} {1}")
    @DisplayName("종전대로 — 주말·기존 공휴일은 닫힘, 거래일 정규장은 열림, 시각 경계는 봇 고유(09:00~15:30)")
    @CsvSource({
            "2026-10-09T10:00, true,  한글날(금)",
            "2026-10-10T10:00, true,  토요일",
            "2026-10-08T10:00, false, 거래일 정규장",
            "2026-10-08T08:59, true,  개장 전",
            "2026-10-08T15:30, false, 15:30 은 아직 열림(경계 포함)",
            "2026-10-08T15:31, true,  봇은 15:30 이후 닫힘(달력 15:40 과 다르다)"
    })
    void unchangedBehavior(String at, boolean expectedClosed, String name) {
        assertThat(closedAt(at)).as(name).isEqualTo(expectedClosed);
    }
}
