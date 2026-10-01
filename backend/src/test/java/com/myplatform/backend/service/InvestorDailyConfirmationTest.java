package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 투자자 일별 수급 확정 판정 — {@link InvestorDailyConfirmation}(2026-10-01). */
class InvestorDailyConfirmationTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 30);

    @Test
    @DisplayName("9/30 실측 재현 — 11:13 부팅이 쓴 잠정 행은 확정이 아니다")
    void provisionalRowsAreNotConfirmed() {
        assertThat(InvestorDailyConfirmation.isConfirmed(D, List.of(
                new Object[]{"FOREIGN", D.atTime(11, 13, 16)},
                new Object[]{"INSTITUTION", D.atTime(11, 13, 17)}))).isFalse();
    }

    @Test
    @DisplayName("15:50 정규 수집이 외국인·기관을 다 쓰면 확정 — 연기금 0건은 조건이 아니다")
    void confirmedAfterCutoffWithBothTypes() {
        assertThat(InvestorDailyConfirmation.isConfirmed(D, List.of(
                new Object[]{"FOREIGN", D.atTime(15, 50, 0)},
                new Object[]{"INSTITUTION", D.atTime(15, 50, 3)}))).isTrue();
    }

    @Test
    @DisplayName("한쪽만 있으면(부분 수집) 확정이 아니다")
    void partialIsNotConfirmed() {
        assertThat(InvestorDailyConfirmation.isConfirmed(D,
                List.<Object[]>of(new Object[]{"FOREIGN", D.atTime(15, 50, 1)}))).isFalse();
    }

    @Test
    @DisplayName("한 행이라도 확정 시각 전에 쓰였으면 확정이 아니다 / 저장 시각을 모르면 확정으로 단정하지 않는다")
    void anyEarlyRowOrUnknownTimeIsNotConfirmed() {
        assertThat(InvestorDailyConfirmation.isConfirmed(D, List.of(
                new Object[]{"FOREIGN", D.atTime(15, 50, 1)},
                new Object[]{"INSTITUTION", D.atTime(14, 0)}))).isFalse();
        assertThat(InvestorDailyConfirmation.isConfirmed(D, List.of(
                new Object[]{"FOREIGN", null}, new Object[]{"INSTITUTION", null}))).isFalse();
        assertThat(InvestorDailyConfirmation.isConfirmed(D, List.of())).isFalse();
    }

    @Test
    @DisplayName("확정 수집 시각은 15:50 부터")
    void confirmedWindow() {
        assertThat(InvestorDailyConfirmation.isConfirmedWindow(D.atTime(15, 49, 59))).isFalse();
        assertThat(InvestorDailyConfirmation.isConfirmedWindow(D.atTime(15, 50))).isTrue();
        assertThat(InvestorDailyConfirmation.isConfirmedWindow(D.atTime(21, 0))).isTrue();
    }
}
