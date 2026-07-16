package com.myplatform.backend.service;

import com.myplatform.backend.service.KoreaInvestmentService.IndexOhlcvData;
import com.myplatform.backend.util.TrendChannelCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KOSPI 지수 채널(V50) Bar 변환 순수함수 검증 — 조회/캐시(6h)는 통합 성격이라 제외, toBars 만 단위 검증.
 * TrendChannelCalculator 산식 자체는 {@code TrendChannelCalculatorTest} 가 커버.
 */
class KospiChannelServiceTest {

    private static IndexOhlcvData row(String date, double h, double l, double c) {
        return new IndexOhlcvData(date, BigDecimal.valueOf(c), BigDecimal.valueOf(h),
                BigDecimal.valueOf(l), BigDecimal.valueOf(c));
    }

    @Test
    @DisplayName("toBars: 오름차순 40행 → 최근 30봉만(오름차순 유지), high/low/close 매핑")
    void toBars_takesLastN() {
        List<IndexOhlcvData> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double c = 2500 + i;
            rows.add(row("2026-05-" + String.format("%02d", (i % 28) + 1), c + 2, c - 2, c));
        }
        List<TrendChannelCalculator.Bar> bars = KospiChannelService.toBars(rows, 30);

        assertThat(bars).hasSize(30);
        // 최근 30봉 = index 10..39 → 첫 bar close = 2510, 마지막 = 2539(오름차순 보존)
        assertThat(bars.get(0).close()).isEqualTo(2510.0);
        assertThat(bars.get(29).close()).isEqualTo(2539.0);
        assertThat(bars.get(29).high()).isEqualTo(2541.0);
        assertThat(bars.get(29).low()).isEqualTo(2537.0);
    }

    @Test
    @DisplayName("toBars: 30봉 미만이면 전량 반환")
    void toBars_fewerThanMax() {
        List<IndexOhlcvData> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) rows.add(row("2026-05-01", 100 + i + 1, 100 + i - 1, 100 + i));
        assertThat(KospiChannelService.toBars(rows, 30)).hasSize(12);
    }

    @Test
    @DisplayName("toBars: 고가/저가 결측은 NaN 으로 → compute 가 위장 없이 null(§4c)")
    void toBars_missingHighLowBecomesNaN() {
        List<IndexOhlcvData> rows = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            double c = 2500 + i;
            // 고가/저가 null (지수 응답이 시/고/저 누락 케이스) — 종가만 존재
            rows.add(new IndexOhlcvData("2026-05-01", null, null, null, BigDecimal.valueOf(c)));
        }
        List<TrendChannelCalculator.Bar> bars = KospiChannelService.toBars(rows, 30);
        assertThat(bars).hasSize(15);
        assertThat(Double.isNaN(bars.get(0).high())).isTrue();
        assertThat(Double.isNaN(bars.get(0).low())).isTrue();
        // compute 는 고저가 NaN 이면 null (가짜 채널 위장 금지)
        assertThat(TrendChannelCalculator.compute(bars)).isNull();
    }

    @Test
    @DisplayName("toBars: 종가 null 행은 제외, null 입력은 빈 리스트")
    void toBars_skipsNullCloseAndNullInput() {
        List<IndexOhlcvData> rows = new ArrayList<>();
        rows.add(row("2026-05-01", 102, 98, 100));
        rows.add(new IndexOhlcvData("2026-05-02", null, BigDecimal.valueOf(103), BigDecimal.valueOf(99), null)); // 종가 null
        rows.add(row("2026-05-03", 104, 100, 102));
        assertThat(KospiChannelService.toBars(rows, 30)).hasSize(2);
        assertThat(KospiChannelService.toBars(null, 30)).isEmpty();
    }
}
