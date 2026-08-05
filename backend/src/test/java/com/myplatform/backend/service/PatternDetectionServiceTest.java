package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 패턴 shadow 평가 순수함수({@code forwardReturns}) 검증. */
class PatternDetectionServiceTest {

    private static final LocalDate DETECTED = LocalDate.of(2026, 3, 2);

    private static StockPriceHistory row(LocalDate date, double close) {
        return StockPriceHistory.builder()
                .stockCode("000001")
                .tradeDate(date)
                .closePrice(BigDecimal.valueOf(close))
                .build();
    }

    /** 확정일 + 이후 n거래일 종가(100+i)로 최신순 리스트 구성. */
    private static List<StockPriceHistory> rowsDescWithDaysAfter(int daysAfter) {
        List<StockPriceHistory> asc = new ArrayList<>();
        asc.add(row(DETECTED, 100));
        for (int i = 1; i <= daysAfter; i++) {
            asc.add(row(DETECTED.plusDays(i), 100 + i));
        }
        Collections.reverse(asc);
        return asc;
    }

    @Test
    void 이후_10거래일이_쌓이면_1_3_5_10일_수익률_일괄산출() {
        Map<Integer, BigDecimal> out = PatternDetectionService.forwardReturns(
                BigDecimal.valueOf(100), DETECTED, rowsDescWithDaysAfter(12));

        assertThat(out).isNotNull();
        assertThat(out.get(1)).isEqualByComparingTo("1.0000");   // 101
        assertThat(out.get(3)).isEqualByComparingTo("3.0000");   // 103
        assertThat(out.get(5)).isEqualByComparingTo("5.0000");   // 105
        assertThat(out.get(10)).isEqualByComparingTo("10.0000"); // 110
    }

    @Test
    void 이후_봉이_10개_미만이면_null_미평가유지() {
        assertThat(PatternDetectionService.forwardReturns(
                BigDecimal.valueOf(100), DETECTED, rowsDescWithDaysAfter(9))).isNull();
    }

    @Test
    void 확정일_이전_봉은_수익률_계산에서_제외() {
        List<StockPriceHistory> rows = rowsDescWithDaysAfter(10);
        rows.add(row(DETECTED.minusDays(1), 50));   // 과거 봉 — 섞여 있어도 무시돼야 함

        Map<Integer, BigDecimal> out = PatternDetectionService.forwardReturns(
                BigDecimal.valueOf(100), DETECTED, rows);

        assertThat(out).isNotNull();
        assertThat(out.get(1)).isEqualByComparingTo("1.0000");
    }

    @Test
    void 이후_구간에_종가_결측이_끼면_null_지평_어긋남_방지() {
        List<StockPriceHistory> asc = new ArrayList<>();
        asc.add(row(DETECTED, 100));
        for (int i = 1; i <= 12; i++) {
            asc.add(i == 4 ? row(DETECTED.plusDays(i), 0) : row(DETECTED.plusDays(i), 100 + i));
        }
        Collections.reverse(asc);

        assertThat(PatternDetectionService.forwardReturns(BigDecimal.valueOf(100), DETECTED, asc)).isNull();
    }

    @Test
    void 기준가_무의미하면_null() {
        assertThat(PatternDetectionService.forwardReturns(null, DETECTED, rowsDescWithDaysAfter(12))).isNull();
        assertThat(PatternDetectionService.forwardReturns(BigDecimal.ZERO, DETECTED, rowsDescWithDaysAfter(12))).isNull();
    }
}
