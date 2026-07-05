package com.myplatform.backend.util;

import com.myplatform.backend.util.PriceSanityGuard.Result;
import com.myplatform.backend.util.PriceSanityGuard.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 봇 진입 가격 sanity 가드 테스트.
 *
 * <p>규약 고정:
 * <ul>
 *   <li>전일 종가(J 소스 앵커) 대비 ±50% <b>초과</b>만 BLOCKED — KRX 일일 제한 ±30% 정상 등락은 통과.</li>
 *   <li>앵커 결측/0/오래됨(4일 초과)은 UNKNOWN=통과 — 결측을 근거로 차단하지 않는다(§4c).</li>
 *   <li>가격 보정 없음 — 판정만 반환(§16-3 비충돌).</li>
 * </ul>
 */
class PriceSanityGuardTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 6);   // 월요일
    private static final LocalDate FRIDAY = LocalDate.of(2026, 7, 3);  // 직전 거래일(3일 전)

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    @Nested
    @DisplayName("정상 가격 — 통과")
    class Sane {
        @Test
        @DisplayName("전일 종가 70000 → 진입가 72100(+3%) = SANE")
        void normalMove_isSane() {
            Result r = PriceSanityGuard.judge(bd("72100"), bd("70000"), FRIDAY, TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.SANE);
            assertThat(r.ratio()).isEqualByComparingTo("1.03");
        }

        @Test
        @DisplayName("상한가 +30%(91000/70000) = SANE — 정상 장 오탐 없음")
        void limitUp_isSane() {
            Result r = PriceSanityGuard.judge(bd("91000"), bd("70000"), FRIDAY, TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.SANE);
        }

        @Test
        @DisplayName("경계: 정확히 ×1.5(105000/70000) = SANE — '초과'만 차단")
        void exactlyOnePointFive_isSane() {
            Result r = PriceSanityGuard.judge(bd("105000"), bd("70000"), FRIDAY, TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.SANE);
            assertThat(r.ratio()).isEqualByComparingTo("1.50");
        }

        @Test
        @DisplayName("경계: 정확히 ×0.5(35000/70000) = SANE — 하방도 '초과'만 차단")
        void exactlyHalf_isSane() {
            Result r = PriceSanityGuard.judge(bd("35000"), bd("70000"), FRIDAY, TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.SANE);
        }
    }

    @Nested
    @DisplayName("×10 오염 — 차단")
    class Blocked {
        @Test
        @DisplayName("×10(700000/70000) = BLOCKED, ratio 10")
        void tenTimes_isBlocked() {
            Result r = PriceSanityGuard.judge(bd("700000"), bd("70000"), FRIDAY, TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.BLOCKED);
            assertThat(r.ratio()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("×0.1(7000/70000) = BLOCKED — 저측 글리치도 차단")
        void oneTenth_isBlocked() {
            Result r = PriceSanityGuard.judge(bd("7000"), bd("70000"), FRIDAY, TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.BLOCKED);
            assertThat(r.ratio()).isEqualByComparingTo("0.10");
        }

        @Test
        @DisplayName("+51%(105700/70000) = BLOCKED — 임계 바로 위")
        void justOverThreshold_isBlocked() {
            Result r = PriceSanityGuard.judge(bd("105700"), bd("70000"), FRIDAY, TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.BLOCKED);
        }
    }

    @Nested
    @DisplayName("앵커 결측/노후 — UNKNOWN(통과, §4c)")
    class Unknown {
        @Test
        @DisplayName("앵커 null = UNKNOWN — 결측을 근거로 차단하지 않음")
        void nullAnchor_isUnknown() {
            Result r = PriceSanityGuard.judge(bd("700000"), null, FRIDAY, TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.UNKNOWN);
        }

        @Test
        @DisplayName("앵커 0 = UNKNOWN")
        void zeroAnchor_isUnknown() {
            Result r = PriceSanityGuard.judge(bd("700000"), BigDecimal.ZERO, FRIDAY, TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.UNKNOWN);
        }

        @Test
        @DisplayName("앵커 일자 null = UNKNOWN — 신선도 판단 불가")
        void nullAnchorDate_isUnknown() {
            Result r = PriceSanityGuard.judge(bd("700000"), bd("70000"), null, TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.UNKNOWN);
        }

        @Test
        @DisplayName("앵커 5일 전(>4일) = UNKNOWN — 연휴+연속 상한가 오탐 방지")
        void staleAnchor_isUnknown() {
            Result r = PriceSanityGuard.judge(bd("700000"), bd("70000"), TODAY.minusDays(5), TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.UNKNOWN);
        }

        @Test
        @DisplayName("경계: 앵커 정확히 4일 전 = 판정 유효(×10이면 BLOCKED)")
        void fourDayAnchor_stillJudges() {
            Result r = PriceSanityGuard.judge(bd("700000"), bd("70000"), TODAY.minusDays(4), TODAY);
            assertThat(r.verdict()).isEqualTo(Verdict.BLOCKED);
        }

        @Test
        @DisplayName("진입가 null/0 = UNKNOWN — 판정 불가")
        void nullPrice_isUnknown() {
            assertThat(PriceSanityGuard.judge(null, bd("70000"), FRIDAY, TODAY).verdict())
                    .isEqualTo(Verdict.UNKNOWN);
            assertThat(PriceSanityGuard.judge(BigDecimal.ZERO, bd("70000"), FRIDAY, TODAY).verdict())
                    .isEqualTo(Verdict.UNKNOWN);
        }
    }
}
