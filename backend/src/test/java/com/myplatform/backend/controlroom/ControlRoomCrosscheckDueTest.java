package com.myplatform.backend.controlroom;

import com.myplatform.backend.service.MarketCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 외부 대조 기한(⑯ 입력) — 거래일 10:30(늦게 여는 날은 장 시작 + 30분 중 늦은 쪽)이 지나면 오늘 실행이 있어야 하고, 그 전·휴장일은
 * 직전 거래일까지만 본다. 실행 기록이 없으면 이 프로세스가 뜬 뒤 기한이 지났는지로 본다(독립 검토 A2·B6, 2026-11-11).
 */
class ControlRoomCrosscheckDueTest {

    private static final LocalDate TUE = LocalDate.of(2026, 11, 10);
    private static final LocalDate WED = LocalDate.of(2026, 11, 11);
    private static final LocalDate SAT = LocalDate.of(2026, 11, 14);
    private static final LocalDate FRI = LocalDate.of(2026, 11, 13);
    private final MarketCalendarService cal = mock(MarketCalendarService.class);

    @BeforeEach
    void setUp() {
        when(cal.isMarketClosed(any(LocalDate.class))).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return d.getDayOfWeek().getValue() >= 6;
        });
        when(cal.marketOpenOf(any())).thenReturn(LocalTime.of(9, 0));
        when(cal.minusTradingDays(eq(WED), eq(1))).thenReturn(TUE);
        when(cal.minusTradingDays(eq(SAT), eq(1))).thenReturn(FRI);
    }

    private static final LocalDateTime BOOT = LocalDateTime.of(2026, 11, 1, 0, 0);

    @Test
    @DisplayName("거래일 10:30 전엔 직전 거래일까지만 — 오늘 실행이 아직 없어도 조용하다")
    void beforeDue() {
        var due = ControlRoomSnapshotService.crosscheckDue(LocalDateTime.of(WED, LocalTime.of(10, 29)), TUE, BOOT, cal);
        assertThat(due.expectedRunDate()).isEqualTo(TUE);
        assertThat(due.missedTradingDays()).isZero();
    }

    @Test
    @DisplayName("거래일 10:30 이 지나 오늘 실행이 없으면 그날 바로 1일 빠짐")
    void afterDueMissedToday() {
        var due = ControlRoomSnapshotService.crosscheckDue(LocalDateTime.of(WED, LocalTime.of(10, 30)), TUE, BOOT, cal);
        assertThat(due.expectedRunDate()).isEqualTo(WED);
        assertThat(due.missedTradingDays()).isEqualTo(1);
        assertThat(ControlRoomSnapshotService.crosscheckDue(LocalDateTime.of(WED, LocalTime.of(11, 0)), WED, BOOT, cal)
                .missedTradingDays()).isZero();
    }

    @Test
    @DisplayName("늦게 여는 날(10:00 개장)도 기한은 10:30 — 장 시작 + 30분")
    void lateOpenDay() {
        when(cal.marketOpenOf(WED)).thenReturn(LocalTime.of(10, 0));
        assertThat(ControlRoomSnapshotService.crosscheckDueAt(WED, cal)).isEqualTo(LocalDateTime.of(WED, LocalTime.of(10, 30)));
        when(cal.marketOpenOf(WED)).thenReturn(LocalTime.of(10, 30));
        assertThat(ControlRoomSnapshotService.crosscheckDueAt(WED, cal)).isEqualTo(LocalDateTime.of(WED, LocalTime.of(11, 0)));
    }

    @Test
    @DisplayName("휴장일엔 직전 거래일까지만 본다 — 토요일에 금요일 실행이 있으면 조용하다")
    void holiday() {
        var due = ControlRoomSnapshotService.crosscheckDue(LocalDateTime.of(SAT, LocalTime.of(12, 0)), FRI, BOOT, cal);
        assertThat(due.expectedRunDate()).isEqualTo(FRI);
        assertThat(due.missedTradingDays()).isZero();
    }

    @Test
    @DisplayName("실행 기록이 없으면 — 이 프로세스가 뜬 뒤 기한이 지났을 때만 '돌았어야 했다'")
    void firstRunDue() {
        LocalDateTime now = LocalDateTime.of(WED, LocalTime.of(11, 0));
        assertThat(ControlRoomSnapshotService.crosscheckDue(now, null, LocalDateTime.of(WED, LocalTime.of(9, 0)), cal).firstRunDue()).isTrue();
        assertThat(ControlRoomSnapshotService.crosscheckDue(now, null, LocalDateTime.of(WED, LocalTime.of(10, 45)), cal).firstRunDue())
                .as("기한 뒤에 뜬 프로세스(장 마감 뒤 배포 등)는 아직 첫 실행 전").isFalse();
        assertThat(ControlRoomSnapshotService.crosscheckDue(now, null, null, cal).firstRunDue()).isFalse();
    }
}
