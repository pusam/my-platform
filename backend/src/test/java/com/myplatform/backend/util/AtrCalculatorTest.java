package com.myplatform.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.myplatform.backend.entity.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ATR(14, Wilder) 순수 계산 테스트 — 백테스트(exit_backtest)와 동일 정의.
 * §4c 핵심: 15개(TR 14개) 미만·결측 구간 = null(미산출) — 위장 금지.
 */
class AtrCalculatorTest {

    private static final LocalDate BASE = LocalDate.of(2026, 7, 7);

    /** n일치 동일 캔들(H/L/C) — 최신순 리스트. */
    private static List<StockPriceHistory> flatCandles(int n, long high, long low, long close) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(candle(BASE.minusDays(i), high, low, close));
        }
        return rows;
    }

    private static StockPriceHistory candle(LocalDate date, Long high, Long low, Long close) {
        return StockPriceHistory.builder()
                .stockCode("005930").tradeDate(date)
                .highPrice(high == null ? null : BigDecimal.valueOf(high))
                .lowPrice(low == null ? null : BigDecimal.valueOf(low))
                .closePrice(close == null ? null : BigDecimal.valueOf(close))
                .build();
    }

    @Test
    void atr14_constantRange_equalsRange() {
        // 매일 H105/L95/C100 → TR = 10 상수 → ATR = 10 (평활 무관)
        BigDecimal atr = AtrCalculator.atr14(flatCandles(30, 105, 95, 100));
        assertThat(atr).isEqualByComparingTo("10");
    }

    @Test
    void atr14_wilderSmoothing_stepChange() {
        // 과거 20일 TR=10, 최신 1일 TR=20 → ATR = (10×13 + 20) ÷ 14 = 10.7142...
        List<StockPriceHistory> rows = flatCandles(20, 105, 95, 100);
        rows.add(0, candle(BASE.plusDays(1), 110L, 90L, 100L));   // 최신 행: H-L=20
        BigDecimal atr = AtrCalculator.atr14(rows);
        assertThat(atr).isEqualByComparingTo("10.7143");
    }

    @Test
    void atr14_gapTrueRange_usesPrevClose() {
        // 전일 C100 → 당일 H130/L125/C128 (갭업): TR = max(5, |130-100|, |125-100|) = 30
        // 과거 20일 TR=10 → ATR = (10×13 + 30)/14 = 11.4285...
        List<StockPriceHistory> rows = flatCandles(20, 105, 95, 100);
        rows.add(0, candle(BASE.plusDays(1), 130L, 125L, 128L));
        BigDecimal atr = AtrCalculator.atr14(rows);
        assertThat(atr).isEqualByComparingTo("11.4286");
    }

    @Test
    void atr14_lessThan15Rows_null() {
        assertThat(AtrCalculator.atr14(flatCandles(14, 105, 95, 100))).isNull();   // TR 13개뿐
        assertThat(AtrCalculator.atr14(Collections.emptyList())).isNull();
        assertThat(AtrCalculator.atr14(null)).isNull();
    }

    @Test
    void atr14_missingOhlcTruncates_nullIfInsufficient() {
        // 최신 10행 유효 + 11번째 행 low 결측 → 연속 유효 10행뿐 → null(§4c)
        List<StockPriceHistory> rows = flatCandles(10, 105, 95, 100);
        rows.add(candle(BASE.minusDays(10), 105L, null, 100L));
        rows.addAll(flatCandles(20, 105, 95, 100));   // 결측 너머 과거는 이어붙이지 않음
        assertThat(AtrCalculator.atr14(rows)).isNull();
    }

    @Test
    void atr14_zeroVolatility_null() {
        // 전 구간 H=L=C → TR 전부 0 → ATR 0 = 무의미(사이징 분모) → null
        assertThat(AtrCalculator.atr14(flatCandles(30, 100, 100, 100))).isNull();
    }
}
