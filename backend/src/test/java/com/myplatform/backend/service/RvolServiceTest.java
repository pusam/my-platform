package com.myplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.myplatform.backend.entity.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RVOL(V41) 순수 함수 테스트 — 표시·스냅샷 전용(산식 미편입).
 * §4c 핵심: 20거래일 미만/결측/무의미 분모는 전부 null(미수집) — 그럴듯한 값으로 위장하지 않는다.
 */
class RvolServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 7);

    private static List<BigDecimal> flatValues(int n, long each) {
        List<BigDecimal> v = new ArrayList<>();
        for (int i = 0; i < n; i++) v.add(BigDecimal.valueOf(each));
        return v;
    }

    // ==================== computeRvol ====================

    @Test
    void computeRvol_normal() {
        // 20일 평균 100억, 당일 320억 → 3.20
        BigDecimal rvol = RvolService.computeRvol(
                BigDecimal.valueOf(32_000_000_000L), flatValues(20, 10_000_000_000L));
        assertThat(rvol).isEqualByComparingTo("3.20");
    }

    @Test
    void computeRvol_lessThan20Days_null() {
        assertThat(RvolService.computeRvol(
                BigDecimal.valueOf(10_000_000_000L), flatValues(19, 10_000_000_000L))).isNull();
        assertThat(RvolService.computeRvol(
                BigDecimal.valueOf(10_000_000_000L), Collections.emptyList())).isNull();
        assertThat(RvolService.computeRvol(BigDecimal.TEN, null)).isNull();
    }

    @Test
    void computeRvol_nullOrNonPositiveToday_null() {
        assertThat(RvolService.computeRvol(null, flatValues(20, 1_000L))).isNull();
        assertThat(RvolService.computeRvol(BigDecimal.ZERO, flatValues(20, 1_000L))).isNull();
        assertThat(RvolService.computeRvol(BigDecimal.valueOf(-5), flatValues(20, 1_000L))).isNull();
    }

    @Test
    void computeRvol_usesOnlyLatest20WhenMore() {
        // 최신 20개=100억, 이후(과거) 5개=1조 — 과거 초과분은 무시되어야 3.20 유지
        List<BigDecimal> values = flatValues(20, 10_000_000_000L);
        values.addAll(flatValues(5, 1_000_000_000_000L));
        BigDecimal rvol = RvolService.computeRvol(BigDecimal.valueOf(32_000_000_000L), values);
        assertThat(rvol).isEqualByComparingTo("3.20");
    }

    // ==================== pastDailyValues ====================

    private static StockPriceHistory h(LocalDate date, Long close, Long volume) {
        return StockPriceHistory.builder()
                .stockCode("005930")
                .tradeDate(date)
                .closePrice(close == null ? null : BigDecimal.valueOf(close))
                .volume(volume == null ? null : BigDecimal.valueOf(volume))
                .build();
    }

    @Test
    void pastDailyValues_excludesTodayAndComputesCloseTimesVolume() {
        List<StockPriceHistory> rows = List.of(
                h(TODAY, 70_000L, 1_000_000L),              // 오늘 — 분자와 이중 계상 방지로 제외
                h(TODAY.minusDays(1), 70_000L, 1_000_000L), // 700억
                h(TODAY.minusDays(2), 60_000L, 2_000_000L)  // 1,200억
        );
        List<BigDecimal> values = RvolService.pastDailyValues(rows, TODAY);
        assertThat(values).containsExactly(
                BigDecimal.valueOf(70_000_000_000L), BigDecimal.valueOf(120_000_000_000L));
    }

    @Test
    void pastDailyValues_skipsMissingCloseOrVolume() {
        // §4c: 결측일은 0으로 위장하지 않고 표본에서 제외 → 표본 부족이면 computeRvol 이 null
        List<StockPriceHistory> rows = List.of(
                h(TODAY.minusDays(1), null, 1_000_000L),
                h(TODAY.minusDays(2), 60_000L, null),
                h(TODAY.minusDays(3), 0L, 1_000_000L),
                h(TODAY.minusDays(4), 60_000L, 1_000_000L)
        );
        List<BigDecimal> values = RvolService.pastDailyValues(rows, TODAY);
        assertThat(values).containsExactly(BigDecimal.valueOf(60_000_000_000L));
    }

    @Test
    void pastDailyValues_nullRows_empty() {
        assertThat(RvolService.pastDailyValues(null, TODAY)).isEmpty();
    }
}
