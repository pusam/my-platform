package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockInvestorDetailDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.InvestorStockDaily;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.InvestorStockDailyRepository;
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
 * 종목 상세 '투자자 동향'의 일별 표·누적 그래프(2026-11-06).
 *
 * <p>재현: 표와 그래프가 순위 행만 읽어 그 종목이 상위 30 에 든 날만 더했다 — 현대차 외국인은 11/5 −34억·11/4 −99억이 빠져 있었다.
 * 확정치가 있는 날은 그 값을 쓴다. 확정치엔 순매수만 있어 매수·매도는 모른다(null).
 */
class StockInvestorDetailConfirmedTest {

    private static final String CODE = "005380";

    private final InvestorDailyTradeRepository rank = mock(InvestorDailyTradeRepository.class);
    private final InvestorStockDailyRepository confirmed = mock(InvestorStockDailyRepository.class);

    @SuppressWarnings("unchecked")
    private InvestorTradeService service() {
        InvestorTradeService s = new InvestorTradeService(rank, mock(KisInvestorDataCollector.class), mock(KoreaInvestmentService.class),
                mock(RedisCacheService.class), mock(MarketCalendarService.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));
        ReflectionTestUtils.setField(s, "investorStockDailyRepository", confirmed);
        return s;
    }

    private static InvestorDailyTrade rankRow(String date, String investor, String net) {
        return InvestorDailyTrade.builder().stockCode(CODE).stockName("현대차").tradeDate(LocalDate.parse(date))
                .investorType(investor).tradeType(net.startsWith("-") ? "SELL" : "BUY")
                .netBuyAmount(new BigDecimal(net)).currentPrice(new BigDecimal("283000")).build();
    }

    private static InvestorStockDaily day(String date, String foreign, String institution, String pension, String close) {
        return InvestorStockDaily.builder().stockCode(CODE).tradeDate(LocalDate.parse(date))
                .foreignNet(new BigDecimal(foreign)).institutionNet(new BigDecimal(institution)).pensionNet(new BigDecimal(pension))
                .closePrice(new BigDecimal(close)).build();
    }

    private void stub(List<InvestorDailyTrade> rankRows, List<InvestorStockDaily> confirmedRows) {
        when(rank.findByStockCodeAndDateRange(eq(CODE), any(), any())).thenReturn(rankRows);
        when(confirmed.findByStockCodeAndTradeDateBetween(eq(CODE), any(), any())).thenReturn(confirmedRows);
    }

    @Test
    @DisplayName("재현: 순위에 안 든 날도 확정치가 있으면 표에 나온다 — 외국인 합이 순위 행만으론 −116억, 실제 −249억")
    void confirmedDaysFillTheGaps() {
        stub(List.of(rankRow("2026-11-03", "FOREIGN", "-116.46")),
                List.of(day("2026-11-05", "-34.01", "352.71", "127.17", "293500"),
                        day("2026-11-04", "-98.52", "176.08", "4.54", "283500"),
                        day("2026-11-03", "-116.46", "-47.69", "-5.02", "277500")));

        StockInvestorDetailDto dto = service().getStockInvestorDetail(CODE, 180);

        assertThat(dto.getDailyTrades()).extracting(StockInvestorDetailDto.DailyInvestorTrade::getTradeDate)
                .containsExactly(LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-03"));
        StockInvestorDetailDto.DailyInvestorTrade first = dto.getDailyTrades().get(0);
        assertThat(first.getForeign().getNetBuyAmount()).isEqualByComparingTo("-34.01");
        assertThat(first.getInstitution().getNetBuyAmount()).isEqualByComparingTo("352.71");
        assertThat(first.getPension().getNetBuyAmount()).isEqualByComparingTo("127.17");
        assertThat(first.getClosePrice()).isEqualByComparingTo("293500");
        assertThat(dto.getConfirmedFrom()).isEqualTo(LocalDate.parse("2026-11-03"));
    }

    @Test
    @DisplayName("확정치엔 매수·매도 금액이 없다 — 0 이 아니라 모름")
    void buyAndSellAreUnknownOnConfirmedDays() {
        stub(List.of(), List.of(day("2026-11-05", "-34.01", "352.71", "127.17", "293500")));

        StockInvestorDetailDto.InvestorTradeSummary foreign = service().getStockInvestorDetail(CODE, 180).getDailyTrades().get(0).getForeign();

        assertThat(foreign.getBuyAmount()).isNull();
        assertThat(foreign.getSellAmount()).isNull();
    }

    @Test
    @DisplayName("확정치가 없는 옛날은 순위 행 그대로 — 두 기준이 같은 표에 있어도 날짜마다 하나다")
    void olderDaysStayRanked() {
        stub(List.of(rankRow("2026-08-03", "FOREIGN", "50.00")), List.of(day("2026-11-05", "-34.01", "352.71", "127.17", "293500")));

        StockInvestorDetailDto dto = service().getStockInvestorDetail(CODE, 180);

        assertThat(dto.getDailyTrades()).hasSize(2);
        assertThat(dto.getDailyTrades().get(1).getForeign().getNetBuyAmount()).isEqualByComparingTo("50.00");
        assertThat(dto.getConfirmedFrom()).isEqualTo(LocalDate.parse("2026-11-05"));
    }

    @Test
    @DisplayName("확정치 표에 없는 종목은 종전과 같다 — 확정치 시작일이 없다")
    void noConfirmedKeepsRank() {
        stub(List.of(rankRow("2026-11-03", "FOREIGN", "-116.46")), List.of());

        StockInvestorDetailDto dto = service().getStockInvestorDetail(CODE, 180);

        assertThat(dto.getDailyTrades()).hasSize(1);
        assertThat(dto.getConfirmedFrom()).isNull();
    }

    @Test
    @DisplayName("둘 다 없으면 종전대로 없음")
    void nothingIsNull() {
        stub(List.of(), List.of());

        assertThat(service().getStockInvestorDetail(CODE, 180)).isNull();
    }
}
