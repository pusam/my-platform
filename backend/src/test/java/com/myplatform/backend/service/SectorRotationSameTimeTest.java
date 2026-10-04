package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 섹터 자금 흐름(로테이션) — 같은 시각끼리 비교한다(2026-10-04).
 *
 * <p>재현: 오늘 <b>장중 누적</b> 거래대금을 직전 거래일 <b>하루 전체</b> 거래대금과 비교해(15:35 스냅샷) 오전엔 거의 모든
 * 섹터가 −50% 안팎 = '유출'(임계 −10%)이었다 — 11:30 이면 오늘 값은 하루치의 절반쯤이다. 15:35 이후엔 그 스냅샷이 오늘
 * 값으로 바뀌어 오늘끼리 비교(0% = 전부 중립)였고, 전일 값이 없으면 가격 등락률을 '자금 흐름'이라 불렀다. 봇의 유출 섹터
 * 필터(isOutflowSectorStock)가 이 값을 쓴다.
 */
class SectorRotationSameTimeTest {

    private static Map<String, BigDecimal> totals(String sector, String eok) {
        return Map.of(sector, new BigDecimal(eok));
    }

    private static NavigableMap<LocalTime, Map<String, BigDecimal>> prevDay() {
        NavigableMap<LocalTime, Map<String, BigDecimal>> m = new TreeMap<>();
        m.put(LocalTime.of(11, 27), totals("SEMI", "4000"));
        m.put(LocalTime.of(11, 30), totals("SEMI", "4100"));
        m.put(LocalTime.of(15, 39), totals("SEMI", "9000"));
        return m;
    }

    @Test
    @DisplayName("재현: 장중엔 직전 거래일 같은 시각(이하 가장 가까운 3분 스냅샷) 누적과 비교 — 하루 전체와 비교하지 않는다")
    void intradayUsesSameTimeSlot() {
        Map<String, BigDecimal> base = SectorTradingService.sameTimeBaseline(prevDay(), LocalTime.of(11, 31));
        assertThat(base.get("SEMI")).isEqualByComparingTo("4100");
    }

    @Test
    @DisplayName("같은 시각 스냅샷이 없으면(6분 넘게 떨어짐·직전 거래일 기록 없음) 비교 기준 없음")
    void noNearSlotMeansNoBaseline() {
        assertThat(SectorTradingService.sameTimeBaseline(prevDay(), LocalTime.of(13, 0))).isNull();
        assertThat(SectorTradingService.sameTimeBaseline(new TreeMap<>(), LocalTime.of(11, 30))).isNull();
    }

    @Test
    @DisplayName("장 마감 뒤엔 하루 전체끼리 — 직전 거래일 마지막 스냅샷")
    void afterCloseUsesFullDay() {
        Map<String, BigDecimal> base = SectorTradingService.sameTimeBaseline(prevDay(), LocalTime.of(16, 0));
        assertThat(base.get("SEMI")).isEqualByComparingTo("9000");
    }

    @Test
    @DisplayName("재현: 기준이 없으면 UNKNOWN — 가격 등락률을 자금 흐름이라 부르지 않는다, 임계 ±10% 는 그대로")
    void classification() {
        assertThat(SectorTradingService.flowDirection(null)).isEqualTo("UNKNOWN");
        assertThat(SectorTradingService.flowDirection(new BigDecimal("12.5"))).isEqualTo("INFLOW");
        assertThat(SectorTradingService.flowDirection(new BigDecimal("-12.5"))).isEqualTo("OUTFLOW");
        assertThat(SectorTradingService.flowDirection(new BigDecimal("3"))).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("재현: 섹터 평균 등락률(히트맵)은 0%(보합) 종목도 센다 — 값이 하나도 없으면 0 이 아니라 null")
    void topAverageCountsZeroAndUnknownIsNull() {
        assertThat(SectorTradingService.topAverageChangeRate(List.of(new BigDecimal("2.0"), BigDecimal.ZERO)))
                .isEqualByComparingTo("1.00");
        assertThat(SectorTradingService.topAverageChangeRate(java.util.Arrays.asList(null, null))).isNull();
        assertThat(SectorTradingService.topAverageChangeRate(List.of())).isNull();
    }
}
