package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 거래대금 하한을 저장 봉으로 다시 판정한다(2026-11-11) — 계산 밖에서 '지금 이 종목이 매수 후보가 될 수 있나'를 물을 때.
 * DB 에서 되살린 추천 목록과 종목 결론 카드가 쓴다. 판정은 {@link LiquidityFloor} 그대로(단일 출처), 봉 노후 기준은 기술 채점과 같은
 * 직전 거래일. 외부 호출 없음 — 저장 봉만 읽는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiquidityFloorService {

    /** 20거래일 + 연휴 여유(달력일). */
    static final int LOOKBACK_DAYS = 60;

    private final StockPriceHistoryRepository priceHistoryRepository;
    private final MarketCalendarService marketCalendar;

    @Value("${recommendation.min-avg-traded-value-eok:5}")
    double minAvgTradedValueEok = 5;

    /** 판정 결과 — 평균(억 원, 모르면 null)과 판정. */
    public record Assessment(BigDecimal avgEok, LiquidityFloor.Verdict verdict) {
        public boolean below() { return verdict == LiquidityFloor.Verdict.BELOW; }
    }

    /** 하한(억 원) — 꺼져 있으면 null. */
    public Double floorOrNull() {
        return LiquidityFloor.isOff(minAvgTradedValueEok) ? null : minAvgTradedValueEok;
    }

    /**
     * 종목별 판정. 봉 조회가 실패하면 null(모른다 — 호출자는 막지 않는다). 하한이 꺼져 있으면 조회하지 않고 전부 PASS(평균 null).
     */
    public Map<String, Assessment> assess(Collection<String> codes) {
        Map<String, Assessment> out = new HashMap<>();
        if (codes == null || codes.isEmpty()) return out;
        if (floorOrNull() == null) {
            for (String c : codes) out.put(c, new Assessment(null, LiquidityFloor.Verdict.PASS));
            return out;
        }
        LocalDate today = LocalDate.now();
        Map<String, List<StockPriceHistory>> bars;
        try {
            bars = priceHistoryRepository.findByStockCodesSince(List.copyOf(codes), today.minusDays(LOOKBACK_DAYS))
                    .stream().collect(Collectors.groupingBy(StockPriceHistory::getStockCode));
        } catch (Exception e) {
            log.warn("[거래대금 하한] 봉 조회 실패 — 판정하지 않는다(막지 않음): {}", e.getMessage());
            return null;
        }
        LocalDate cutoff = staleCutoff(today);
        for (String c : codes) {
            BigDecimal avg = LiquidityFloor.avgTradedValueEok(bars.get(c), cutoff);
            out.put(c, new Assessment(avg, LiquidityFloor.judge(avg, minAvgTradedValueEok)));
        }
        return out;
    }

    /** 한 종목 — 실패면 null. */
    public Assessment assess(String code) {
        Map<String, Assessment> m = assess(List.of(code));
        return m == null ? null : m.get(code);
    }

    private LocalDate staleCutoff(LocalDate today) {
        try {
            return marketCalendar.minusTradingDays(today, 1);
        } catch (Exception e) {
            return null;   // 달력 실패 — 노후 판정 없음(기술 채점과 같은 fail-open)
        }
    }
}
