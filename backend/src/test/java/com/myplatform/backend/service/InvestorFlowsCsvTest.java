package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백테스트 정밀 수급 CSV 피벗(buildFlowsCsv) — 순수 함수.
 * 스키마는 python-backend app/backtest/investor_flows.load_flows_csv 계약:
 * date,stock_code,frgn_net_eok,inst_net_eok (억원).
 */
class InvestorFlowsCsvTest {

    private Object[] row(String date, String code, String investor, String eok) {
        return new Object[]{LocalDate.parse(date), code, investor, new BigDecimal(eok)};
    }

    @Test
    @DisplayName("외국인+기관 같은 (일자,종목) → 한 행으로 피벗")
    void pivotBothInvestors() {
        String csv = InvestorTradeService.buildFlowsCsv(List.<Object[]>of(
                row("2026-07-21", "005930", "FOREIGN", "1500.50"),
                row("2026-07-21", "005930", "INSTITUTION", "-320.25")));
        assertThat(csv).isEqualTo(
                "date,stock_code,frgn_net_eok,inst_net_eok\n" +
                "2026-07-21,005930,1500.5,-320.25\n");
    }

    @Test
    @DisplayName("한쪽 투자자만 랭킹에 있으면 없는 쪽은 빈칸 — 0(균형)으로 위장 금지(§4c, pandas NaN)")
    void missingSideIsBlankNotZero() {
        String csv = InvestorTradeService.buildFlowsCsv(List.<Object[]>of(
                row("2026-07-21", "000660", "FOREIGN", "88")));
        assertThat(csv).contains("2026-07-21,000660,88,\n");
        assertThat(csv).doesNotContain("88,0");
    }

    @Test
    @DisplayName("여러 일자·종목 — 입력 정렬(일자,종목) 순서 보존, 빈 입력은 헤더만")
    void ordering() {
        String csv = InvestorTradeService.buildFlowsCsv(List.<Object[]>of(
                row("2026-07-18", "005930", "INSTITUTION", "10"),
                row("2026-07-21", "000660", "FOREIGN", "20")));
        int first = csv.indexOf("2026-07-18,005930");
        int second = csv.indexOf("2026-07-21,000660");
        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);

        assertThat(InvestorTradeService.buildFlowsCsv(List.<Object[]>of()))
                .isEqualTo("date,stock_code,frgn_net_eok,inst_net_eok\n");
    }
}
