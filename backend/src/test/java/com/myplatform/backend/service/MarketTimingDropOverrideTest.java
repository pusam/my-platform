package com.myplatform.backend.service;

import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.entity.MarketDailyStatus;
import com.myplatform.backend.repository.MarketDailyStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 당일 급락 보정 — ADR 판단 보류(시장 폭 수집 재개 후 15일 미만) 중에도 시장 상태가 계산된다(2026-10-03).
 *
 * <p>예전엔 급락 분기(한쪽 −2.5% 이하 또는 양쪽 −2% 이하, −3% 미만)가 null 상태에서 {@code getSuggestion()} 을 불러 NPE —
 * '오늘' 탭 시장 줄·상단 바·시장 타이밍·07:30 모닝브리핑·AI 예측이 한꺼번에 실패했다. VKOSPI 40 안팎이라 흔한 날이다.
 */
class MarketTimingDropOverrideTest {

    private final MarketDailyStatusRepository repo = mock(MarketDailyStatusRepository.class);

    private final MarketTimingService service = new MarketTimingService(repo, mock(TelegramNotificationService.class),
            mock(RedisCacheService.class), new MarketCalendarService(), mock(KoreaInvestmentService.class)) {
        @Override
        BigDecimal[] crawlIndexInfo(String marketType) {
            return null;
        }

        @Override
        BigDecimal[] fetchIndexFromNaverApi(String marketType) {
            return null;
        }
    };

    private static MarketDailyStatus row(String market, String indexChange) {
        return MarketDailyStatus.builder()
                .marketType(market).tradeDate(LocalDate.of(2026, 10, 6))
                .advancingCount(200).decliningCount(700).unchangedCount(20).totalCount(920)
                .indexClose(new BigDecimal("6800.00")).indexChangeRate(new BigDecimal(indexChange))
                .build();
    }

    @Test
    @DisplayName("재현: ADR 판단 보류 + 코스피 −2.8% → 예전엔 NPE. 이제 상태는 모름, 진단은 낙폭 사실과 판단 보류")
    void dropWhileAdrOnHoldDoesNotCrash() {
        when(repo.findLatestAll()).thenReturn(List.of(row("KOSPI", "-2.80"), row("KOSDAQ", "-1.10")));
        // 창 안 유효일 1일 — 15일 미만이라 ADR 은 판단 보류(null)
        when(repo.findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(any(), any(), any()))
                .thenAnswer(inv -> List.of(row(inv.getArgument(0), "-1.00")));

        MarketTimingDto dto = service.getCurrentMarketTiming();

        assertThat(dto.getCombinedAdr()).isNull();
        assertThat(dto.getOverallCondition()).isNull();
        assertThat(dto.getDiagnosis()).contains("당일 급락").contains("KOSPI -2.80%").contains("판단 보류");
    }

    @Test
    @DisplayName("−3% 이하 폭락은 종전대로 CRASH(ADR 과 무관)")
    void crashStillApplies() {
        when(repo.findLatestAll()).thenReturn(List.of(row("KOSPI", "-3.40"), row("KOSDAQ", "-1.10")));
        when(repo.findByMarketTypeAndTradeDateBetweenOrderByTradeDateDesc(any(), any(), any()))
                .thenAnswer(inv -> List.of(row(inv.getArgument(0), "-1.00")));

        MarketTimingDto dto = service.getCurrentMarketTiming();

        assertThat(dto.getOverallCondition()).isEqualTo(MarketTimingDto.MarketCondition.CRASH);
    }
}
