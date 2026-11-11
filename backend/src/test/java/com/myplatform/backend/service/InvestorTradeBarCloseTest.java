package com.myplatform.backend.service;

import com.myplatform.backend.dto.InvestorTradeDto;
import com.myplatform.backend.dto.StockInvestorDetailDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.InvestorStockDaily;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.InvestorStockDailyRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 시장 탭 수급 → 매매 동향 표의 '종가'(2026-11-11 화면 점검 9차).
 *
 * <p>재현(11/11 12:28, 11/10 행): 수급 표의 종가·등락률은 그날 저녁 받은 값이라 다음 날 16:15 회차 전까지 KRX 종가와 다르다 —
 * SK하이닉스 표 1,208,000 vs KRX 종가 1,212,000, NAVER 192,300 vs 193,000. 그 날짜의 일봉(아침 07:40 재수집 뒤 KRX 종가)이 있으면
 * 응답의 종가·등락률을 그 봉으로 바꾼다. 저장 행은 고치지 않는다. 등락률은 그 봉 종가 ÷ 바로 앞 거래일 봉 종가 − 1(앞 봉이 없거나
 * 사이에 거래일이 비면 원래 값 그대로). 봉이 없으면 원래 값 그대로.
 */
class InvestorTradeBarCloseTest {

    private static final LocalDate NOV10 = LocalDate.of(2026, 11, 10);   // 화요일
    private static final LocalDate NOV9 = LocalDate.of(2026, 11, 9);     // 월요일
    private static final LocalDate NOV6 = LocalDate.of(2026, 11, 6);     // 금요일

    private static boolean weekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static StockPriceHistory bar(String code, LocalDate date, String close) {
        StockPriceHistory h = new StockPriceHistory();
        h.setStockCode(code);
        h.setTradeDate(date);
        h.setClosePrice(new BigDecimal(close));
        return h;
    }

    private static InvestorTradeDto row(String code, String price, String rate) {
        return InvestorTradeDto.builder().stockCode(code).tradeDate(NOV10)
                .currentPrice(new BigDecimal(price)).changeRate(new BigDecimal(rate)).build();
    }

    @Test
    @DisplayName("재현: SK하이닉스·NAVER — 그 날짜 봉이 있으면 종가는 봉 종가, 등락률은 앞 거래일 봉 대비")
    void barCloseReplacesEveningPrice() {
        InvestorTradeDto hynix = row("000660", "1208000", "1.51");
        InvestorTradeDto naver = row("035420", "192300", "-0.36");

        InvestorTradeService.overlayBarCloses(List.of(hynix, naver), List.of(
                bar("000660", NOV10, "1212000"), bar("000660", NOV9, "1200000"),
                bar("035420", NOV10, "193000"), bar("035420", NOV9, "193000")),
                InvestorTradeBarCloseTest::weekend);

        assertThat(hynix.getCurrentPrice()).isEqualByComparingTo("1212000");
        assertThat(hynix.getChangeRate()).isEqualByComparingTo("1.00");
        assertThat(naver.getCurrentPrice()).isEqualByComparingTo("193000");
        assertThat(naver.getChangeRate()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("봉이 없으면 원래 값 · 앞 봉이 없거나 사이에 거래일이 비면 종가만 바꾸고 등락률은 원래 값")
    void missingBarsKeepStoredValues() {
        InvestorTradeDto noBar = row("005930", "100000", "2.00");
        InvestorTradeDto noPrev = row("000660", "1208000", "1.51");
        InvestorTradeDto gap = row("035420", "192300", "-0.36");

        InvestorTradeService.overlayBarCloses(List.of(noBar, noPrev, gap), List.of(
                bar("000660", NOV10, "1212000"),
                bar("035420", NOV10, "193000"), bar("035420", NOV6, "180000")),   // 11/9(월) 봉이 빠졌다
                InvestorTradeBarCloseTest::weekend);

        assertThat(noBar.getCurrentPrice()).isEqualByComparingTo("100000");
        assertThat(noBar.getChangeRate()).isEqualByComparingTo("2.00");
        assertThat(noPrev.getCurrentPrice()).isEqualByComparingTo("1212000");
        assertThat(noPrev.getChangeRate()).isEqualByComparingTo("1.51");
        assertThat(gap.getCurrentPrice()).isEqualByComparingTo("193000");
        assertThat(gap.getChangeRate()).as("11/6 대비가 아니다 — 원래 값").isEqualByComparingTo("-0.36");
    }

    @Test
    @DisplayName("월요일 행은 금요일 봉이 앞 거래일이다(주말은 거래일이 아니다)")
    void mondayComparesWithFriday() {
        InvestorTradeDto mon = InvestorTradeDto.builder().stockCode("000660").tradeDate(NOV9)
                .currentPrice(new BigDecimal("1")).changeRate(new BigDecimal("9")).build();
        InvestorTradeService.overlayBarCloses(List.of(mon),
                List.of(bar("000660", NOV9, "1100000"), bar("000660", NOV6, "1000000")), InvestorTradeBarCloseTest::weekend);
        assertThat(mon.getChangeRate()).isEqualByComparingTo("10.00");
    }

    @SuppressWarnings("unchecked")
    private static InvestorTradeService service(InvestorDailyTradeRepository rank, StockPriceHistoryRepository bars,
                                                InvestorStockDailyRepository confirmed) {
        MarketCalendarService cal = mock(MarketCalendarService.class);
        when(cal.isMarketClosed(any(LocalDate.class))).thenAnswer(inv -> weekend(inv.getArgument(0)));
        InvestorTradeService s = new InvestorTradeService(rank, mock(KisInvestorDataCollector.class), mock(KoreaInvestmentService.class),
                mock(RedisCacheService.class), cal, mock(org.springframework.beans.factory.ObjectProvider.class));
        ReflectionTestUtils.setField(s, "stockPriceHistoryRepository", bars);
        if (confirmed != null) ReflectionTestUtils.setField(s, "investorStockDailyRepository", confirmed);
        return s;
    }

    private static InvestorDailyTrade entity(String code, String investor, String price) {
        InvestorDailyTrade t = new InvestorDailyTrade();
        t.setStockCode(code);
        t.setStockName(code);
        t.setTradeDate(NOV10);
        t.setInvestorType(investor);
        t.setTradeType("BUY");
        t.setNetBuyAmount(new BigDecimal("100"));
        t.setCurrentPrice(new BigDecimal(price));
        t.setChangeRate(new BigDecimal("1.51"));
        t.setRankNum(1);
        return t;
    }

    @Test
    @DisplayName("매매 동향 응답(외국인·기관·연기금·개인)은 봉을 한 번에 읽고 저장 행은 건드리지 않는다")
    void allTopTradesReadsBarsOnce() {
        InvestorDailyTradeRepository rank = mock(InvestorDailyTradeRepository.class);
        StockPriceHistoryRepository bars = mock(StockPriceHistoryRepository.class);
        when(rank.findLatestTradeDate()).thenReturn(NOV10);
        InvestorDailyTrade stored = entity("000660", "FOREIGN", "1208000");
        when(rank.findTopTradesByInvestorAndTradeType(eq("FOREIGN"), eq("BUY"), eq(NOV10), any()))
                .thenReturn(List.of(stored));
        when(rank.findTopTradesByInvestorAndTradeType(eq("INSTITUTION"), eq("BUY"), eq(NOV10), any()))
                .thenReturn(List.of(entity("035420", "INSTITUTION", "192300")));
        when(bars.findByStockCodesSince(anyList(), any(LocalDate.class))).thenReturn(List.of(
                bar("000660", NOV10, "1212000"), bar("000660", NOV9, "1200000"), bar("035420", NOV10, "193000")));

        Map<String, List<InvestorTradeDto>> res = service(rank, bars, null).getAllInvestorTopTrades("BUY", 50);

        assertThat(res.get("FOREIGN").get(0).getCurrentPrice()).isEqualByComparingTo("1212000");
        assertThat(res.get("FOREIGN").get(0).getChangeRate()).isEqualByComparingTo("1.00");
        assertThat(res.get("INSTITUTION").get(0).getCurrentPrice()).isEqualByComparingTo("193000");
        verify(bars, times(1)).findByStockCodesSince(anyList(), any(LocalDate.class));
        assertThat(stored.getCurrentPrice()).as("저장 행은 그대로").isEqualByComparingTo("1208000");
    }

    @Test
    @DisplayName("봉 조회가 실패하면 저장 값 그대로 보인다")
    void barQueryFailureKeepsStoredValues() {
        InvestorDailyTradeRepository rank = mock(InvestorDailyTradeRepository.class);
        StockPriceHistoryRepository bars = mock(StockPriceHistoryRepository.class);
        when(rank.findLatestTradeDate()).thenReturn(NOV10);
        when(rank.findTopTradesByInvestorAndTradeType(eq("FOREIGN"), eq("BUY"), eq(NOV10), any()))
                .thenReturn(List.of(entity("000660", "FOREIGN", "1208000")));
        when(bars.findByStockCodesSince(anyList(), any(LocalDate.class))).thenThrow(new RuntimeException("db"));

        Map<String, List<InvestorTradeDto>> res = service(rank, bars, null).getAllInvestorTopTrades("BUY", 50);

        assertThat(res.get("FOREIGN").get(0).getCurrentPrice()).isEqualByComparingTo("1208000");
    }

    @Test
    @DisplayName("종목 상세 '투자자 동향' 일별 종가도 같은 봉으로 — 확정치 표의 저녁 종가가 아니라")
    void stockDetailClosesFollowBars() {
        InvestorDailyTradeRepository rank = mock(InvestorDailyTradeRepository.class);
        InvestorStockDailyRepository confirmed = mock(InvestorStockDailyRepository.class);
        StockPriceHistoryRepository bars = mock(StockPriceHistoryRepository.class);
        InvestorStockDaily c10 = new InvestorStockDaily();
        c10.setStockCode("000660");
        c10.setTradeDate(NOV10);
        c10.setClosePrice(new BigDecimal("1208000"));
        c10.setForeignNet(new BigDecimal("10"));
        InvestorStockDaily c9 = new InvestorStockDaily();
        c9.setStockCode("000660");
        c9.setTradeDate(NOV9);
        c9.setClosePrice(new BigDecimal("1200000"));
        when(confirmed.findByStockCodeAndTradeDateBetween(eq("000660"), any(), any())).thenReturn(List.of(c10, c9));
        when(bars.findByStockCodeAndDateRange(eq("000660"), any(), any())).thenReturn(List.of(bar("000660", NOV10, "1212000")));

        StockInvestorDetailDto dto = service(rank, bars, confirmed).getStockInvestorDetail("000660", 30);

        Map<LocalDate, BigDecimal> closes = new java.util.HashMap<>();
        for (StockInvestorDetailDto.DailyInvestorTrade d : dto.getDailyTrades()) closes.put(d.getTradeDate(), d.getClosePrice());
        assertThat(closes.get(NOV10)).isEqualByComparingTo("1212000");
        assertThat(closes.get(NOV9)).as("봉이 없는 날은 그대로").isEqualByComparingTo("1200000");
    }
}
