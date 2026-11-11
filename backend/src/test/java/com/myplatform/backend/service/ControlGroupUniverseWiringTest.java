package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대조군 유니버스의 거래대금 하한 배선(2026-11-11 검토 4·5번) — 조회·실패·노후 기준·끔·쪼그라든 유니버스.
 */
class ControlGroupUniverseWiringTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);   // 목요일 — 직전 거래일 11/11

    private StockPriceHistoryRepository repo;
    private MarketCalendarService calendar;

    @SuppressWarnings("unchecked")
    private ControlGroupService service(double floor) {
        repo = mock(StockPriceHistoryRepository.class);
        calendar = mock(MarketCalendarService.class);
        when(calendar.minusTradingDays(DAY, 1)).thenReturn(DAY.minusDays(1));
        ObjectProvider<StockPriceService> prices = mock(ObjectProvider.class);
        ObjectProvider<StockStatusService> status = mock(ObjectProvider.class);   // getIfAvailable() = null → 활성 필터 생략
        ObjectProvider<ControlGroupService> self = mock(ObjectProvider.class);
        ObjectProvider<MarketCalendarService> cal = mock(ObjectProvider.class);
        when(cal.getIfAvailable()).thenReturn(calendar);
        ControlGroupService s = new ControlGroupService(mock(SignalOutcomeRepository.class), repo, prices, status, self, cal);
        ReflectionTestUtils.setField(s, "minAvgTradedValueEok", floor);
        return s;
    }

    /** latest 부터 거꾸로 20봉, 하루 eok 억. */
    private static List<StockPriceHistory> bars(String code, LocalDate latest, double eok) {
        List<StockPriceHistory> out = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            out.add(StockPriceHistory.builder().stockCode(code).tradeDate(latest.minusDays(i))
                    .closePrice(BigDecimal.valueOf(10_000)).volume(BigDecimal.valueOf(Math.round(eok * 1e8 / 10_000))).build());
        }
        return out;
    }

    private static List<String> codes(String prefix, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(prefix + i);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> resolve(ControlGroupService s) {
        return (List<String>) ReflectionTestUtils.invokeMethod(s, "resolveUniverse", DAY);
    }

    @Test
    @DisplayName("미달·노후 종목을 빼고 하루 캐시한다 — 노후 기준은 직전 거래일(추천 기술 채점과 같다)")
    void filtersAndCaches() {
        ControlGroupService s = service(5);
        List<String> ok = codes("OK", 60);
        List<String> all = new ArrayList<>(ok);
        all.add("THIN");
        all.add("OLD");
        when(repo.findActiveStockCodesWithMinHistory(anyLong(), any())).thenReturn(all);
        List<StockPriceHistory> rows = new ArrayList<>();
        for (String c : ok) rows.addAll(bars(c, DAY.minusDays(1), 19.7));
        rows.addAll(bars("THIN", DAY.minusDays(1), 0.23));
        rows.addAll(bars("OLD", DAY.minusDays(2), 100));   // 직전 거래일보다 오래됨 = 판정 불가 → 뽑지 않는다
        when(repo.findByStockCodesSince(anyList(), any())).thenReturn(rows);

        assertThat(resolve(s)).containsExactlyElementsOf(ok);
        resolve(s);
        verify(repo, times(1)).findByStockCodesSince(anyList(), any());   // 하루 캐시
        assertThat(s.currentUniverseSize()).isEqualTo(60);
    }

    @Test
    @DisplayName("봉 조회가 실패하면 빈 목록(대조군 없음)이고 캐시하지 않는다 — 하한을 모르는 채로 뽑지 않는다")
    void queryFailureIsEmptyAndNotCached() {
        ControlGroupService s = service(5);
        when(repo.findActiveStockCodesWithMinHistory(anyLong(), any())).thenReturn(codes("OK", 60));
        when(repo.findByStockCodesSince(anyList(), any())).thenThrow(new IllegalStateException("DB"));

        assertThat(resolve(s)).isEmpty();
        assertThat(s.currentUniverseSize()).isEqualTo(-1);
    }

    @Test
    @DisplayName("재현: 통과 종목이 50 미만이면(전날 봉 미수집 등) 그 목록은 쓰되 캐시하지 않는다 — 다음 시그널이 다시 계산")
    void shrunkenUniverseIsNotCached() {
        ControlGroupService s = service(5);
        List<String> all = codes("OK", 60);
        when(repo.findActiveStockCodesWithMinHistory(anyLong(), any())).thenReturn(all);
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) rows.addAll(bars("OK" + i, i < 10 ? DAY.minusDays(1) : DAY.minusDays(3), 19.7));
        when(repo.findByStockCodesSince(anyList(), any())).thenReturn(rows);

        assertThat(resolve(s)).hasSize(10);
        assertThat(s.currentUniverseSize()).isEqualTo(-1);
        resolve(s);
        verify(repo, times(2)).findByStockCodesSince(anyList(), any());
    }

    @Test
    @DisplayName("하한을 끄면 봉을 읽지 않고 종전 유니버스 그대로")
    void offDoesNotQuery() {
        ControlGroupService s = service(0);
        when(repo.findActiveStockCodesWithMinHistory(anyLong(), any())).thenReturn(List.of("A", "B"));

        assertThat(resolve(s)).containsExactly("A", "B");
        verify(repo, never()).findByStockCodesSince(anyList(), any());
    }
}
