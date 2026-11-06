package com.myplatform.backend.service;

import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.InvestorStockDaily;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.InvestorStockDailyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 연속 순매수 일수는 확정치로 센다(2026-11-06).
 *
 * <p>재현: 순위 행은 그 종목이 그날 상위 30 에 든 날만 있어 순위 밖으로 밀린 날 연속이 끊겼다. 11/5 기관 신한지주는 실제 7일 연속인데 1일, 외국인 두산테스나는
 * 6일인데 1일이었고, 외국인 '2일 이상'이 5종목(확정치로는 19종목)이었다. 종합추천 수급 점수의 '2~3일 정점·5일+ 후반' 구분이 틀린 일수로 돌았다.
 * 목록에 드는 종목은 그대로 '그날 순매수 상위 30' 이다 — 일수만 바로잡는다.
 */
class InvestorConfirmedStreakTest {

    private static final String CODE = "055550";
    private static final List<LocalDate> DAYS = List.of(LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-04"),
            LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-02"), LocalDate.parse("2026-10-30"));

    private final InvestorDailyTradeRepository rank = mock(InvestorDailyTradeRepository.class);
    private final InvestorStockDailyRepository confirmed = mock(InvestorStockDailyRepository.class);

    @SuppressWarnings("unchecked")
    private InvestorTradeService tradeService() {
        InvestorTradeService s = new InvestorTradeService(rank, mock(KisInvestorDataCollector.class), mock(KoreaInvestmentService.class),
                mock(RedisCacheService.class), new MarketCalendarService(), mock(org.springframework.beans.factory.ObjectProvider.class));
        ReflectionTestUtils.setField(s, "investorStockDailyRepository", confirmed);
        return s;
    }

    private InvestorBuyStreakService streakService() {
        InvestorBuyStreakService s = new InvestorBuyStreakService(rank, new MarketCalendarService());
        ReflectionTestUtils.setField(s, "investorStockDailyRepository", confirmed);
        return s;
    }

    private static InvestorDailyTrade buy(String date, String code, String investor, String net) {
        return InvestorDailyTrade.builder().stockCode(code).stockName("신한지주").tradeDate(LocalDate.parse(date))
                .investorType(investor).tradeType("BUY").netBuyAmount(new BigDecimal(net)).build();
    }

    private static InvestorStockDaily day(String date, String code, String foreign, String institution) {
        return InvestorStockDaily.builder().stockCode(code).tradeDate(LocalDate.parse(date))
                .foreignNet(foreign == null ? null : new BigDecimal(foreign))
                .institutionNet(institution == null ? null : new BigDecimal(institution)).build();
    }

    private void stubRank(String investor, List<InvestorDailyTrade> rows) {
        when(rank.findDistinctTradeDates(investor)).thenReturn(DAYS);
        when(rank.findBuyTradesForConsecutiveAnalysis(eq(investor), any(), any())).thenReturn(rows);
    }

    private void stubConfirmed(List<InvestorStockDaily> rows) {
        when(confirmed.findByStockCodeInAndTradeDateBetween(anyCollection(), any(), any())).thenReturn(rows);
    }

    @Test
    @DisplayName("재현: 순위에 어제부터 없어도 확정치가 순매수면 이어진다 — 신한지주 기관 1일이 아니라 3일, 금액도 확정치 합")
    void outOfRankDaysCount() {
        stubRank("INSTITUTION", List.of(buy("2026-11-05", CODE, "INSTITUTION", "30.00")));
        stubConfirmed(List.of(day("2026-11-05", CODE, null, "30.00"), day("2026-11-04", CODE, null, "12.50"),
                day("2026-11-03", CODE, null, "4.25"), day("2026-11-02", CODE, null, "-1.00")));

        List<ConsecutiveBuyDto> list = tradeService().getConsecutiveBuyStocks("INSTITUTION", 2);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getConsecutiveDays()).isEqualTo(3);
        assertThat(list.get(0).getTotalNetBuyAmount()).isEqualByComparingTo("46.75");
        assertThat(list.get(0).getStartDate()).isEqualTo(LocalDate.parse("2026-11-03"));
        assertThat(list.get(0).getEndDate()).isEqualTo(LocalDate.parse("2026-11-05"));
    }

    @Test
    @DisplayName("순위 행이 있어도 확정치가 순매도면 끊는다 — 가집계 순위에 올랐던 날")
    void confirmedSellBreaks() {
        stubRank("FOREIGN", List.of(buy("2026-11-05", CODE, "FOREIGN", "30.00"), buy("2026-11-04", CODE, "FOREIGN", "20.00")));
        stubConfirmed(List.of(day("2026-11-05", CODE, "30.00", null), day("2026-11-04", CODE, "-5.00", null)));

        assertThat(tradeService().getConsecutiveBuyStocks("FOREIGN", 2)).isEmpty();
    }

    @Test
    @DisplayName("확정치가 없는 날은 순위 행 그대로 — 재구성 전 15:50 가집계·옛 구간")
    void noConfirmedKeepsRank() {
        stubRank("FOREIGN", List.of(buy("2026-11-05", CODE, "FOREIGN", "30.00"), buy("2026-11-04", CODE, "FOREIGN", "20.00")));
        stubConfirmed(List.of());

        List<ConsecutiveBuyDto> list = tradeService().getConsecutiveBuyStocks("FOREIGN", 2);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getConsecutiveDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("그 투자자의 칸이 비면 모르는 것이다 — 0 으로 끊지 않고 순위 행을 따른다")
    void blankCellKeepsRank() {
        stubRank("FOREIGN", List.of(buy("2026-11-05", CODE, "FOREIGN", "30.00"), buy("2026-11-04", CODE, "FOREIGN", "20.00")));
        stubConfirmed(List.of(day("2026-11-05", CODE, null, "1.00"), day("2026-11-04", CODE, null, "1.00")));

        assertThat(tradeService().getConsecutiveBuyStocks("FOREIGN", 2)).hasSize(1);
    }

    @Test
    @DisplayName("목록에 드는 종목은 그대로 그날 순위 안 — 순위 밖 종목이 확정치로 끼어들지 않는다")
    void membershipStaysRanked() {
        stubRank("FOREIGN", List.of(buy("2026-11-05", CODE, "FOREIGN", "30.00")));
        stubConfirmed(List.of(day("2026-11-05", CODE, "30.00", null), day("2026-11-04", CODE, "2.00", null)));

        assertThat(tradeService().getConsecutiveBuyStocks("FOREIGN", 2))
                .extracting(ConsecutiveBuyDto::getStockCode).containsExactly(CODE);
        org.mockito.Mockito.verify(confirmed).findByStockCodeInAndTradeDateBetween(eq(Set.of(CODE)), any(), any());
    }

    @Test
    @DisplayName("재현: 종목 상세 배지도 같은 값 — 순위 밖 날을 세고, 오늘 순위 밖 종목도 일수가 나온다")
    void badgeUsesConfirmed() {
        when(rank.findDistinctBuyTradeDatesSince(anyString(), any())).thenReturn(DAYS);
        when(rank.findByStockCodeAndDateRange(eq(CODE), any(), any())).thenReturn(new ArrayList<>());
        stubConfirmed(List.of(day("2026-11-05", CODE, "-3.00", "30.00"), day("2026-11-04", CODE, "1.00", "12.50"),
                day("2026-11-03", CODE, "1.00", "-4.00")));

        InvestorBuyStreakService.Streaks s = streakService().getStreaks(CODE);

        assertThat(s.foreign()).isZero();
        assertThat(s.institution()).isEqualTo(2);
    }

    @Test
    @DisplayName("보드 묶음 조회도 같은 규칙 — 코드마다 확정치를 덮는다")
    void boardBatchUsesConfirmed() {
        when(rank.findDistinctBuyTradeDatesSince(anyString(), any())).thenReturn(DAYS);
        when(rank.findBuyTradesByStockCodesAndInvestorSince(any(), eq("FOREIGN"), any())).thenReturn(List.of(buy("2026-11-05", CODE, "FOREIGN", "9.00")));
        when(rank.findBuyTradesByStockCodesAndInvestorSince(any(), eq("INSTITUTION"), any())).thenReturn(List.of());
        stubConfirmed(List.of(day("2026-11-05", CODE, "9.00", "30.00"), day("2026-11-04", CODE, "2.00", "12.50")));

        Map<String, InvestorBuyStreakService.Streaks> out = streakService().getStreaksForCodes(List.of(CODE));

        assertThat(out.get(CODE).foreign()).isEqualTo(2);
        assertThat(out.get(CODE).institution()).isEqualTo(2);
    }
}
