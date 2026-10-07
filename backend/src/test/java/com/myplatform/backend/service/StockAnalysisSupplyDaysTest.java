package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockDiagnosisDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockPriceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
                mock(TechnicalIndicatorService.class), mock(KoreaInvestmentService.class));

        StockDiagnosisDto.SupplyDemandDto sd = svc.analyzeSupplyDemand("005930");

        assertThat(sd.getInstitutionNet5Days()).isEqualByComparingTo("-1248.90");
        assertThat(sd.getInstitutionBuyDays()).isEqualTo(3);
        assertThat(sd.getInstitutionSellDays()).isEqualTo(2);
        assertThat(sd.getForeignBuyDays()).isZero();
        assertThat(sd.getForeignSellDays()).isEqualTo(5);
    }

    @Test
    @DisplayName("동반 순매도 경고는 5일 합 기준이라고 말한다 — '매도 중'(지금도 팔고 있다)이 아니다")
    void bothSellingWarningSaysCumulative() {
        assertThat(StockAnalysisService.BOTH_SELLING_WARNING)
                .contains("5일 누적")
                .doesNotContain("매도 중");
    }
}
