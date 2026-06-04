package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockMaster;
import com.myplatform.backend.entity.StockPrice;
import com.myplatform.backend.repository.StockMasterRepository;
import com.myplatform.backend.repository.StockPriceRepository;
import com.myplatform.backend.util.PriceOutlierDiagnostics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * P0-2 ×10 가격 이상치 <b>근본원인 진단·분류</b> 스캐너 (읽기 전용 — 가격 보정/수정 없음).
 *
 * <p>{@code warnIfPriceOutlier} 가 가격을 보정하지 않으므로 ×10 로 들어온 시세는 {@code stock_price}
 * 에 그대로 적재된다. 이 스캐너는 종목별 시세 이력에서 <b>직전/주변 정상가 대비 ×N(기본 5배 이상) 으로 튄
 * 행</b>을 찾아, 분류기({@link PriceOutlierDiagnostics})로 두 가설("응답 자체 ×10" vs "현재가 필드 단독")을
 * 판정하고, 다음 축으로 군집을 분류한다:
 *
 * <ul>
 *   <li><b>market</b> (KOSPI / KOSDAQ / KONEX / ETF) — {@code stock_master} 조인</li>
 *   <li><b>세션 시간대</b> — {@code fetched_at} 기준. KIS 가 KRX+NXT 통합시세(UN)로 호출되므로
 *       <b>NXT 단독 구간(08:00–09:00 프리, 15:40–20:00 애프터)</b>에 ×10 이 몰리면 통합시세 필드 규약
 *       차이(=NXT 관여) 가 강하게 의심된다. 09:00–15:30 은 KRX+NXT 중첩.</li>
 * </ul>
 *
 * <p>스키마에 NXT/이중상장 플래그가 없어(=stock_master 는 market 만 보유) NXT 관여 여부는 위 세션
 * 시간대를 프록시로 추론한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PriceScalingDiagnosticService {

    private final StockPriceRepository stockPriceRepository;
    private final StockMasterRepository stockMasterRepository;

    /** 정상가 대비 이 배수 이상(또는 역수 이하)으로 튀면 스케일 이상치 후보. (P0-1 가드와 동일 임계) */
    private static final BigDecimal HIGH = new BigDecimal("5");
    private static final BigDecimal LOW = new BigDecimal("0.2");
    /** 한 종목당 최소 이 정도 행이 있어야 median(정상 앵커) 이 신뢰 가능. */
    private static final int MIN_ROWS_FOR_ANCHOR = 3;

    public record Event(String stockCode, String stockName, String market, String session,
                        String kind, BigDecimal multiple, BigDecimal price, BigDecimal anchorMedian,
                        LocalDateTime fetchedAt) {}

    public record Report(int hoursBack, long scannedRows, int distinctStocksScanned,
                         int outlierEvents, int distinctOutlierStocks,
                         Map<String, Integer> byMarket, Map<String, Integer> bySession,
                         Map<String, Integer> byKind, List<Event> events, String note) {}

    /**
     * @param hoursBack 스캔 기간(시간). 720=30일 기본.
     * @param maxEvents 반환 이벤트 상한(과다 방지).
     */
    public Report scan(int hoursBack, int maxEvents) {
        LocalDateTime since = LocalDateTime.now().minusHours(Math.max(1, hoursBack));
        List<StockPrice> rows;
        try {
            rows = stockPriceRepository.findAllSince(since);
        } catch (Exception e) {
            log.warn("[P0-2] stock_price 스캔 실패: {}", e.getMessage());
            rows = List.of();
        }

        // 종목별 그룹화
        Map<String, List<StockPrice>> byCode = rows.stream()
                .filter(r -> r.getStockCode() != null && r.getCurrentPrice() != null)
                .collect(Collectors.groupingBy(StockPrice::getStockCode));

        List<Event> events = new ArrayList<>();
        for (Map.Entry<String, List<StockPrice>> e : byCode.entrySet()) {
            List<StockPrice> list = e.getValue();
            if (list.size() < MIN_ROWS_FOR_ANCHOR) continue;
            BigDecimal median = median(list);
            if (median == null || median.compareTo(BigDecimal.ZERO) <= 0) continue;

            for (StockPrice sp : list) {
                BigDecimal price = sp.getCurrentPrice();
                if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal ratio = price.divide(median, 2, java.math.RoundingMode.HALF_UP);
                if (ratio.compareTo(HIGH) < 0 && ratio.compareTo(LOW) > 0) continue; // 정상

                // median 을 정상 앵커로 사용해 두 가설 분류
                PriceOutlierDiagnostics.Result diag = PriceOutlierDiagnostics.classify(
                        price, sp.getHighPrice(), sp.getLowPrice(), sp.getChangeRate(), median);
                events.add(new Event(
                        e.getKey(), sp.getStockName(), null,
                        sessionOf(sp.getFetchedAt()),
                        diag.kind().name(), diag.multiple(), price, median, sp.getFetchedAt()));
            }
        }

        // market 조인 (이상치 종목만)
        List<String> codes = events.stream().map(Event::stockCode).distinct().collect(Collectors.toList());
        Map<String, String> marketByCode = new LinkedHashMap<>();
        if (!codes.isEmpty()) {
            try {
                for (StockMaster m : stockMasterRepository.findAllById(codes)) {
                    marketByCode.put(m.getStockCode(), m.getMarket() != null ? m.getMarket() : "UNKNOWN");
                }
            } catch (Exception ex) {
                log.debug("[P0-2] stock_master 조인 실패: {}", ex.getMessage());
            }
        }
        List<Event> enriched = events.stream()
                .map(ev -> new Event(ev.stockCode(), ev.stockName(),
                        marketByCode.getOrDefault(ev.stockCode(), "UNKNOWN"),
                        ev.session(), ev.kind(), ev.multiple(), ev.price(), ev.anchorMedian(), ev.fetchedAt()))
                .sorted((a, b) -> b.fetchedAt() == null ? -1
                        : a.fetchedAt() == null ? 1 : b.fetchedAt().compareTo(a.fetchedAt()))
                .collect(Collectors.toList());

        Map<String, Integer> byMarket = new TreeMap<>();
        Map<String, Integer> bySession = new TreeMap<>();
        Map<String, Integer> byKind = new TreeMap<>();
        for (Event ev : enriched) {
            byMarket.merge(ev.market(), 1, Integer::sum);
            bySession.merge(ev.session(), 1, Integer::sum);
            byKind.merge(ev.kind(), 1, Integer::sum);
        }

        List<Event> capped = enriched.size() > maxEvents ? enriched.subList(0, maxEvents) : enriched;
        String note = enriched.isEmpty()
                ? "스캔 기간 내 ×N 스케일 이상치 없음. (가드가 가격을 보정하지 않으므로 이상치는 DB 에 그대로 남는다 — 0건이면 해당 기간 실제로 발생 안 함)"
                : "NXT 단독 구간(PREMARKET/AFTERHOURS) 집중 → 통합시세(UN) 필드 규약 차이 의심. "
                  + "BATCH_SCALED 우세 → '응답 자체 ×N', CURRENT_FIELD_OUTLIER 우세 → '현재가 필드 매핑 오류'.";

        return new Report(hoursBack, rows.size(), byCode.size(),
                enriched.size(), codes.size(), byMarket, bySession, byKind, capped, note);
    }

    /** 종목 시세 이력의 median 현재가 (정상 앵커). ×N 이상치는 소수라 median 은 정상값으로 수렴. */
    private BigDecimal median(List<StockPrice> list) {
        List<BigDecimal> prices = list.stream()
                .map(StockPrice::getCurrentPrice)
                .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                .sorted()
                .collect(Collectors.toList());
        if (prices.isEmpty()) return null;
        int n = prices.size();
        if (n % 2 == 1) return prices.get(n / 2);
        return prices.get(n / 2 - 1).add(prices.get(n / 2))
                .divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
    }

    /** fetched_at → 세션 시간대. NXT 단독 구간을 별도 라벨로 분리 (NXT 관여 프록시). */
    static String sessionOf(LocalDateTime at) {
        if (at == null) return "UNKNOWN";
        LocalTime t = at.toLocalTime();
        if (t.isBefore(LocalTime.of(8, 0))) return "OFF_HOURS";
        if (t.isBefore(LocalTime.of(9, 0))) return "NXT_PREMARKET";      // 08:00–09:00 NXT 단독
        if (t.isBefore(LocalTime.of(15, 30))) return "KRX_REGULAR";      // 09:00–15:30 KRX+NXT 중첩
        if (t.isBefore(LocalTime.of(15, 40))) return "KRX_CLOSE_AUCTION";// 15:30–15:40 종가단일가
        if (!t.isAfter(LocalTime.of(20, 0))) return "NXT_AFTERHOURS";    // 15:40–20:00 NXT 단독
        return "OFF_HOURS";
    }
}
