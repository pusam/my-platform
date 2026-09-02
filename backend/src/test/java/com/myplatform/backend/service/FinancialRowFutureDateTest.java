package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockFinancialData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미래 날짜 재무 행 제외 — {@link FinancialRowSynthesizer#excludeFutureDated}.
 *
 * <p>고치려는 결함(2026-09-02 prod 실측): 네이버 크롤이 2026-07-21 전까지 "yyyy.12(E)" 추정치 컬럼을
 * 확정 실적처럼 저장해 342종목에 미래 날짜(2026-12-31) 행이 남아 있다. 005930 의 그 행은
 * 매출 7,324,732억·영업이익 3,832,404억 — 실적일 수 없는 값이다. 합성은 최신 행부터 채우므로
 * 이 행이 있으면 매출·이익 축이 통째로 추정치를 읽었다. 크롤러는 이미 (E) 를 빼니 읽는 쪽에서 거른다.
 */
class FinancialRowFutureDateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 2);

    private static StockFinancialData row(String date, String operatingProfit) {
        StockFinancialData r = new StockFinancialData();
        r.setStockCode("005930");
        r.setReportDate(date == null ? null : LocalDate.parse(date));
        r.setOperatingProfit(operatingProfit == null ? null : new BigDecimal(operatingProfit));
        return r;
    }

    @Test
    @DisplayName("기준일이 오늘보다 뒤인 행은 빠진다 — 005930 의 12-31 추정 행 원형")
    void dropsFutureDatedRows() {
        List<StockFinancialData> rows = List.of(
                row("2026-12-31", "3832404"),   // 추정치 잔여 — 빠져야 한다
                row("2025-12-31", "200737"),
                row("2025-09-30", "121661"));

        List<StockFinancialData> out = FinancialRowSynthesizer.excludeFutureDated(rows, TODAY);

        assertThat(out).extracting(r -> r.getReportDate().toString())
                .containsExactly("2025-12-31", "2025-09-30");
        // 합성이 이 결과를 받으면 영업이익은 추정치가 아니라 실적에서 온다
        assertThat(FinancialRowSynthesizer.synthesize(out).getOperatingProfit())
                .isEqualByComparingTo("200737");
    }

    @Test
    @DisplayName("오늘 날짜 행은 남긴다(경계 포함) — 일별 스냅샷은 report_date=오늘")
    void keepsTodayRow() {
        List<StockFinancialData> rows = List.of(row("2026-09-02", "1"), row("2026-09-03", "2"));
        List<StockFinancialData> out = FinancialRowSynthesizer.excludeFutureDated(rows, TODAY);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getReportDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("기준일 결측 행은 '미래'로 단정하지 않고 남긴다(§4c) — null/빈 입력은 그대로")
    void nullDatesAndInputsPassThrough() {
        List<StockFinancialData> rows = new ArrayList<>(Arrays.asList(row(null, "5"), null, row("2026-12-31", "9")));
        List<StockFinancialData> out = FinancialRowSynthesizer.excludeFutureDated(rows, TODAY);
        assertThat(out).hasSize(2);   // 결측 행 + null 요소는 통과, 미래 행만 제거

        assertThat(FinancialRowSynthesizer.excludeFutureDated(null, TODAY)).isNull();
        assertThat(FinancialRowSynthesizer.excludeFutureDated(List.of(), TODAY)).isEmpty();
        assertThat(FinancialRowSynthesizer.excludeFutureDated(rows, null)).isSameAs(rows);
    }
}
