package com.myplatform.backend.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수급 급증 스냅샷의 낡음 — 날짜까지 본다(2026-10-03).
 *
 * <p>재현: 오늘 장중 스냅샷이 아직 없으면(09:00~09:02, 수집이 멈춘 날) 서버가 직전 거래일 스냅샷을 돌려주는데, 낡음 판정이
 * 시각만 비교해 어제 15:30 을 오늘 09:05 와 비교하면 음수 → 0분 → '최신'이었다. 화면은 어제 카드를 갱신 지연 표시 없이
 * '실시간' 아래에 세웠다.
 */
class InvestorSurgeDtoSessionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 9, 5);

    @Test
    @DisplayName("재현: 직전 거래일 15:30 스냅샷은 오늘 09:05 에 낡음이고 직전 세션이다")
    void previousSessionIsOutdated() {
        LocalDate prev = LocalDate.of(2026, 10, 2);
        assertThat(InvestorSurgeDto.outdatedAt(prev, LocalTime.of(15, 30), NOW)).isTrue();
        assertThat(InvestorSurgeDto.previousSessionAt(prev, NOW)).isTrue();
    }

    @Test
    @DisplayName("오늘 스냅샷은 종전대로 시각 차이로 — 15분 미만이면 최신")
    void todayUsesMinutes() {
        LocalDate today = NOW.toLocalDate();
        assertThat(InvestorSurgeDto.outdatedAt(today, LocalTime.of(9, 0), NOW)).isFalse();
        assertThat(InvestorSurgeDto.outdatedAt(today, LocalTime.of(8, 40), NOW)).isTrue();
        assertThat(InvestorSurgeDto.previousSessionAt(today, NOW)).isFalse();
    }

    @Test
    @DisplayName("날짜를 모르면 종전 규칙(시각만) — 지어내지 않는다")
    void unknownDateFallsBackToTime() {
        assertThat(InvestorSurgeDto.outdatedAt(null, LocalTime.of(9, 0), NOW)).isFalse();
        assertThat(InvestorSurgeDto.previousSessionAt(null, NOW)).isFalse();
    }
}
