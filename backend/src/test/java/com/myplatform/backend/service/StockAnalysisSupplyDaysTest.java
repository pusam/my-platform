package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockDiagnosisDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.InvestorStockDaily;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.InvestorStockDailyRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockPriceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 종목 진단 '최근 5일 누적 수급' — 며칠 사고 며칠 팔았는지를 함께 준다(2026-10-07 화면 점검).
 *
 * <p>재현(10/7 운영 삼성전자): 기관은 9/29·9/30 에 팔고 10/1·10/2·10/6 3일 연속 순매수였는데, 5일 합(−1,248.9억)이 음수라는
 * 이유만으로 화면이 '기관 순매도 1,248.9억 (연속 순매도 주의!)'라 했고 경고는 '외국인+기관 동반 매도 중'이었다. 서버는 매수일만
 * 실어 화면이 매도일을 알 수 없었다.
 */
class StockAnalysisSupplyDaysTest {

    private static InvestorDailyTrade t(String date, String investor, String type, String eok) {
        return InvestorDailyTrade.builder().stockCode("005930")
                .tradeDate(LocalDate.parse(date)).investorType(investor).tradeType(type)
                .netBuyAmount(new BigDecimal(eok)).build();
    }

    @Test
    @DisplayName("재현: 기관 5일 합 −1,248.9억이어도 순매수 3일·순매도 2일 — 외국인은 순매도 5일")
    void buyAndSellDaysBothReported() {
        InvestorDailyTradeRepository repo = mock(InvestorDailyTradeRepository.class);
        when(repo.findByStockCodeAndDateRange(eq("005930"), any(), any())).thenReturn(List.of(
                t("2026-09-29", "FOREIGN", "SELL", "-1526.00"), t("2026-09-29", "INSTITUTION", "SELL", "-754.83"),
                t("2026-09-30", "FOREIGN", "SELL", "-4551.08"), t("2026-09-30", "INSTITUTION", "SELL", "-2075.51"),
                t("2026-10-01", "FOREIGN", "SELL", "-2489.52"), t("2026-10-01", "INSTITUTION", "BUY", "709.32"),
                t("2026-10-02", "FOREIGN", "SELL", "-1418.64"), t("2026-10-02", "INSTITUTION", "BUY", "494.04"),
                t("2026-10-06", "FOREIGN", "SELL", "-3995.68"), t("2026-10-06", "INSTITUTION", "BUY", "378.08")));
        StockAnalysisService svc = new StockAnalysisService(mock(StockFinancialDataRepository.class), repo,
                mock(StockPriceHistoryRepository.class), mock(StockPriceRepository.class),
                mock(TechnicalIndicatorService.class), mock(KoreaInvestmentService.class), new MarketCalendarService());

        StockDiagnosisDto.SupplyDemandDto sd = svc.analyzeSupplyDemand("005930");

        assertThat(sd.getInstitutionNet5Days()).isEqualByComparingTo("-1248.90");
        assertThat(sd.getInstitutionBuyDays()).isEqualTo(3);
        assertThat(sd.getInstitutionSellDays()).isEqualTo(2);
        assertThat(sd.getForeignBuyDays()).isZero();
        assertThat(sd.getForeignSellDays()).isEqualTo(5);
    }

    private static StockAnalysisService withConfirmed(InvestorDailyTradeRepository repo, List<InvestorStockDaily> rows) {
        InvestorStockDailyRepository confirmed = mock(InvestorStockDailyRepository.class);
        when(confirmed.findByStockCodeAndTradeDateBetween(eq("005380"), any(), any())).thenReturn(rows);
        StockAnalysisService svc = new StockAnalysisService(mock(StockFinancialDataRepository.class), repo,
                mock(StockPriceHistoryRepository.class), mock(StockPriceRepository.class),
                mock(TechnicalIndicatorService.class), mock(KoreaInvestmentService.class), new MarketCalendarService());
        ReflectionTestUtils.setField(svc, "investorStockDailyRepository", confirmed);
        return svc;
    }

    private static InvestorStockDaily d(String date, String foreign, String institution) {
        return InvestorStockDaily.builder().stockCode("005380").tradeDate(LocalDate.parse(date))
                .foreignNet(foreign == null ? null : new BigDecimal(foreign))
                .institutionNet(institution == null ? null : new BigDecimal(institution)).build();
    }

    /** 현대차 10/30~11/5 운영 값. */
    private static List<InvestorStockDaily> hyundai() {
        return List.of(d("2026-11-05", "-34.01", "352.71"), d("2026-11-04", "-98.52", "176.08"),
                d("2026-11-03", "-116.46", "-47.69"), d("2026-11-02", "93.48", "122.51"), d("2026-10-30", "-226.73", "85.89"));
    }

    @Test
    @DisplayName("재현: 5일 합은 확정치로 — 순위 행은 30위 안에 든 날만 있어 외국인 −250억(실제 −382억)·기관 +651억(실제 +690억)이었다")
    void fiveDaysUseConfirmedRows() {
        InvestorDailyTradeRepository rank = mock(InvestorDailyTradeRepository.class);
        // 순위 행 — 외국인은 11/3·11/2·10/30, 기관은 11/5·11/4·11/2 에만 있다
        when(rank.findByStockCodeAndDateRange(eq("005380"), any(), any())).thenReturn(List.of(
                t("2026-11-05", "INSTITUTION", "BUY", "352.71"), t("2026-11-04", "INSTITUTION", "BUY", "176.08"),
                t("2026-11-03", "FOREIGN", "SELL", "-116.46"), t("2026-11-02", "FOREIGN", "BUY", "93.48"),
                t("2026-11-02", "INSTITUTION", "BUY", "122.51"), t("2026-10-30", "FOREIGN", "SELL", "-226.73")));
        StockAnalysisService svc = withConfirmed(rank, hyundai());

        StockDiagnosisDto.SupplyDemandDto sd = svc.analyzeSupplyDemand("005380");
        assertThat(sd.getForeignNet5Days()).isEqualByComparingTo("-382.24");
        assertThat(sd.getForeignBuyDays()).isEqualTo(1);
        assertThat(sd.getForeignSellDays()).isEqualTo(4);
        assertThat(sd.getInstitutionNet5Days()).isEqualByComparingTo("689.50");
        assertThat(sd.getInstitutionBuyDays()).isEqualTo(4);
        assertThat(sd.getInstitutionSellDays()).isEqualTo(1);

        StockAnalysisService.FiveDaySupply five = svc.getFiveDayNetBuy("005380");
        assertThat(five.foreignNetEok()).isEqualByComparingTo("-382.24");
        assertThat(five.institutionNetEok()).isEqualByComparingTo("689.50");
        assertThat(five.days()).isEqualTo(5);
    }

    @Test
    @DisplayName("확정치가 없는 종목은 종전대로 순위 행 — 후보 풀 밖 종목")
    void noConfirmedFallsBackToRank() {
        InvestorDailyTradeRepository rank = mock(InvestorDailyTradeRepository.class);
        when(rank.findByStockCodeAndDateRange(eq("005380"), any(), any()))
                .thenReturn(List.of(t("2026-11-05", "FOREIGN", "BUY", "10.00")));

        StockDiagnosisDto.SupplyDemandDto sd = withConfirmed(rank, List.of()).analyzeSupplyDemand("005380");

        assertThat(sd.getForeignNet5Days()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("금액이 빈 칸은 0 이 아니다 — 그 투자자의 그날은 세지 않는다")
    void blankAmountIsNotZero() {
        StockAnalysisService svc = withConfirmed(mock(InvestorDailyTradeRepository.class),
                List.of(d("2026-11-05", null, "10.00"), d("2026-11-04", null, "5.00")));

        StockDiagnosisDto.SupplyDemandDto sd = svc.analyzeSupplyDemand("005380");

        assertThat(sd.getForeignNet5Days()).as("모름").isNull();
        assertThat(sd.getInstitutionNet5Days()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("동반 순매도 경고는 5일 합 기준이라고 말한다 — '매도 중'(지금도 팔고 있다)이 아니다")
    void bothSellingWarningSaysCumulative() {
        assertThat(StockAnalysisService.BOTH_SELLING_WARNING)
                .contains("5일 누적")
                .doesNotContain("매도 중");
    }
}
