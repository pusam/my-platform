package com.myplatform.backend.controlroom;

import com.myplatform.backend.service.MarketCalendarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 규칙 ⑮ 시장 폭 수집 정지 / ADR 판단 보류 — {@link DataAnomalyRules#marketBreadthStall}(2026-10-02).
 *
 * <p>"이 규칙이 있었다면 울렸을까": 9/11~10/2 네이버 크롤 사망 동안 마지막으로 등락 수가 있는 날은 9/10 이었다 —
 * 10/2 기준 거래일 13일 밀림 → 경고. 수집이 되살아나도 창이 차는 몇 주 동안은 '판단 보류' INFO.
 */
class DataAnomalyRulesMarketBreadthTest {

    private final MarketCalendarService calendar = new MarketCalendarService();

    @Test
    @DisplayName("재현: 9/10 이후 수집이 멈춘 10/2 — 거래일 13일 밀림 → 경고")
    void wouldHaveFiredDuringTheCrawlDeath() {
        int behind = ControlRoomSnapshotService.tradingDaysAfter(
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 1), calendar);

        DataAnomalyRules.Anomaly a = DataAnomalyRules.marketBreadthStall(LocalDate.of(2026, 9, 10), behind, 6);

        assertThat(behind).isEqualTo(13);   // 9/11 · 9/14~18 · 9/21~23 · 9/28~30 · 10/1 (추석 9/24·25 제외)
        assertThat(a).isNotNull();
        assertThat(a.severity()).isEqualTo(DataAnomalyRules.WARNING);
        assertThat(a.title()).contains("수집 정지").contains("2026-09-10").contains("13일 밀림");
        assertThat(a.detail()).contains("FHPUP02100000");
    }

    @Test
    @DisplayName("하루 밀림은 정상일 수 있다(15:40~16:30 엔 오늘 값이 아직 없다) — 대신 창이 모자라면 판단 보류 INFO")
    void oneDayLagIsNotAStall() {
        DataAnomalyRules.Anomaly a = DataAnomalyRules.marketBreadthStall(LocalDate.of(2026, 10, 6), 1, 3);

        assertThat(a).isNotNull();
        assertThat(a.severity()).isEqualTo(DataAnomalyRules.INFO);
        assertThat(a.title()).contains("ADR 판단 보류 중").contains("3일(15일 필요)");
    }

    @Test
    @DisplayName("수집이 돌고 창이 차 있으면 조용하다(null)")
    void quietWhenHealthy() {
        assertThat(DataAnomalyRules.marketBreadthStall(LocalDate.of(2026, 11, 20), 0, 18)).isNull();
    }

    @Test
    @DisplayName("한 번도 수집된 적 없으면 경고")
    void neverCollectedWarns() {
        assertThat(DataAnomalyRules.marketBreadthStall(null, 99, 0).severity()).isEqualTo(DataAnomalyRules.WARNING);
    }

    @Test
    @DisplayName("거래일 수는 휴장일을 빼고 센다 — 개천절 대체휴일(10/5)·주말")
    void tradingDayCountSkipsHolidays() {
        assertThat(ControlRoomSnapshotService.tradingDaysAfter(
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 6), calendar)).isEqualTo(1);
        assertThat(ControlRoomSnapshotService.tradingDaysAfter(
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2), calendar)).isZero();
        assertThat(ControlRoomSnapshotService.tradingDaysAfter(null, LocalDate.of(2026, 10, 2), calendar)).isEqualTo(99);
    }
}
