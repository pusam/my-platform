package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KOSPI200 선물 근월물 단축코드 — 순수 계산 검증.
 *
 * 코드 형식은 2026-07-16 KIS 선물 마스터(fo_idx_code.mst) 실물 확정: "1A01" + 연도끝자리 + 월2자리
 * (1A01609 = 2026년 9월물, 1A01703 = 2027년 3월물). 만기 = 분기월(3/6/9/12) 두 번째 목요일,
 * 만기일 당일까지 해당 분기물 유지.
 */
class GlobalFuturesFrontMonthTest {

    @Test
    @DisplayName("2026-07-16 → 9월물 1A01609 (6월 만기 지난 뒤, 마스터 실물과 일치)")
    void midJuly_septemberContract() {
        assertThat(GlobalFuturesService.calculateFrontMonthCode(LocalDate.of(2026, 7, 16)))
                .isEqualTo("1A01609");
    }

    @Test
    @DisplayName("만기 경계 — 2026-06 둘째 목(6/11) 당일까지 6월물, 다음날부터 9월물")
    void expiryBoundary_june2026() {
        LocalDate secondThu = LocalDate.of(2026, 6, 11);   // 2026년 6월 둘째 목요일
        assertThat(GlobalFuturesService.calculateFrontMonthCode(secondThu)).isEqualTo("1A01606");
        assertThat(GlobalFuturesService.calculateFrontMonthCode(secondThu.plusDays(1))).isEqualTo("1A01609");
    }

    @Test
    @DisplayName("연말 롤 — 12월 만기 후엔 내년 3월물 (연도끝자리 증가)")
    void yearEndRoll_toNextMarch() {
        LocalDate decSecondThu = LocalDate.of(2026, 12, 10);   // 2026년 12월 둘째 목요일
        assertThat(GlobalFuturesService.calculateFrontMonthCode(decSecondThu)).isEqualTo("1A01612");
        assertThat(GlobalFuturesService.calculateFrontMonthCode(decSecondThu.plusDays(1))).isEqualTo("1A01703");
    }

    @Test
    @DisplayName("월 2자리 zero-pad — 3월물은 '03' (마스터 1A01703 레이아웃)")
    void monthZeroPadded() {
        assertThat(GlobalFuturesService.calculateFrontMonthCode(LocalDate.of(2027, 1, 5)))
                .isEqualTo("1A01703");
    }
}
