package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockPriceHistory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거래대금 하한(2026-11-11) — 20거래일 평균 거래대금 판정. 수치는 그날 '오늘' 탭 후보의 실측(억 원).
 */
class LiquidityFloorTest {

    private static final LocalDate LAST = LocalDate.of(2026, 11, 10);

    /** 최신순 봉 n 개 — 종가 10,000원, 하루 거래대금이 eok 억이 되도록 거래량을 맞춘다. */
    static List<StockPriceHistory> bars(int n, double eok) {
        List<StockPriceHistory> out = new ArrayList<>();
        long volume = Math.round(eok * 1e8 / 10_000);
        for (int i = 0; i < n; i++) out.add(bar(LAST.minusDays(i), 10_000, volume));
        return out;
    }

    static StockPriceHistory bar(LocalDate d, long close, Long volume) {
        StockPriceHistory b = new StockPriceHistory();
        b.setStockCode("X");
        b.setTradeDate(d);
        b.setClosePrice(BigDecimal.valueOf(close));
        b.setVolume(volume == null ? null : BigDecimal.valueOf(volume));
        return b;
    }

    private static LiquidityFloor.Verdict judge(List<StockPriceHistory> rows, double floor) {
        return LiquidityFloor.judge(LiquidityFloor.avgTradedValueEok(rows, LAST), floor);
    }

    @Nested
    class RealCandidates {
        @Test
        void belowFloor() {
            for (double eok : new double[]{0.23, 0.24, 1.47, 2.91, 3.22}) {
                assertThat(judge(bars(20, eok), 5)).as("%s억", eok).isEqualTo(LiquidityFloor.Verdict.BELOW);
            }
        }

        @Test
        void passes() {
            for (double eok : new double[]{5.25, 19.7, 29.3, 536, 810}) {
                assertThat(judge(bars(20, eok), 5)).as("%s억", eok).isEqualTo(LiquidityFloor.Verdict.PASS);
            }
        }

        @Test
        void exactlyFloorPasses() {
            assertThat(LiquidityFloor.avgTradedValueEok(bars(20, 5), LAST)).isEqualByComparingTo("5");
            assertThat(judge(bars(20, 5), 5)).isEqualTo(LiquidityFloor.Verdict.PASS);
        }
    }

    @Test
    void usesOnlyLatest20Bars() {
        List<StockPriceHistory> rows = bars(20, 10);
        rows.addAll(bars(40, 0).subList(20, 40));   // 21번째부터는 거래 0 — 평균에 들어가면 안 된다
        assertThat(LiquidityFloor.avgTradedValueEok(rows, LAST)).isEqualByComparingTo("10");
    }

    @Test
    void nullVolumeBarIsLeftOutButZeroVolumeCountsAsZero() {
        List<StockPriceHistory> rows = bars(20, 6);
        rows.set(3, bar(LAST.minusDays(3), 10_000, null));   // 미수집 — 평균에서 뺀다 → 19봉 평균 6
        assertThat(LiquidityFloor.avgTradedValueEok(rows, LAST)).isEqualByComparingTo("6");
        rows.set(5, bar(LAST.minusDays(5), 10_000, 0L));     // 거래 없음 — 0 으로 센다 → 19봉 중 18봉이 6억 → 6×18/19
        BigDecimal avg = LiquidityFloor.avgTradedValueEok(rows, LAST);
        assertThat(avg.doubleValue()).isCloseTo(6.0 * 18 / 19, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void fewerThan10UsableBarsIsUnknown() {
        assertThat(LiquidityFloor.avgTradedValueEok(bars(9, 0.1), LAST)).isNull();
        assertThat(judge(bars(9, 0.1), 5)).isEqualTo(LiquidityFloor.Verdict.UNKNOWN);
        assertThat(LiquidityFloor.avgTradedValueEok(bars(10, 0.1), LAST)).isNotNull();
        // 봉은 20개지만 거래량을 아는 봉이 9개면 판정 불가
        List<StockPriceHistory> rows = bars(20, 0.1);
        for (int i = 0; i < 11; i++) rows.set(i, bar(LAST.minusDays(i), 10_000, null));
        assertThat(LiquidityFloor.avgTradedValueEok(rows, LAST)).isNull();
    }

    @Test
    void staleLatestBarIsUnknown() {
        assertThat(LiquidityFloor.avgTradedValueEok(bars(20, 0.1), LAST.plusDays(1))).isNull();
        // 달력을 모르면(기준일 null) 노후 판정은 하지 않는다 — 기술 채점과 같은 fail-open
        assertThat(LiquidityFloor.avgTradedValueEok(bars(20, 0.1), null)).isNotNull();
        assertThat(LiquidityFloor.avgTradedValueEok(List.of(), LAST)).isNull();
        assertThat(LiquidityFloor.avgTradedValueEok(null, LAST)).isNull();
    }

    @Test
    void floorZeroOrBelowTurnsOff() {
        assertThat(judge(bars(20, 0.01), 0)).isEqualTo(LiquidityFloor.Verdict.PASS);
        assertThat(judge(bars(20, 0.01), -1)).isEqualTo(LiquidityFloor.Verdict.PASS);
        assertThat(LiquidityFloor.judge(null, 0)).isEqualTo(LiquidityFloor.Verdict.PASS);
        assertThat(LiquidityFloor.judge(null, 5)).isEqualTo(LiquidityFloor.Verdict.UNKNOWN);
    }
}
