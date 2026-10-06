package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시장 폭 '오래됨'은 거래일로 판정한다(2026-10-06).
 *
 * <p>재현: 시장 타이밍 화면이 달력 날짜로 세어 매주 월요일 "3일 전 데이터", 연휴 다음 날(10/6 화) "4일 전 데이터" 배지를 달고
 * 부팅마다 "⚠ 시장 데이터 오래됨" WARN 을 남겼다 — 그때 10/2 값은 받을 수 있는 가장 최신(오늘 값은 16:30 수집)이다. 관제실 규칙 ⑮
 * 와 같은 기준 — 마지막 마감 거래일에서 1거래일까지는 정상(15:40 마감~16:30 수집 사이 포함), 그보다 밀리면 오래됨.
 */
class MarketTimingStaleBadgeTest {

    private final MarketCalendarService calendar = new MarketCalendarService();

    @Test
    @DisplayName("재현: 연휴(10/3~10/5) 다음 날 장중 — 10/2 값은 최신이다")
    void dayAfterLongHolidayIsNotStale() {
        assertThat(MarketTimingService.breadthStale(LocalDate.of(2026, 10, 2), LocalDateTime.of(2026, 10, 6, 14, 0), calendar))
                .isFalse();
    }

    @Test
    @DisplayName("월요일 아침 — 금요일 값은 최신이다(한글날 10/9 금 휴장이면 목요일 값)")
    void mondayMorningIsNotStale() {
        assertThat(MarketTimingService.breadthStale(LocalDate.of(2026, 10, 8), LocalDateTime.of(2026, 10, 12, 9, 0), calendar))
                .isFalse();
    }

    @Test
    @DisplayName("마감(15:40) 뒤 16:30 수집 전 — 전 거래일 값은 아직 정상")
    void betweenCloseAndCollectionIsNotStale() {
        assertThat(MarketTimingService.breadthStale(LocalDate.of(2026, 10, 6), LocalDateTime.of(2026, 10, 7, 16, 0), calendar))
                .isFalse();
    }

    @Test
    @DisplayName("수집이 거래일 하루 이상 빠지면 오래됨 — 10/7 저녁에 아직 10/2 값")
    void missedCollectionIsStale() {
        assertThat(MarketTimingService.breadthStale(LocalDate.of(2026, 10, 2), LocalDateTime.of(2026, 10, 7, 17, 0), calendar))
                .isTrue();
    }
}
