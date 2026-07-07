package com.myplatform.backend.service;

import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.util.InvestorBuyStreakCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 외인/기관 연속 순매수일 조회 — {@code investor_daily_trade}(순매수 상위 top-N) <b>재사용·read-only</b>.
 *
 * <p>표시·참고 전용(산식 미편입, unverified). streak5 는 백테스트상 <b>약한 양(+)의 신호</b>였어서
 * "참고" 톤으로만 노출한다. 계산은 순수 함수 {@link InvestorBuyStreakCalculator}(테스트 대상).
 * 보드 배치는 IN 절 일괄 조회로 N+1 을 피한다.
 */
@Service
@RequiredArgsConstructor
public class InvestorBuyStreakService {

    /** 조회 윈도우(일) — 연속일 계산에 넉넉한 창(약 4주 거래일). */
    public static final int WINDOW_DAYS = 40;

    private static final String FOREIGN = "FOREIGN";
    private static final String INSTITUTION = "INSTITUTION";

    private final InvestorDailyTradeRepository repository;

    /** 외인/기관 연속 순매수일. null=데이터 부족(§4c) / 0=최신일 순매수 아님. */
    public record Streaks(Integer foreign, Integer institution) {
        static final Streaks EMPTY = new Streaks(null, null);
    }

    /** 단일 종목(종목상세 QuickSummaryBar 배지용). best-effort — 실패 시 EMPTY. */
    @Transactional(readOnly = true)
    public Streaks getStreaks(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return Streaks.EMPTY;
        LocalDate start = LocalDate.now().minusDays(WINDOW_DAYS);

        List<InvestorDailyTrade> rows = repository.findByStockCodeAndDateRange(stockCode, start, LocalDate.now());
        Set<LocalDate> foreignBuy = buyDates(rows, FOREIGN);
        Set<LocalDate> instBuy = buyDates(rows, INSTITUTION);

        List<LocalDate> foreignCal = repository.findDistinctBuyTradeDatesSince(FOREIGN, start);
        List<LocalDate> instCal = repository.findDistinctBuyTradeDatesSince(INSTITUTION, start);

        return new Streaks(
                InvestorBuyStreakCalculator.consecutiveNetBuyDays(foreignCal, foreignBuy),
                InvestorBuyStreakCalculator.consecutiveNetBuyDays(instCal, instBuy));
    }

    /** 보드용 배치 — 코드 집합 전체를 IN 절 일괄 조회(N+1 금지). 코드별 Streaks(외인/기관). */
    @Transactional(readOnly = true)
    public Map<String, Streaks> getStreaksForCodes(Collection<String> codes) {
        Map<String, Streaks> out = new HashMap<>();
        if (codes == null || codes.isEmpty()) return out;
        LocalDate start = LocalDate.now().minusDays(WINDOW_DAYS);

        // 캘린더는 투자자별 1쿼리(전 종목 공통). 매수일은 IN 절 1쿼리/투자자. 총 4쿼리 — 보드 크기 무관.
        List<LocalDate> foreignCal = repository.findDistinctBuyTradeDatesSince(FOREIGN, start);
        List<LocalDate> instCal = repository.findDistinctBuyTradeDatesSince(INSTITUTION, start);

        Map<String, Set<LocalDate>> foreignBuyByCode =
                buyDatesByCode(repository.findBuyTradesByStockCodesAndInvestorSince(codes, FOREIGN, start));
        Map<String, Set<LocalDate>> instBuyByCode =
                buyDatesByCode(repository.findBuyTradesByStockCodesAndInvestorSince(codes, INSTITUTION, start));

        for (String code : codes) {
            if (code == null) continue;
            Integer f = InvestorBuyStreakCalculator.consecutiveNetBuyDays(
                    foreignCal, foreignBuyByCode.getOrDefault(code, Set.of()));
            Integer i = InvestorBuyStreakCalculator.consecutiveNetBuyDays(
                    instCal, instBuyByCode.getOrDefault(code, Set.of()));
            out.put(code, new Streaks(f, i));
        }
        return out;
    }

    private static Set<LocalDate> buyDates(List<InvestorDailyTrade> rows, String investorType) {
        Set<LocalDate> dates = new HashSet<>();
        if (rows == null) return dates;
        for (InvestorDailyTrade t : rows) {
            if (t == null) continue;
            if (!investorType.equals(t.getInvestorType())) continue;
            if (!"BUY".equals(t.getTradeType())) continue;
            if (t.getTradeDate() != null) dates.add(t.getTradeDate());
        }
        return dates;
    }

    private static Map<String, Set<LocalDate>> buyDatesByCode(List<InvestorDailyTrade> rows) {
        Map<String, Set<LocalDate>> byCode = new HashMap<>();
        if (rows == null) return byCode;
        for (InvestorDailyTrade t : rows) {
            if (t == null || t.getStockCode() == null || t.getTradeDate() == null) continue;
            byCode.computeIfAbsent(t.getStockCode(), k -> new HashSet<>()).add(t.getTradeDate());
        }
        return byCode;
    }
}
