package com.myplatform.backend.service;

import com.myplatform.backend.dto.TechnicalIndicatorsDto;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 기술적 분석 기반 퀀트 서비스
 * - TA 스크리너: RSI, 골든크로스, 볼린저, 거래량 등 조건 필터링
 * - 상관관계 매트릭스: 종가 기반 피어슨 상관계수
 *
 * AI / 외부 API 호출 0건 — 모두 stock_price_history DB 캐시 기반.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QuantTaService {

    private final StockPriceHistoryRepository priceHistoryRepository;
    private final TechnicalIndicatorService technicalIndicatorService;

    private static final int MIN_HISTORY_DAYS = 25;       // 최소 일봉 수 (MA20 + RSI 안정성)
    private static final int LOAD_WINDOW_DAYS = 130;      // 로드 창 (영업일 기준 약 6개월)
    private static final int CORRELATION_DAYS = 60;       // 상관관계 기본 윈도우
    private static final int MAX_CORRELATION_STOCKS = 30; // 매트릭스 상한 (n^2 폭증 방지)

    // ==================== 1. TA 스크리너 ====================

    /**
     * 조건 조합으로 종목 필터링.
     * 모든 조건은 AND. null 또는 false인 조건은 무시.
     */
    public Map<String, Object> screen(ScreenerFilter filter, int limit) {
        if (filter == null) filter = new ScreenerFilter();
        int finalLimit = limit > 0 ? Math.min(limit, 200) : 50;

        // 1. universe 추출 (history 충분한 종목)
        List<String> universe = priceHistoryRepository.findStockCodesWithMinHistory(MIN_HISTORY_DAYS);
        log.info("[TA스크리너] universe: {} 종목 / 조건: {}", universe.size(), filter);

        if (universe.isEmpty()) {
            return Map.of("results", List.of(), "universeSize", 0, "matchedCount", 0);
        }

        // 2. 일봉 일괄 로드
        LocalDate since = LocalDate.now().minusDays(LOAD_WINDOW_DAYS);
        List<StockPriceHistory> all = priceHistoryRepository.findByStockCodesSince(universe, since);
        Map<String, List<StockPriceHistory>> byCode = all.stream()
                .collect(Collectors.groupingBy(StockPriceHistory::getStockCode));

        // 3. 종목별 지표 계산 + 조건 필터
        List<ScreenerHit> hits = new ArrayList<>();
        for (Map.Entry<String, List<StockPriceHistory>> e : byCode.entrySet()) {
            List<StockPriceHistory> rows = e.getValue();
            if (rows.size() < MIN_HISTORY_DAYS) continue;

            ScreenerHit hit = evaluate(e.getKey(), rows, filter);
            if (hit != null) hits.add(hit);
        }

        // 4. 정렬 — 점수 내림차순 (조건 통과 강도)
        hits.sort(Comparator.comparingInt(ScreenerHit::getMatchScore).reversed());
        List<ScreenerHit> top = hits.stream().limit(finalLimit).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", top);
        result.put("universeSize", universe.size());
        result.put("matchedCount", hits.size());
        result.put("filter", filter);
        return result;
    }

    private ScreenerHit evaluate(String stockCode, List<StockPriceHistory> rowsDesc, ScreenerFilter f) {
        // rowsDesc: tradeDate DESC. 지표 계산용으로 종가 추출 (최신 = index 0).
        List<BigDecimal> prices = rowsDesc.stream()
                .map(StockPriceHistory::getClosePrice)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (prices.size() < MIN_HISTORY_DAYS) return null;

        StockPriceHistory latest = rowsDesc.get(0);
        BigDecimal currentPrice = latest.getClosePrice();
        if (currentPrice == null || currentPrice.signum() <= 0) return null;

        TechnicalIndicatorsDto ind = technicalIndicatorService.calculate(prices);
        if (ind == null) return null;

        // 거래량 비율: 최근 거래량 / 직전 20일 평균
        BigDecimal volRatio = computeVolumeRatio(rowsDesc, 20);

        // 볼린저 — calculate()로 안 채워지는 항목이 있어 별도 호출
        TechnicalIndicatorService.BollingerBandsResult bb =
                technicalIndicatorService.calculateBollingerBands(prices);

        int score = 0;
        List<String> matchedTags = new ArrayList<>();

        // -------- 조건 평가 --------
        if (f.rsiBelow != null) {
            BigDecimal rsi = ind.getRsi14();
            if (rsi == null || rsi.compareTo(BigDecimal.valueOf(f.rsiBelow)) >= 0) return null;
            score += 20;
            matchedTags.add("RSI<" + f.rsiBelow);
        }
        if (f.rsiAbove != null) {
            BigDecimal rsi = ind.getRsi14();
            if (rsi == null || rsi.compareTo(BigDecimal.valueOf(f.rsiAbove)) <= 0) return null;
            score += 15;
            matchedTags.add("RSI>" + f.rsiAbove);
        }
        if (Boolean.TRUE.equals(f.goldenCross)) {
            if (!Boolean.TRUE.equals(ind.getIsGoldenCross())) return null;
            score += 25;
            matchedTags.add("골든크로스");
        }
        if (Boolean.TRUE.equals(f.arrangedUp)) {
            if (!Boolean.TRUE.equals(ind.getIsArrangedUp())) return null;
            score += 15;
            matchedTags.add("정배열");
        }
        if (Boolean.TRUE.equals(f.aboveMa20)) {
            if (!Boolean.TRUE.equals(ind.getIsAboveMa20())) return null;
            score += 5;
            matchedTags.add("MA20위");
        }
        if (Boolean.TRUE.equals(f.belowMa20)) {
            if (Boolean.TRUE.equals(ind.getIsAboveMa20())) return null;
            score += 5;
            matchedTags.add("MA20아래");
        }
        if (f.volumeRatioMin != null) {
            if (volRatio == null || volRatio.compareTo(BigDecimal.valueOf(f.volumeRatioMin)) < 0) return null;
            score += 15;
            matchedTags.add("거래량x" + f.volumeRatioMin);
        }
        if (Boolean.TRUE.equals(f.bollingerLowerTouch)) {
            if (bb == null || bb.getLowerBand() == null) return null;
            // 종가가 하단 밴드의 102% 이하면 터치/근접으로 판단
            BigDecimal threshold = bb.getLowerBand().multiply(BigDecimal.valueOf(1.02));
            if (currentPrice.compareTo(threshold) > 0) return null;
            score += 20;
            matchedTags.add("볼린저하단터치");
        }
        if (Boolean.TRUE.equals(f.bollingerSqueeze)) {
            if (bb == null || !Boolean.TRUE.equals(bb.getIsSqueeze())) return null;
            score += 15;
            matchedTags.add("볼린저스퀴즈");
        }
        if (f.changeRateMin != null) {
            BigDecimal cr = latest.getChangeRate();
            if (cr == null || cr.compareTo(BigDecimal.valueOf(f.changeRateMin)) < 0) return null;
            score += 5;
        }
        if (f.changeRateMax != null) {
            BigDecimal cr = latest.getChangeRate();
            if (cr == null || cr.compareTo(BigDecimal.valueOf(f.changeRateMax)) > 0) return null;
            score += 5;
        }

        // 조건이 하나도 안 걸린 경우(=빈 필터) → 결과 안 반환
        if (matchedTags.isEmpty() && score == 0) return null;

        ScreenerHit hit = new ScreenerHit();
        hit.stockCode = stockCode;
        hit.stockName = latest.getStockName();
        hit.tradeDate = latest.getTradeDate();
        hit.closePrice = currentPrice;
        hit.changeRate = latest.getChangeRate();
        hit.rsi14 = ind.getRsi14();
        hit.ma5 = ind.getMa5();
        hit.ma20 = ind.getMa20();
        hit.ma60 = ind.getMa60();
        hit.isGoldenCross = ind.getIsGoldenCross();
        hit.isArrangedUp = ind.getIsArrangedUp();
        hit.volumeRatio = volRatio;
        hit.bollingerLower = bb != null ? bb.getLowerBand() : null;
        hit.bollingerUpper = bb != null ? bb.getUpperBand() : null;
        hit.matchedTags = matchedTags;
        hit.matchScore = score;
        return hit;
    }

    /**
     * 최근 거래량 / 직전 N일 평균 거래량.
     * rowsDesc: tradeDate DESC, 0번이 최신.
     */
    private BigDecimal computeVolumeRatio(List<StockPriceHistory> rowsDesc, int window) {
        if (rowsDesc.size() < window + 1) return null;
        BigDecimal latest = rowsDesc.get(0).getVolume();
        if (latest == null || latest.signum() <= 0) return null;

        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (int i = 1; i <= window && i < rowsDesc.size(); i++) {
            BigDecimal v = rowsDesc.get(i).getVolume();
            if (v != null && v.signum() > 0) {
                sum = sum.add(v);
                n++;
            }
        }
        if (n == 0) return null;
        BigDecimal avg = sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        if (avg.signum() == 0) return null;
        return latest.divide(avg, 2, RoundingMode.HALF_UP);
    }

    // ==================== 2. 상관관계 매트릭스 ====================

    /**
     * 종목 리스트에 대한 일봉 종가 기반 피어슨 상관계수 매트릭스.
     * - 입력: stockCodes (최대 30개)
     * - days: 비교 기간 (기본 60)
     * - 일변화율(returns) 기반으로 계산 (raw price보다 표준 관행)
     */
    public Map<String, Object> correlation(List<String> stockCodes, int days) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return Map.of("matrix", List.of(), "stocks", List.of(), "warnings", List.of("종목이 비어있음"));
        }
        // 중복 제거 + 상한
        List<String> codes = stockCodes.stream().distinct().limit(MAX_CORRELATION_STOCKS).collect(Collectors.toList());
        int window = days > 0 ? Math.min(days, 250) : CORRELATION_DAYS;

        LocalDate since = LocalDate.now().minusDays(window + 30L); // 휴일 여유
        List<StockPriceHistory> rows = priceHistoryRepository.findByStockCodesSince(codes, since);
        Map<String, List<StockPriceHistory>> byCode = rows.stream()
                .collect(Collectors.groupingBy(StockPriceHistory::getStockCode));

        // 공통 거래일 집합 — 모든 종목에 존재하는 날짜만 사용
        // tradeDate별 종가 매핑
        Map<String, Map<LocalDate, BigDecimal>> closeMap = new HashMap<>();
        Map<String, String> nameMap = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        for (String code : codes) {
            List<StockPriceHistory> hs = byCode.get(code);
            if (hs == null || hs.size() < 10) {
                warnings.add(code + ": 데이터 부족");
                continue;
            }
            Map<LocalDate, BigDecimal> m = new HashMap<>();
            for (StockPriceHistory h : hs) {
                if (h.getClosePrice() != null) m.put(h.getTradeDate(), h.getClosePrice());
            }
            closeMap.put(code, m);
            nameMap.put(code, hs.get(0).getStockName());
        }

        List<String> validCodes = codes.stream().filter(closeMap::containsKey).collect(Collectors.toList());
        if (validCodes.size() < 2) {
            return Map.of("matrix", List.of(), "stocks", List.of(), "warnings", warnings);
        }

        // 공통 날짜 — validCodes 모두에 존재하는 거래일
        List<LocalDate> commonDates = closeMap.get(validCodes.get(0)).keySet().stream()
                .filter(d -> validCodes.stream().allMatch(c -> closeMap.get(c).containsKey(d)))
                .sorted()
                .collect(Collectors.toList());
        if (commonDates.size() > window + 1) {
            commonDates = commonDates.subList(commonDates.size() - (window + 1), commonDates.size());
        }
        if (commonDates.size() < 10) {
            warnings.add("공통 거래일 부족: " + commonDates.size() + "일");
            return Map.of("matrix", List.of(), "stocks", List.of(), "warnings", warnings);
        }

        // 일별 수익률 (returns) — log return 대신 simple return 사용
        Map<String, double[]> returnsMap = new HashMap<>();
        for (String code : validCodes) {
            Map<LocalDate, BigDecimal> m = closeMap.get(code);
            double[] r = new double[commonDates.size() - 1];
            for (int i = 1; i < commonDates.size(); i++) {
                double prev = m.get(commonDates.get(i - 1)).doubleValue();
                double curr = m.get(commonDates.get(i)).doubleValue();
                r[i - 1] = prev == 0 ? 0 : (curr - prev) / prev;
            }
            returnsMap.put(code, r);
        }

        // 매트릭스 계산
        List<Map<String, Object>> stocks = new ArrayList<>();
        for (String code : validCodes) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("stockCode", code);
            s.put("stockName", nameMap.getOrDefault(code, code));
            stocks.add(s);
        }
        double[][] matrix = new double[validCodes.size()][validCodes.size()];
        for (int i = 0; i < validCodes.size(); i++) {
            for (int j = 0; j < validCodes.size(); j++) {
                if (i == j) {
                    matrix[i][j] = 1.0;
                } else if (j > i) {
                    matrix[i][j] = pearson(returnsMap.get(validCodes.get(i)), returnsMap.get(validCodes.get(j)));
                } else {
                    matrix[i][j] = matrix[j][i];
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stocks", stocks);
        result.put("matrix", matrix);
        result.put("daysUsed", commonDates.size() - 1);
        result.put("warnings", warnings);
        return result;
    }

    private double pearson(double[] x, double[] y) {
        if (x == null || y == null || x.length != y.length || x.length < 2) return 0;
        int n = x.length;
        double sumX = 0, sumY = 0;
        for (int i = 0; i < n; i++) { sumX += x[i]; sumY += y[i]; }
        double meanX = sumX / n, meanY = sumY / n;
        double num = 0, dx2 = 0, dy2 = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX, dy = y[i] - meanY;
            num += dx * dy;
            dx2 += dx * dx;
            dy2 += dy * dy;
        }
        double denom = Math.sqrt(dx2 * dy2);
        if (denom == 0) return 0;
        double r = num / denom;
        return Math.round(r * 1000.0) / 1000.0;
    }

    // ==================== DTOs ====================

    @lombok.Data
    public static class ScreenerFilter {
        private Double rsiBelow;          // RSI < value
        private Double rsiAbove;          // RSI > value
        private Boolean goldenCross;      // 5일선이 20일선 상향돌파
        private Boolean arrangedUp;       // 정배열 (5>20>60)
        private Boolean aboveMa20;        // 종가 > MA20
        private Boolean belowMa20;        // 종가 < MA20
        private Double volumeRatioMin;    // 거래량 / 20일평균 ≥ value
        private Boolean bollingerLowerTouch; // 종가 ≤ 볼린저 하단 * 1.02
        private Boolean bollingerSqueeze;    // 밴드폭 < 평균 * 0.7
        private Double changeRateMin;     // 등락률 ≥ value
        private Double changeRateMax;     // 등락률 ≤ value
    }

    @lombok.Data
    public static class ScreenerHit {
        private String stockCode;
        private String stockName;
        private LocalDate tradeDate;
        private BigDecimal closePrice;
        private BigDecimal changeRate;
        private BigDecimal rsi14;
        private BigDecimal ma5;
        private BigDecimal ma20;
        private BigDecimal ma60;
        private Boolean isGoldenCross;
        private Boolean isArrangedUp;
        private BigDecimal volumeRatio;
        private BigDecimal bollingerLower;
        private BigDecimal bollingerUpper;
        private List<String> matchedTags;
        private int matchScore;
    }
}
