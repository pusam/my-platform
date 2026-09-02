package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 데이터 이상 판정 규칙 ⑫⑬ — KIS 호출 실패 (2026-09-02 실사고 둘에서 신설).
 *
 * <p>둘 다 <b>예외가 안 나는 실패</b>였다: 분봉은 필수 파라미터 누락에 KIS 가 200 + rt_cd≠0 으로 답했고,
 * 잔고는 같은 초 충돌(EGW00215)이 ERROR 로그 속에 묻혀 있었다. 그날의 실측 수치로 "그때 이 규칙이 있었다면
 * 울렸을까"를 고정하고, 정상일 때 조용한지도 같이 고정한다.
 */
class DataAnomalyRulesKisTest {

    @Nested
    @DisplayName("KIS 분봉 API 실패율")
    class MinuteChart {

        @Test
        @DisplayName("2026-09-01 실측 재현 — 5회 시도 5회 실패(100%)면 울린다")
        void reproducesTheRealIncident() {
            DataAnomalyRules.Anomaly a = DataAnomalyRules.minuteChartFailureRate(5, 5);

            assertThat(a).isNotNull();
            assertThat(a.severity()).isEqualTo(DataAnomalyRules.WARNING);
            assertThat(a.key()).isEqualTo("kis-minute-chart-failing");
            assertThat(a.title()).contains("100%");
            assertThat(a.detail()).contains("buildMinuteChartUrl").contains("INPUT FIELD NOT FOUND");
        }

        @Test
        @DisplayName("절반 실패도 울린다 — 파라미터 누락은 전부 실패지만 토큰 간헐 장애도 잡아야 한다")
        void halfFailingRings() {
            assertThat(DataAnomalyRules.minuteChartFailureRate(10, 5)).isNotNull();
        }

        @Test
        @DisplayName("정상이면 조용하다 — 200회 중 3회 실패는 KIS 의 평상시 잡음")
        void healthyIsSilent() {
            assertThat(DataAnomalyRules.minuteChartFailureRate(200, 3)).isNull();
            assertThat(DataAnomalyRules.minuteChartFailureRate(200, 0)).isNull();
        }

        @Test
        @DisplayName("시도가 적으면 판정 보류 — 장전·휴장·아무도 안 열었음은 정상 0 (§4c)")
        void tooFewAttemptsIsSilent() {
            assertThat(DataAnomalyRules.minuteChartFailureRate(0, 0)).isNull();
            assertThat(DataAnomalyRules.minuteChartFailureRate(4, 4)).isNull();
        }
    }

    @Nested
    @DisplayName("KIS 잔고 조회 실패")
    class Balance {

        @Test
        @DisplayName("2026-09-01 실측 재현 — 하루 24건 실패면 울린다")
        void reproducesTheRealIncident() {
            DataAnomalyRules.Anomaly a = DataAnomalyRules.balanceLookupFailures(60, 24);

            assertThat(a).isNotNull();
            assertThat(a.severity()).isEqualTo(DataAnomalyRules.WARNING);
            assertThat(a.key()).isEqualTo("kis-balance-failing");
            assertThat(a.title()).contains("24건");
            assertThat(a.detail()).contains("EGW00215").contains("단일 비행");
        }

        @Test
        @DisplayName("단일 비행 후 정상 — 0건은 조용하고, 간헐 4건도 아직 안 울린다")
        void healthyIsSilent() {
            assertThat(DataAnomalyRules.balanceLookupFailures(60, 0)).isNull();
            assertThat(DataAnomalyRules.balanceLookupFailures(60, 4)).isNull();
        }

        @Test
        @DisplayName("임계(5건)부터 울린다")
        void thresholdRings() {
            assertThat(DataAnomalyRules.balanceLookupFailures(60, 5)).isNotNull();
        }
    }
}
