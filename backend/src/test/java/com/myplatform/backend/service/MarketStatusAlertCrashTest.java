package com.myplatform.backend.service;

import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.dto.MarketTimingDto.MarketCondition;
import com.myplatform.backend.repository.MarketDailyStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 장 마감 시장 상태 알림(16:45). 과열·극심한 공포만 보내서 코스피가 5% 넘게 빠진 날(2026-11-05)에도 폭락 알림이 없었다.
 */
class MarketStatusAlertCrashTest {

    private final TelegramNotificationService telegram = mock(TelegramNotificationService.class);

    private MarketTimingService serviceWith(MarketCondition condition) {
        return new MarketTimingService(mock(MarketDailyStatusRepository.class), telegram, mock(RedisCacheService.class),
                new MarketCalendarService(), mock(KoreaInvestmentService.class)) {
            @Override
            public MarketTimingDto getCurrentMarketTiming() {
                return MarketTimingDto.builder().overallCondition(condition)
                        .combinedAdr(new BigDecimal("76.0")).diagnosis("진단").build();
            }
        };
    }

    @Test
    @DisplayName("재현: 폭락 상태인데 알림이 나가지 않았다")
    void crashIsExtreme() {
        assertThat(serviceWith(MarketCondition.CRASH).sendMarketStatusAlert(true)).isTrue();
        verify(telegram).sendMarketStatusAlert(any(), any(), any());
    }

    @Test
    @DisplayName("보통 날은 그대로 보내지 않는다")
    void normalStaysQuiet() {
        assertThat(serviceWith(MarketCondition.NORMAL).sendMarketStatusAlert(true)).isFalse();
        verify(telegram, never()).sendMarketStatusAlert(any(), any(), any());
    }
}
