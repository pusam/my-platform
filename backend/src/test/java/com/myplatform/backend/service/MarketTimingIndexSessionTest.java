package com.myplatform.backend.service;

import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.entity.MarketDailyStatus;
import com.myplatform.backend.repository.MarketDailyStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 지수 등락률은 어느 거래일의 값인가(2026-10-19, 리뷰 에이전트 지적).
 *
 * <p>재현: 장 시작 전·휴장일에 받는 지수 등락률(네이버 실시간 보충, 실패하면 16:30 저장값)은 <b>직전 거래일 세션</b>의 값인데,
 * 급락 보정 문장은 늘 "당일 급락 — KOSPI −2.80%"였다 — 07:30 모닝브리핑·'오늘' 탭 시장 줄이 지난 거래일 하락을 오늘 일처럼
 * 말했다. 이제 지수 값마다 세션 날짜({@code indexAsOf})를 싣고, 오늘이 아니면 그 날짜로 말한다.
 */
class MarketTimingIndexSessionTest {

    private static final LocalDate FRI = LocalDate.of(2026, 10, 30);
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    private final MarketDailyStatusRepository repo = mock(MarketDailyStatusRepository.class);
    private LocalDateTime now;
    private BigDecimal[] naver;   // null = 네이버 실패

    private final MarketTimingService service = new MarketTimingService(repo, mock(TelegramNotificationService.class),
            mock(RedisCacheService.class), new MarketCalendarService(), mock(KoreaInvestmentService.class)) {
        @Override
        BigDecimal[] crawlIndexInfo(String marketType) {
            return null;
        }

        @Override
        BigDecimal[] fetchIndexFromNaverApi(String marketType) {
            if (naver == null) throw new IllegalStateException("네이버 응답 없음");
            return naver.clone();
        }

        @Override
        LocalDateTime now() {
            return now;
        }
    };

    private static MarketDailyStatus row(String market, LocalDate date, String indexChange) {
        return MarketDailyStatus.builder()
                .marketType(market).tradeDate(date)
                .advancingCount(200).decliningCount(700).unchangedCount(20).totalCount(920)
                .indexClose(new BigDecimal("2500.00")).indexChangeRate(new BigDecimal(indexChange))
                .build();
    }

    @BeforeEach
    void setUp() {
        // 금요일 16:30 저장분 — KOSPI −2.80%(급락)
        when(repo.findLatestAll()).thenReturn(List.of(row("KOSPI", FRI, "-2.80"), row("KOSDAQ", FRI, "-1.10")));
        when(repo.findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(any(), any(), any()))
                .thenAnswer(inv -> List.of(row(inv.getArgument(0), FRI, "-1.00")));
    }

    @Test
    @DisplayName("재현: 월요일 장 전(08:00)에 네이버를 못 받으면 금요일 저장값 — '당일 급락'이 아니라 '10/30 급락'")
    void preOpenStoredValueIsLabelledWithItsSession() {
        now = MON.atTime(8, 0);
        naver = null;

        MarketTimingDto dto = service.getCurrentMarketTiming();

        assertThat(dto.getDiagnosis()).doesNotContain("당일 급락").contains("10/30 급락").contains("KOSPI -2.80%");
        assertThat(dto.getKospi().getIndexAsOf()).isEqualTo(FRI);
    }

    @Test
    @DisplayName("재현: 월요일 장 전에 네이버에서 받은 값도 금요일 세션 — 08:00 의 지수는 아직 오늘 장이 아니다")
    void preOpenNaverValueIsLastSession() {
        now = MON.atTime(8, 0);
        naver = new BigDecimal[]{new BigDecimal("2490.00"), new BigDecimal("-2.90"), new BigDecimal("1000")};

        MarketTimingDto dto = service.getCurrentMarketTiming();

        assertThat(dto.getKospi().getIndexAsOf()).isEqualTo(FRI);
        assertThat(dto.getDiagnosis()).doesNotContain("당일 급락").contains("10/30 급락").contains("KOSPI -2.90%");
    }

    @Test
    @DisplayName("토요일엔 금요일 세션 — '10/30 급락'")
    void weekendIsLastSession() {
        now = LocalDate.of(2026, 10, 31).atTime(10, 0);
        naver = new BigDecimal[]{new BigDecimal("2490.00"), new BigDecimal("-2.90"), new BigDecimal("1000")};

        MarketTimingDto dto = service.getCurrentMarketTiming();

        assertThat(dto.getKospi().getIndexAsOf()).isEqualTo(FRI);
        assertThat(dto.getDiagnosis()).contains("10/30 급락");
    }

    @Test
    @DisplayName("장중(월 10:30)에 받은 값은 오늘 세션 — 종전대로 '당일 급락'")
    void intradayIsToday() {
        now = MON.atTime(10, 30);
        naver = new BigDecimal[]{new BigDecimal("2480.00"), new BigDecimal("-2.95"), new BigDecimal("1000")};

        MarketTimingDto dto = service.getCurrentMarketTiming();

        assertThat(dto.getKospi().getIndexAsOf()).isEqualTo(MON);
        assertThat(dto.getDiagnosis()).contains("당일 급락").contains("KOSPI -2.95%");
    }

    @Test
    @DisplayName("세션 날짜 규칙 — 거래일 09:00 부터 오늘, 그 전·휴장일은 직전 거래일")
    void sessionDateRule() {
        MarketCalendarService cal = new MarketCalendarService();
        assertThat(MarketTimingService.indexSessionDate(MON.atTime(8, 59), cal)).isEqualTo(FRI);
        assertThat(MarketTimingService.indexSessionDate(MON.atTime(9, 0), cal)).isEqualTo(MON);
        // 한글날(금) 휴장 — 직전 거래일 목요일
        assertThat(MarketTimingService.indexSessionDate(LocalDate.of(2026, 10, 9).atTime(11, 0), cal))
                .isEqualTo(LocalDate.of(2026, 10, 8));
    }
}
