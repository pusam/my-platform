package com.myplatform.backend.util;

import com.myplatform.backend.entity.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일봉 upsert 판정 검증 — P2-19 ① (장중 미확정 봉 영구 확정 버그).
 *
 * <p>핵심 회귀: "존재하면 스킵"이면 장중 수집된 당일 봉이 영원히 갱신되지 않는다.
 */
class DailyBarUpsertRuleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 5);

    private static StockPriceHistory row(double o, double h, double l, double c, long v) {
        return StockPriceHistory.builder()
                .stockCode("005930").tradeDate(TODAY)
                .openPrice(BigDecimal.valueOf(o)).highPrice(BigDecimal.valueOf(h))
                .lowPrice(BigDecimal.valueOf(l)).closePrice(BigDecimal.valueOf(c))
                .volume(BigDecimal.valueOf(v))
                .build();
    }

    private static DailyBarUpsertRule.Bar bar(double o, double h, double l, double c, long v) {
        return new DailyBarUpsertRule.Bar(BigDecimal.valueOf(o), BigDecimal.valueOf(h),
                BigDecimal.valueOf(l), BigDecimal.valueOf(c), BigDecimal.valueOf(v));
    }

    @Test
    void 기존_행이_없으면_저장한다() {
        assertThat(DailyBarUpsertRule.shouldWrite(null, bar(100, 110, 95, 105, 1000), TODAY, TODAY)).isTrue();
    }

    @Test
    void 당일_봉은_값이_같아도_항상_덮어쓴다() {
        // 장중 미확정 봉 → 마감 후 확정값으로 갱신되어야 한다. 이게 P2-19 ① 의 핵심 회귀.
        StockPriceHistory intraday = row(100, 105, 98, 103, 500);      // 장중 부분값
        DailyBarUpsertRule.Bar confirmed = bar(100, 112, 96, 108, 2400); // 마감 후 확정

        assertThat(DailyBarUpsertRule.shouldWrite(intraday, confirmed, TODAY, TODAY)).isTrue();
        // 값이 동일해도 당일 봉은 계속 최신화(확정 여부를 값만으로는 알 수 없다)
        assertThat(DailyBarUpsertRule.shouldWrite(intraday, bar(100, 105, 98, 103, 500), TODAY, TODAY)).isTrue();
    }

    @Test
    void 과거_봉은_값이_같으면_쓰지_않는다() {
        LocalDate past = TODAY.minusDays(3);
        StockPriceHistory stored = row(100, 110, 95, 105, 1000);
        assertThat(DailyBarUpsertRule.shouldWrite(stored, bar(100, 110, 95, 105, 1000), past, TODAY)).isFalse();
    }

    @Test
    void 과거_봉도_값이_다르면_확정값으로_복구한다() {
        // 과거에 장중 동결된 봉을 나중에 확정값으로 되살리는 경로
        LocalDate past = TODAY.minusDays(3);
        StockPriceHistory frozen = row(100, 105, 98, 103, 500);
        assertThat(DailyBarUpsertRule.shouldWrite(frozen, bar(100, 112, 96, 108, 2400), past, TODAY)).isTrue();
    }

    @Test
    void 미래_거래일도_당일과_같이_취급한다() {
        // 서버/거래소 시각 경계에서 tradeDate 가 today 보다 앞설 수 있다 — 미확정으로 본다
        assertThat(DailyBarUpsertRule.shouldWrite(row(1, 1, 1, 1, 1), bar(1, 1, 1, 1, 1),
                TODAY.plusDays(1), TODAY)).isTrue();
    }

    @Test
    void 종가가_없거나_0이면_저장하지_않는다() {
        StockPriceHistory stored = row(100, 110, 95, 105, 1000);
        assertThat(DailyBarUpsertRule.shouldWrite(stored,
                new DailyBarUpsertRule.Bar(null, null, null, null, null), TODAY, TODAY)).isFalse();
        assertThat(DailyBarUpsertRule.shouldWrite(stored, bar(100, 110, 95, 0, 1000), TODAY, TODAY)).isFalse();
        assertThat(DailyBarUpsertRule.shouldWrite(null, bar(100, 110, 95, 0, 1000), TODAY, TODAY)).isFalse();
    }

    @Test
    void 날짜를_모르면_기존_값을_보존한다() {
        StockPriceHistory stored = row(100, 110, 95, 105, 1000);
        assertThat(DailyBarUpsertRule.shouldWrite(stored, bar(1, 1, 1, 1, 1), null, TODAY)).isFalse();
        assertThat(DailyBarUpsertRule.shouldWrite(stored, bar(1, 1, 1, 1, 1), TODAY, null)).isFalse();
    }

    // ==================== differs ====================

    @Test
    void scale이_달라도_같은_수면_같다고_본다() {
        StockPriceHistory stored = StockPriceHistory.builder()
                .closePrice(new BigDecimal("1000.00")).highPrice(new BigDecimal("1100.00"))
                .lowPrice(new BigDecimal("900.00")).openPrice(new BigDecimal("950.00"))
                .volume(new BigDecimal("500")).build();
        DailyBarUpsertRule.Bar same = new DailyBarUpsertRule.Bar(
                new BigDecimal("950"), new BigDecimal("1100"), new BigDecimal("900"),
                new BigDecimal("1000"), new BigDecimal("500"));
        assertThat(DailyBarUpsertRule.differs(stored, same)).isFalse();
    }

    @Test
    void 새_값이_결측인_필드는_비교하지_않는다() {
        // 결측을 '다름'으로 보면 정상 값이 null 로 덮어써진다(§4c)
        StockPriceHistory stored = row(100, 110, 95, 105, 1000);
        DailyBarUpsertRule.Bar partialFresh = new DailyBarUpsertRule.Bar(
                null, null, null, BigDecimal.valueOf(105), null);
        assertThat(DailyBarUpsertRule.differs(stored, partialFresh)).isFalse();
    }

    @Test
    void 거래량만_달라도_다르다고_본다() {
        // 장중 부분 거래량 → 확정 거래량이 대표적 오염 케이스
        StockPriceHistory stored = row(100, 110, 95, 105, 500);
        assertThat(DailyBarUpsertRule.differs(stored, bar(100, 110, 95, 105, 2400))).isTrue();
    }

    @Test
    void 저장값이_결측이고_새_값이_있으면_다르다() {
        StockPriceHistory stored = StockPriceHistory.builder()
                .closePrice(BigDecimal.valueOf(105)).build();   // 고가·저가·거래량 결측
        assertThat(DailyBarUpsertRule.differs(stored, bar(100, 110, 95, 105, 1000))).isTrue();
    }
}
