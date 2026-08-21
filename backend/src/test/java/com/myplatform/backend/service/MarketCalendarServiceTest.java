package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-7 (정규장 종료 15:40 경계) · P2-8 (음력 공휴일) · P2-9 (NXT/KRX 경계 특성화).
 */
class MarketCalendarServiceTest {

    private final MarketCalendarService svc = new MarketCalendarService();

    /** 평일·비휴일 (2026-06-04 목). 시각 경계만 보기 위한 고정 날짜. */
    private static final LocalDate WEEKDAY = LocalDate.of(2026, 6, 4);

    @Nested
    @DisplayName("P2-7: 정규장 종료 15:40 (종가 단일가매매 버퍼) 경계")
    class RegularSessionBoundary {
        @Test @DisplayName("09:00 개장 경계 포함 → true")
        void openInclusive() { assertThat(svc.isRegularSession(WEEKDAY, LocalTime.of(9, 0))).isTrue(); }

        @Test @DisplayName("08:59 개장 직전 → false")
        void beforeOpen() { assertThat(svc.isRegularSession(WEEKDAY, LocalTime.of(8, 59))).isFalse(); }

        @Test @DisplayName("15:30 접속매매 종료 → 아직 정규장(true)")
        void at1530() { assertThat(svc.isRegularSession(WEEKDAY, LocalTime.of(15, 30))).isTrue(); }

        @Test @DisplayName("15:31 종가단일가 구간 → true (15:40 버퍼)")
        void at1531() { assertThat(svc.isRegularSession(WEEKDAY, LocalTime.of(15, 31))).isTrue(); }

        @Test @DisplayName("15:40 종료 경계 포함 → true")
        void closeInclusive() { assertThat(svc.isRegularSession(WEEKDAY, LocalTime.of(15, 40))).isTrue(); }

        @Test @DisplayName("15:41 종료 직후 → false")
        void afterClose() { assertThat(svc.isRegularSession(WEEKDAY, LocalTime.of(15, 41))).isFalse(); }
    }

    @Nested
    @DisplayName("P2-8: 음력 유래 공휴일은 휴장 + 정규장 false")
    class LunarHolidays {
        @Test @DisplayName("2026-02-17 설날 → 휴장")
        void seollal2026() {
            assertThat(svc.isMarketClosed(LocalDate.of(2026, 2, 17))).isTrue();
            assertThat(svc.isRegularSession(LocalDate.of(2026, 2, 17), LocalTime.of(10, 0))).isFalse();
        }

        @Test @DisplayName("2026-09-25 추석 → 휴장")
        void chuseok2026() {
            assertThat(svc.isMarketClosed(LocalDate.of(2026, 9, 25))).isTrue();
            assertThat(svc.isRegularSession(LocalDate.of(2026, 9, 25), LocalTime.of(10, 0))).isFalse();
        }

        @Test @DisplayName("2027-05-13 부처님오신날(평일) → 휴장")
        void buddha2027() {
            assertThat(svc.isMarketClosed(LocalDate.of(2027, 5, 13))).isTrue();
        }

        @Test @DisplayName("음력 연휴 다음 평일(2026-02-19 목)은 정상 개장")
        void dayAfterSeollalOpen() {
            assertThat(svc.isMarketClosed(LocalDate.of(2026, 2, 19))).isFalse();
            assertThat(svc.isRegularSession(LocalDate.of(2026, 2, 19), LocalTime.of(10, 0))).isTrue();
        }
    }

    @Nested
    @DisplayName("대체공휴일·근로자의날·연말폐장 (2026-08-21 보강) — 미수록 시 다음 거래일 오전 수급·기술 동시 미채점")
    class SubstituteHolidays {
        @Test @DisplayName("2026-05-01 근로자의날(금) → 휴장 (법정공휴일 아님·KRX 휴장)")
        void laborDay2026() {
            assertThat(svc.isMarketClosed(LocalDate.of(2026, 5, 1))).isTrue();
        }

        @Test @DisplayName("2026-08-17 광복절 대체휴일(월) → 휴장 — 실제로 미수록 상태로 지나간 날")
        void liberationSubstitute2026() {
            assertThat(svc.isMarketClosed(LocalDate.of(2026, 8, 17))).isTrue();
            assertThat(svc.isRegularSession(LocalDate.of(2026, 8, 17), LocalTime.of(10, 0))).isFalse();
        }

        @Test @DisplayName("2026-09-28 추석 대체휴일(월) / 2026-10-05 개천절 대체휴일(월) → 휴장")
        void chuseokAndFoundationSubstitute2026() {
            assertThat(svc.isMarketClosed(LocalDate.of(2026, 9, 28))).isTrue();
            assertThat(svc.isMarketClosed(LocalDate.of(2026, 10, 5))).isTrue();
        }

        @Test @DisplayName("2026-12-31 연말 폐장일(목) → 휴장")
        void yearEndClosure2026() {
            assertThat(svc.isMarketClosed(LocalDate.of(2026, 12, 31))).isTrue();
        }

        @Test @DisplayName("대체휴일 다음 평일(2026-08-18 화)은 정상 개장")
        void dayAfterSubstituteOpen() {
            assertThat(svc.isMarketClosed(LocalDate.of(2026, 8, 18))).isFalse();
        }

        @Test @DisplayName("minusTradingDays 가 8/17 대체휴일을 건너뛴다 — 3거래일 평가 컷오프 정합")
        void minusTradingDaysSkipsSubstitute() {
            // 2026-08-19 수 → 8/18 화(1), [8/17 월 휴장 + 주말 skip], 8/14 금(2), 8/13 목(3)
            assertThat(svc.minusTradingDays(LocalDate.of(2026, 8, 19), 3))
                    .isEqualTo(LocalDate.of(2026, 8, 13));
        }
    }

    @Nested
    @DisplayName("minusTradingDays: 거래일 역산 (시그널 3거래일 평가 컷오프)")
    class MinusTradingDays {
        @Test @DisplayName("월요일 기준 3거래일 전 = 수요일 (주말 건너뜀) — 달력일이면 금요일이 되던 버그")
        void mondayGoesBackToWednesday() {
            // 2026-07-13 월 → 금(1), 목(2), 수(3)
            assertThat(svc.minusTradingDays(LocalDate.of(2026, 7, 13), 3))
                    .isEqualTo(LocalDate.of(2026, 7, 8));
        }

        @Test @DisplayName("목요일 기준 3거래일 전 = 월요일 (주중, 달력일과 동일)")
        void thursdayGoesBackToMonday() {
            // 2026-07-16 목 → 수(1), 화(2), 월(3)
            assertThat(svc.minusTradingDays(LocalDate.of(2026, 7, 16), 3))
                    .isEqualTo(LocalDate.of(2026, 7, 13));
        }

        @Test @DisplayName("설 연휴(2026-02-16~18) 걸치면 연휴만큼 더 역행")
        void skipsLunarHolidays() {
            // 2026-02-19 목 → 2/13 금(1), 2/12 목(2), 2/11 수(3) — 2/16~18 설 연휴 + 주말 건너뜀
            assertThat(svc.minusTradingDays(LocalDate.of(2026, 2, 19), 3))
                    .isEqualTo(LocalDate.of(2026, 2, 11));
        }

        @Test @DisplayName("0거래일 = 그대로 반환")
        void zeroReturnsSame() {
            assertThat(svc.minusTradingDays(WEEKDAY, 0)).isEqualTo(WEEKDAY);
        }
    }

    @Nested
    @DisplayName("P2-9: NXT(08~20) vs KRX 정규장(09~15:40) 경계는 의도적으로 다름")
    class NxtVsKrxGap {
        // 표시/추천/캐시워밍 = NXT 08~20 / 봇·섹터·정규장 판정 = KRX 09~15:40 (불변식 2).
        // NXT 단독 구간(08~09, 15:40~20:00)에는 KRX 정규장이 닫혀 있어야 한다.

        @Test @DisplayName("08:30 NXT 프리마켓 → KRX 정규장 닫힘")
        void premarketNotKrxRegular() {
            assertThat(svc.isRegularSession(WEEKDAY, LocalTime.of(8, 30))).isFalse();
            // 같은 시각, NXT 세션 분류는 PREMARKET (이미 PriceScalingDiagnosticServiceTest 에서 고정)
            assertThat(PriceScalingDiagnosticService.sessionOf(WEEKDAY.atTime(8, 30)))
                    .isEqualTo("NXT_PREMARKET");
        }

        @Test @DisplayName("16:00 NXT 애프터마켓 → KRX 정규장 닫힘")
        void afterhoursNotKrxRegular() {
            assertThat(svc.isRegularSession(WEEKDAY, LocalTime.of(16, 0))).isFalse();
            assertThat(PriceScalingDiagnosticService.sessionOf(WEEKDAY.atTime(16, 0)))
                    .isEqualTo("NXT_AFTERHOURS");
        }

        @Test @DisplayName("10:00 중첩 구간 → KRX 정규장 + NXT KRX_REGULAR 라벨 일치")
        void overlapBothOpen() {
            assertThat(svc.isRegularSession(WEEKDAY, LocalTime.of(10, 0))).isTrue();
            assertThat(PriceScalingDiagnosticService.sessionOf(WEEKDAY.atTime(10, 0)))
                    .isEqualTo("KRX_REGULAR");
        }
    }
}
