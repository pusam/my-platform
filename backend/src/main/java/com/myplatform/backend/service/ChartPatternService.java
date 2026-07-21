package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.ChartPatternDto;
import com.myplatform.backend.dto.SupportResistanceDto;
import com.myplatform.backend.dto.VolumeProfileDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 차트 패턴 검출 서비스.
 *
 * 검출 가능 패턴:
 *  - 더블탑 / 더블바텀
 *  - 헤드앤숄더 / 역헤드앤숄더
 *  - 삼각수렴 (대칭/상승/하락)
 *
 * 주의:
 *  - 사용자 참고용 인디케이터로만 사용. 자동매매 신호로 사용 금지.
 *  - 검출은 본질적으로 노이즈가 있어 confidence 와 함께 표시.
 *  - 임계치들은 단순 휴리스틱. 정확도 vs 회수율 trade-off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChartPatternService {

    private final KoreaInvestmentService kisService;
    private final com.myplatform.backend.repository.StockPriceRepository stockPriceRepository;
    private final StockMasterService stockMasterService;
    /** self-injection — 같은 클래스 내 호출 시 AOP proxy 거치도록 (@Cacheable 동작 위함). */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private ChartPatternService self;

    /** 일봉 조회 일수 — 90일이면 대부분 패턴 검출 가능 (3개월 추세). */
    private static final int LOOKBACK_DAYS = 90;
    /** 피벗 검출 시 양옆 비교 윈도우 — 5일이면 너무 잡음, 7-10일이 적절. */
    private static final int PIVOT_WINDOW = 5;
    /** 가격 동등성 허용 오차 — 더블탑의 두 peak 가 ±3% 이내면 같은 가격으로 본다. */
    private static final BigDecimal PRICE_EQUAL_TOL = new BigDecimal("0.03");
    /** H&S 어깨/머리 비율 — head 는 어깨 대비 5% 이상 높아야 함. */
    private static final BigDecimal HEAD_PROMINENCE_MIN = new BigDecimal("0.05");

    private static final DateTimeFormatter KIS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 종목 차트 패턴 검출 (메인 엔트리). 30분 캐시.
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "chartPatterns",
            key = "#stockCode",
            condition = "#stockCode != null && !#stockCode.isEmpty()")
    public List<ChartPatternDto> detectPatterns(String stockCode) {
        List<Candle> candles = fetchCandles(stockCode);
        if (candles.size() < PIVOT_WINDOW * 4) {
            log.debug("[ChartPattern] {} - 일봉 부족: {} 건", stockCode, candles.size());
            return Collections.emptyList();
        }

        List<Pivot> highs = findPivotHighs(candles);
        List<Pivot> lows = findPivotLows(candles);

        List<ChartPatternDto> results = new ArrayList<>();
        addIfNotNull(results, detectDoubleTop(highs));
        addIfNotNull(results, detectDoubleBottom(lows));
        addIfNotNull(results, detectHeadAndShoulders(highs));
        addIfNotNull(results, detectInverseHeadAndShoulders(lows));
        addIfNotNull(results, detectTriangle(candles, highs, lows));
        addIfNotNull(results, detectCupAndHandle(candles, highs, lows));
        return results;
    }

    private static void addIfNotNull(List<ChartPatternDto> list, ChartPatternDto pattern) {
        if (pattern != null) list.add(pattern);
    }

    // ==================== 데이터 조회 / 파싱 ====================

    private List<Candle> fetchCandles(String stockCode) {
        try {
            JsonNode resp = kisService.getDailyPrices(stockCode, LOOKBACK_DAYS);
            if (resp == null || !resp.has("output2") || !resp.get("output2").isArray()) {
                return Collections.emptyList();
            }
            List<Candle> out = new ArrayList<>();
            for (JsonNode row : resp.get("output2")) {
                String dateStr = row.path("stck_bsop_date").asText("");
                if (dateStr.isEmpty()) continue;
                try {
                    Candle c = new Candle(
                            LocalDate.parse(dateStr, KIS_DATE),
                            parseDecimal(row.path("stck_oprc").asText()),
                            parseDecimal(row.path("stck_hgpr").asText()),
                            parseDecimal(row.path("stck_lwpr").asText()),
                            parseDecimal(row.path("stck_clpr").asText()),
                            parseLong(row.path("acml_vol").asText())
                    );
                    if (c.high.signum() > 0) out.add(c);
                } catch (Exception e) {
                    log.debug("[ChartPattern] 일봉 파싱 실패 {}: {}", dateStr, e.getMessage());
                }
            }
            // KIS 응답은 최신순 → 시간순(과거→현재)으로 정렬
            out.sort((a, b) -> a.date.compareTo(b.date));
            return out;
        } catch (Exception e) {
            log.warn("[ChartPattern] 일봉 조회 실패 {}: {}", stockCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null || s.isEmpty()) return BigDecimal.ZERO;
        return new BigDecimal(s.replace(",", ""));
    }

    private static long parseLong(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try { return Long.parseLong(s.replace(",", "")); } catch (Exception e) { return 0L; }
    }

    // ==================== 피벗 검출 ====================

    private List<Pivot> findPivotHighs(List<Candle> candles) {
        List<Pivot> pivots = new ArrayList<>();
        for (int i = PIVOT_WINDOW; i < candles.size() - PIVOT_WINDOW; i++) {
            BigDecimal h = candles.get(i).high;
            boolean isPivot = true;
            for (int j = 1; j <= PIVOT_WINDOW; j++) {
                if (candles.get(i - j).high.compareTo(h) >= 0
                        || candles.get(i + j).high.compareTo(h) >= 0) {
                    isPivot = false;
                    break;
                }
            }
            if (isPivot) pivots.add(new Pivot(i, candles.get(i).date, h, candles.get(i).volume));
        }
        return pivots;
    }

    private List<Pivot> findPivotLows(List<Candle> candles) {
        List<Pivot> pivots = new ArrayList<>();
        for (int i = PIVOT_WINDOW; i < candles.size() - PIVOT_WINDOW; i++) {
            BigDecimal l = candles.get(i).low;
            boolean isPivot = true;
            for (int j = 1; j <= PIVOT_WINDOW; j++) {
                if (candles.get(i - j).low.compareTo(l) <= 0
                        || candles.get(i + j).low.compareTo(l) <= 0) {
                    isPivot = false;
                    break;
                }
            }
            if (isPivot) pivots.add(new Pivot(i, candles.get(i).date, l, candles.get(i).volume));
        }
        return pivots;
    }

    // ==================== 더블탑 / 더블바텀 ====================

    private ChartPatternDto detectDoubleTop(List<Pivot> highs) {
        if (highs.size() < 2) return null;
        // 가장 최근 두 피벗 비교
        Pivot p1 = highs.get(highs.size() - 2);
        Pivot p2 = highs.get(highs.size() - 1);
        if (p2.index - p1.index < PIVOT_WINDOW * 2) return null; // 너무 가까우면 동일 피크

        BigDecimal diff = p1.price.subtract(p2.price).abs()
                .divide(p1.price, 4, RoundingMode.HALF_UP);
        if (diff.compareTo(PRICE_EQUAL_TOL) > 0) return null;

        // 신뢰도: 두 번째 거래량이 줄었으면 HIGH (전형적 weakening)
        String confidence = (p2.volume > 0 && p2.volume < p1.volume * 0.85) ? "HIGH" : "MEDIUM";

        return ChartPatternDto.builder()
                .type("DOUBLE_TOP")
                .label("더블탑 의심")
                .confidence(confidence)
                .signal("BEARISH")
                .description(String.format("최근 두 고점이 ±%.1f%% 이내로 형성. " +
                        "넥라인 하향 돌파 시 추세 전환 신호 가능.",
                        diff.multiply(BigDecimal.valueOf(100)).doubleValue()))
                .startDate(p1.date)
                .endDate(p2.date)
                .referencePrice(p1.price.add(p2.price).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP))
                .keyPoints(List.of(
                        new ChartPatternDto.KeyPoint(p1.date, p1.price, "PEAK_1"),
                        new ChartPatternDto.KeyPoint(p2.date, p2.price, "PEAK_2")))
                .build();
    }

    private ChartPatternDto detectDoubleBottom(List<Pivot> lows) {
        if (lows.size() < 2) return null;
        Pivot p1 = lows.get(lows.size() - 2);
        Pivot p2 = lows.get(lows.size() - 1);
        if (p2.index - p1.index < PIVOT_WINDOW * 2) return null;

        BigDecimal diff = p1.price.subtract(p2.price).abs()
                .divide(p1.price, 4, RoundingMode.HALF_UP);
        if (diff.compareTo(PRICE_EQUAL_TOL) > 0) return null;

        String confidence = (p2.volume > 0 && p2.volume > p1.volume * 1.15) ? "HIGH" : "MEDIUM";

        return ChartPatternDto.builder()
                .type("DOUBLE_BOTTOM")
                .label("더블바텀 의심")
                .confidence(confidence)
                .signal("BULLISH")
                .description(String.format("최근 두 저점이 ±%.1f%% 이내로 형성. " +
                        "넥라인 상향 돌파 시 추세 전환 신호 가능.",
                        diff.multiply(BigDecimal.valueOf(100)).doubleValue()))
                .startDate(p1.date)
                .endDate(p2.date)
                .referencePrice(p1.price.add(p2.price).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP))
                .keyPoints(List.of(
                        new ChartPatternDto.KeyPoint(p1.date, p1.price, "TROUGH_1"),
                        new ChartPatternDto.KeyPoint(p2.date, p2.price, "TROUGH_2")))
                .build();
    }

    // ==================== 헤드앤숄더 / 역H&S ====================

    private ChartPatternDto detectHeadAndShoulders(List<Pivot> highs) {
        if (highs.size() < 3) return null;
        Pivot ls = highs.get(highs.size() - 3);
        Pivot hd = highs.get(highs.size() - 2);
        Pivot rs = highs.get(highs.size() - 1);

        // head 가 두 어깨보다 충분히 높아야
        BigDecimal headOverLeft = hd.price.subtract(ls.price)
                .divide(ls.price, 4, RoundingMode.HALF_UP);
        BigDecimal headOverRight = hd.price.subtract(rs.price)
                .divide(rs.price, 4, RoundingMode.HALF_UP);
        if (headOverLeft.compareTo(HEAD_PROMINENCE_MIN) < 0
                || headOverRight.compareTo(HEAD_PROMINENCE_MIN) < 0) return null;

        // 두 어깨 비슷한 높이 (±5%)
        BigDecimal shoulderDiff = ls.price.subtract(rs.price).abs()
                .divide(ls.price, 4, RoundingMode.HALF_UP);
        if (shoulderDiff.compareTo(new BigDecimal("0.05")) > 0) return null;

        String confidence = shoulderDiff.compareTo(new BigDecimal("0.02")) <= 0 ? "HIGH" : "MEDIUM";

        return ChartPatternDto.builder()
                .type("HEAD_AND_SHOULDERS")
                .label("헤드앤숄더")
                .confidence(confidence)
                .signal("BEARISH")
                .description("좌 어깨 - 머리 - 우 어깨 형태 형성. " +
                        "넥라인 하향 돌파 시 강한 하락 전환 신호.")
                .startDate(ls.date)
                .endDate(rs.date)
                .referencePrice(ls.price.add(rs.price).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP))
                .keyPoints(List.of(
                        new ChartPatternDto.KeyPoint(ls.date, ls.price, "LEFT_SHOULDER"),
                        new ChartPatternDto.KeyPoint(hd.date, hd.price, "HEAD"),
                        new ChartPatternDto.KeyPoint(rs.date, rs.price, "RIGHT_SHOULDER")))
                .build();
    }

    private ChartPatternDto detectInverseHeadAndShoulders(List<Pivot> lows) {
        if (lows.size() < 3) return null;
        Pivot ls = lows.get(lows.size() - 3);
        Pivot hd = lows.get(lows.size() - 2);
        Pivot rs = lows.get(lows.size() - 1);

        // 역방향 — head 가 두 어깨보다 충분히 낮아야
        BigDecimal leftOverHead = ls.price.subtract(hd.price)
                .divide(hd.price, 4, RoundingMode.HALF_UP);
        BigDecimal rightOverHead = rs.price.subtract(hd.price)
                .divide(hd.price, 4, RoundingMode.HALF_UP);
        if (leftOverHead.compareTo(HEAD_PROMINENCE_MIN) < 0
                || rightOverHead.compareTo(HEAD_PROMINENCE_MIN) < 0) return null;

        BigDecimal shoulderDiff = ls.price.subtract(rs.price).abs()
                .divide(ls.price, 4, RoundingMode.HALF_UP);
        if (shoulderDiff.compareTo(new BigDecimal("0.05")) > 0) return null;

        String confidence = shoulderDiff.compareTo(new BigDecimal("0.02")) <= 0 ? "HIGH" : "MEDIUM";

        return ChartPatternDto.builder()
                .type("INVERSE_HEAD_AND_SHOULDERS")
                .label("역헤드앤숄더")
                .confidence(confidence)
                .signal("BULLISH")
                .description("좌 어깨 - 머리 - 우 어깨 (모두 저점) 형태 형성. " +
                        "넥라인 상향 돌파 시 강한 상승 전환 신호.")
                .startDate(ls.date)
                .endDate(rs.date)
                .referencePrice(ls.price.add(rs.price).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP))
                .keyPoints(List.of(
                        new ChartPatternDto.KeyPoint(ls.date, ls.price, "LEFT_SHOULDER"),
                        new ChartPatternDto.KeyPoint(hd.date, hd.price, "HEAD"),
                        new ChartPatternDto.KeyPoint(rs.date, rs.price, "RIGHT_SHOULDER")))
                .build();
    }

    // ==================== 삼각수렴 ====================

    private ChartPatternDto detectTriangle(List<Candle> candles, List<Pivot> highs, List<Pivot> lows) {
        // 최근 절반 구간만 평가 (오래된 추세는 무관)
        int from = candles.size() / 2;
        List<Pivot> recentHighs = highs.stream().filter(p -> p.index >= from).toList();
        List<Pivot> recentLows = lows.stream().filter(p -> p.index >= from).toList();
        if (recentHighs.size() < 2 || recentLows.size() < 2) return null;

        // 단순 선형 회귀로 high/low 트렌드 기울기 추정
        double highSlope = linearSlope(recentHighs);
        double lowSlope = linearSlope(recentLows);

        // 최근 high-low 폭 / 과거 high-low 폭 비교 — 좁아져야 수렴
        BigDecimal recentRange = avgRange(candles, candles.size() - 10, candles.size());
        BigDecimal earlierRange = avgRange(candles, from, from + 10);
        if (earlierRange.signum() == 0) return null;
        BigDecimal narrowing = recentRange.divide(earlierRange, 4, RoundingMode.HALF_UP);
        if (narrowing.compareTo(new BigDecimal("0.7")) > 0) return null; // 30% 이상 좁아져야

        String type;
        String label;
        String signal;
        String desc;

        // 평탄 기준 — abs slope < 평균가의 0.001 (대략 일당 0.1%)
        BigDecimal avgPrice = recentHighs.get(0).price;
        double flatTol = avgPrice.doubleValue() * 0.001;

        boolean highFlat = Math.abs(highSlope) < flatTol;
        boolean lowFlat = Math.abs(lowSlope) < flatTol;

        if (highFlat && lowSlope > flatTol) {
            type = "TRIANGLE_ASCENDING"; label = "상승 삼각형"; signal = "BULLISH";
            desc = "고점 평탄 + 저점 상승 — 매수 압력 우세. 저항 돌파 시 상승 가능.";
        } else if (lowFlat && highSlope < -flatTol) {
            type = "TRIANGLE_DESCENDING"; label = "하락 삼각형"; signal = "BEARISH";
            desc = "저점 평탄 + 고점 하락 — 매도 압력 우세. 지지 이탈 시 하락 가능.";
        } else if (highSlope < -flatTol && lowSlope > flatTol) {
            type = "TRIANGLE_SYMMETRIC"; label = "대칭 삼각형"; signal = "NEUTRAL";
            desc = "고점/저점 모두 수렴 — 추세 결정 임박. 어느 쪽 돌파인지 관찰 필요.";
        } else {
            return null;
        }

        return ChartPatternDto.builder()
                .type(type)
                .label(label)
                .confidence("MEDIUM")
                .signal(signal)
                .description(desc)
                .startDate(candles.get(from).date)
                .endDate(candles.get(candles.size() - 1).date)
                .build();
    }

    /** 단순 선형 회귀 기울기 (least-squares). x=index, y=price. */
    private static double linearSlope(List<Pivot> pivots) {
        int n = pivots.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (Pivot p : pivots) {
            double x = p.index;
            double y = p.price.doubleValue();
            sumX += x; sumY += y; sumXY += x * y; sumX2 += x * x;
        }
        double denom = n * sumX2 - sumX * sumX;
        if (Math.abs(denom) < 1e-9) return 0.0;
        return (n * sumXY - sumX * sumY) / denom;
    }

    private static BigDecimal avgRange(List<Candle> candles, int from, int to) {
        int n = 0;
        BigDecimal sum = BigDecimal.ZERO;
        int end = Math.min(to, candles.size());
        int start = Math.max(0, from);
        for (int i = start; i < end; i++) {
            Candle c = candles.get(i);
            sum = sum.add(c.high.subtract(c.low));
            n++;
        }
        return n == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }

    // ==================== 컵앤핸들 ====================

    /** 컵 최소 깊이 — 좌측 림 대비 5% 이상 하락해야 컵으로 인정. */
    private static final BigDecimal CUP_MIN_DEPTH = new BigDecimal("0.05");
    /** 컵 최대 깊이 — 너무 깊으면 (30%+) 추세전환 아닌 폭락 후 회복 — 컵 아님. */
    private static final BigDecimal CUP_MAX_DEPTH = new BigDecimal("0.30");
    /** 양쪽 림 가격 차이 허용 — ±5% 이내. */
    private static final BigDecimal CUP_RIM_TOL = new BigDecimal("0.05");
    /** 핸들 최대 깊이 — 컵 깊이의 50% 미만 (얕은 pullback). */
    private static final BigDecimal HANDLE_MAX_DEPTH_RATIO = new BigDecimal("0.5");

    /**
     * 컵앤핸들 검출 — U자 컵 + 우측 짧은 핸들(pullback).
     * 검출 노이즈 큰 패턴이라 임계치 보수적. 사용자 참고용.
     *
     * 단순화된 로직 (정확한 컵 곡률 회귀 X):
     *  1) 최근 60일 내에 두 개의 비슷한 high pivot (±5%) — 좌/우 림 후보
     *  2) 두 림 사이에 깊은 low pivot (림 대비 5~30% 하락) — 컵 바닥
     *  3) 우측 림 이후 작은 pullback (컵 깊이의 50% 미만) — 핸들
     */
    private ChartPatternDto detectCupAndHandle(List<Candle> candles, List<Pivot> highs, List<Pivot> lows) {
        if (highs.size() < 2 || lows.isEmpty() || candles.size() < 30) return null;

        // 최근 N개 후보만 — 60일 안 또는 candles 절반 안의 high pivots
        int from = Math.max(0, candles.size() - 60);
        List<Pivot> recentHighs = highs.stream().filter(h -> h.index >= from).toList();
        if (recentHighs.size() < 2) return null;

        // 좌/우 림: 가장 최근 두 개의 비슷한 가격 high pivots
        Pivot rightRim = null, leftRim = null;
        for (int i = recentHighs.size() - 1; i >= 0; i--) {
            Pivot rh = recentHighs.get(i);
            for (int j = i - 1; j >= 0; j--) {
                Pivot lh = recentHighs.get(j);
                BigDecimal rimDiff = rh.price.subtract(lh.price).abs()
                        .divide(lh.price, 4, RoundingMode.HALF_UP);
                if (rimDiff.compareTo(CUP_RIM_TOL) > 0) continue;
                // 두 림 사이 거리 — 컵 형성에 충분한 시간 (10일+)
                if (rh.index - lh.index < 10) continue;
                rightRim = rh;
                leftRim = lh;
                break;
            }
            if (rightRim != null) break;
        }
        if (rightRim == null) return null;

        // 컵 바닥: 두 림 사이의 가장 낮은 low pivot
        final int leftIdx = leftRim.index;
        final int rightIdx = rightRim.index;
        Pivot bottom = lows.stream()
                .filter(l -> l.index > leftIdx && l.index < rightIdx)
                .min(Comparator.comparing(l -> l.price))
                .orElse(null);
        if (bottom == null) return null;

        BigDecimal cupDepth = leftRim.price.subtract(bottom.price)
                .divide(leftRim.price, 4, RoundingMode.HALF_UP);
        if (cupDepth.compareTo(CUP_MIN_DEPTH) < 0 || cupDepth.compareTo(CUP_MAX_DEPTH) > 0) return null;

        // 핸들: 우측 림 이후 가격이 다시 하락했는데 (그리고 회복 진행 중)
        // 가장 최근 캔들까지 최저점을 핸들 바닥으로
        BigDecimal handleLow = leftRim.price; // initial high
        for (int i = rightIdx + 1; i < candles.size(); i++) {
            BigDecimal l = candles.get(i).low;
            if (l.compareTo(handleLow) < 0) handleLow = l;
        }
        if (rightIdx + 1 >= candles.size()) return null; // 핸들 형성 안 됨
        BigDecimal handleDepth = rightRim.price.subtract(handleLow)
                .divide(rightRim.price, 4, RoundingMode.HALF_UP);
        // 핸들이 너무 깊으면 컵 아님
        if (handleDepth.compareTo(cupDepth.multiply(HANDLE_MAX_DEPTH_RATIO)) > 0) return null;
        // 핸들이 너무 얕으면 (거의 0) — 아직 형성 중. skip
        if (handleDepth.compareTo(new BigDecimal("0.01")) < 0) return null;

        // 신뢰도: 좌우 림 비슷할수록 + 컵 깊이 적당할수록 (10~20%) HIGH
        BigDecimal rimDiff = leftRim.price.subtract(rightRim.price).abs()
                .divide(leftRim.price, 4, RoundingMode.HALF_UP);
        boolean rimMatch = rimDiff.compareTo(new BigDecimal("0.02")) <= 0;
        boolean depthIdeal = cupDepth.compareTo(new BigDecimal("0.10")) >= 0
                && cupDepth.compareTo(new BigDecimal("0.20")) <= 0;
        String confidence = (rimMatch && depthIdeal) ? "HIGH" : "MEDIUM";

        return ChartPatternDto.builder()
                .type("CUP_AND_HANDLE")
                .label("컵앤핸들")
                .confidence(confidence)
                .signal("BULLISH")
                .description(String.format("컵 깊이 %.1f%% + 핸들 깊이 %.1f%% 형태. " +
                        "우측 림(저항) 상향 돌파 시 강한 상승 신호 가능.",
                        cupDepth.multiply(BigDecimal.valueOf(100)).doubleValue(),
                        handleDepth.multiply(BigDecimal.valueOf(100)).doubleValue()))
                .startDate(leftRim.date)
                .endDate(candles.get(candles.size() - 1).date)
                .referencePrice(leftRim.price.add(rightRim.price)
                        .divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP))
                .keyPoints(List.of(
                        new ChartPatternDto.KeyPoint(leftRim.date, leftRim.price, "LEFT_RIM"),
                        new ChartPatternDto.KeyPoint(bottom.date, bottom.price, "CUP_BOTTOM"),
                        new ChartPatternDto.KeyPoint(rightRim.date, rightRim.price, "RIGHT_RIM")))
                .build();
    }

    // ==================== 다종목 일괄 스캔 ====================

    /**
     * 여러 종목 일괄 스캔. 각 종목별 detectPatterns 캐시(30분)를 활용 — 첫 호출만 KIS 발생.
     * 입력 종목 수 제한: 50개 (KIS rate limit 보호).
     */
    public List<ScanResult> scanForPatterns(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) return Collections.emptyList();
        List<String> capped = stockCodes.stream().distinct().limit(50).toList();
        List<ScanResult> results = new ArrayList<>();
        for (String code : capped) {
            try {
                // self proxy 경유 — @Cacheable hit 시 KIS 호출 skip
                List<ChartPatternDto> patterns = self.detectPatterns(code);
                if (patterns.isEmpty()) continue;
                // 가장 강한 신호 1개 — confidence HIGH > MEDIUM > LOW, BULLISH 우선
                ChartPatternDto top = patterns.stream()
                        .sorted((a, b) -> Integer.compare(rank(b), rank(a)))
                        .findFirst().orElse(null);
                if (top == null) continue;
                String name = stockMasterService.getNameOrDefault(code, code);
                results.add(new ScanResult(code, name, top, patterns.size()));
            } catch (Exception e) {
                log.debug("[ChartPattern] scan {} 실패: {}", code, e.getMessage());
            }
        }
        return results;
    }

    /**
     * 거래대금/거래량 상위 종목 fallback universe — watchlist 비어있을 때 사용.
     * stock_price 의 MAX(volume) 기준 상위 N개 → scanForPatterns 위임.
     */
    public List<ScanResult> scanTopVolumeStocks(int limit) {
        int safeLimit = Math.min(Math.max(limit, 5), 50);
        List<String> codes = stockPriceRepository.findTopVolumeStockCodes(
                java.time.LocalDateTime.now().minusDays(7),   // 최근 7일 시세만 — 전 이력 풀스캔/휴면종목 방지
                org.springframework.data.domain.PageRequest.of(0, safeLimit));
        return scanForPatterns(codes);
    }

    /** confidence + signal 가중치 — BULLISH HIGH 가 가장 높음. */
    private static int rank(ChartPatternDto p) {
        int conf = "HIGH".equals(p.getConfidence()) ? 3 :
                   ("MEDIUM".equals(p.getConfidence()) ? 2 : 1);
        int sig = "BULLISH".equals(p.getSignal()) ? 2 :
                  ("BEARISH".equals(p.getSignal()) ? 1 : 0);
        return conf * 10 + sig;
    }

    /** 다종목 스캔 결과 — 종목당 가장 강한 패턴 1개. */
    public record ScanResult(String stockCode, String stockName,
                             ChartPatternDto topPattern, int totalPatternCount) {}

    // ==================== 지지/저항 검출 ====================

    /** 같은 가격대로 묶을 허용 오차 — ±2% 이내면 동일 레벨로 본다. */
    private static final BigDecimal LEVEL_CLUSTER_TOL = new BigDecimal("0.02");
    /** 노출할 위/아래 레벨 최대 개수. */
    private static final int LEVEL_MAX_COUNT = 5;

    /**
     * 지지/저항 레벨 검출 (메인 엔트리). 30분 캐시.
     * 일봉 90일 피벗을 가격대로 클러스터링 → 같은 레벨 여러 번 터치한 곳이 강한 레벨.
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "chartPatterns",
            key = "'sr:' + #stockCode",
            condition = "#stockCode != null && !#stockCode.isEmpty()")
    public SupportResistanceDto detectSupportResistance(String stockCode) {
        List<Candle> candles = fetchCandles(stockCode);
        if (candles.size() < PIVOT_WINDOW * 4) {
            return SupportResistanceDto.builder()
                    .resistance(Collections.emptyList())
                    .support(Collections.emptyList())
                    .build();
        }
        BigDecimal currentPrice = candles.get(candles.size() - 1).close;

        // 모든 피벗 (high + low) 모음 — 지지/저항 모두 후보
        List<Pivot> allPivots = new ArrayList<>();
        allPivots.addAll(findPivotHighs(candles));
        allPivots.addAll(findPivotLows(candles));

        // 가격대 클러스터링 — sort + ±2% 이내면 같은 클러스터
        allPivots.sort(Comparator.comparing(p -> p.price));
        List<Cluster> clusters = clusterPivots(allPivots);

        // 현재가 기준 위/아래 분리 + Level 변환
        List<SupportResistanceDto.Level> resistance = new ArrayList<>();
        List<SupportResistanceDto.Level> support = new ArrayList<>();
        for (Cluster c : clusters) {
            SupportResistanceDto.Level level = toLevel(c, currentPrice);
            if (c.avgPrice.compareTo(currentPrice) > 0) {
                resistance.add(level);
            } else {
                support.add(level);
            }
        }

        // 정렬: 가까운 순 + top N
        resistance.sort(Comparator.comparing(l -> l.getPrice()));
        support.sort(Comparator.comparing((SupportResistanceDto.Level l) -> l.getPrice()).reversed());

        return SupportResistanceDto.builder()
                .currentPrice(currentPrice)
                .resistance(resistance.stream().limit(LEVEL_MAX_COUNT).toList())
                .support(support.stream().limit(LEVEL_MAX_COUNT).toList())
                .build();
    }

    private List<Cluster> clusterPivots(List<Pivot> sortedByPrice) {
        List<Cluster> clusters = new ArrayList<>();
        if (sortedByPrice.isEmpty()) return clusters;

        Cluster current = new Cluster(sortedByPrice.get(0));
        for (int i = 1; i < sortedByPrice.size(); i++) {
            Pivot p = sortedByPrice.get(i);
            BigDecimal diff = p.price.subtract(current.avgPrice).abs()
                    .divide(current.avgPrice, 4, RoundingMode.HALF_UP);
            if (diff.compareTo(LEVEL_CLUSTER_TOL) <= 0) {
                current.add(p);
            } else {
                clusters.add(current);
                current = new Cluster(p);
            }
        }
        clusters.add(current);
        return clusters;
    }

    private SupportResistanceDto.Level toLevel(Cluster c, BigDecimal currentPrice) {
        String strength = c.touches >= 3 ? "HIGH" : (c.touches >= 2 ? "MEDIUM" : "LOW");
        BigDecimal distancePct = c.avgPrice.subtract(currentPrice)
                .divide(currentPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        return SupportResistanceDto.Level.builder()
                .price(c.avgPrice.setScale(0, RoundingMode.HALF_UP))
                .touches(c.touches)
                .lastTouchDate(c.lastTouchDate)
                .strength(strength)
                .distancePct(distancePct)
                .build();
    }

    // ==================== Volume Profile ====================

    /** Volume Profile 가격 bin 개수 — 30개면 차트 보기 적당. */
    private static final int VP_BIN_COUNT = 30;
    /** Value Area 누적 비율 — 표준 70%. */
    private static final double VP_VALUE_AREA_PCT = 0.70;

    /**
     * 가격대별 누적 거래량 (Volume Profile) 계산. 30분 캐시.
     * - bin: 90일 일봉 가격 범위를 30등분
     * - 각 일봉: typical_price = (high+low+close)/3 의 bin 에 volume 100% 할당
     * - POC: 최다 거래량 bin 의 중간가
     * - VAH/VAL: POC 부터 양옆 bin 누적 70% 도달 영역의 상/하한
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "chartPatterns",
            key = "'vp:' + #stockCode",
            condition = "#stockCode != null && !#stockCode.isEmpty()")
    public VolumeProfileDto computeVolumeProfile(String stockCode) {
        List<Candle> candles = fetchCandles(stockCode);
        if (candles.size() < PIVOT_WINDOW * 4) {
            return VolumeProfileDto.builder()
                    .bins(Collections.emptyList())
                    .totalVolume(0L)
                    .periodDays(candles.size())
                    .build();
        }

        // 가격 범위
        BigDecimal pMin = candles.stream().map(c -> c.low).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal pMax = candles.stream().map(c -> c.high).max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        BigDecimal range = pMax.subtract(pMin);
        if (range.signum() <= 0) {
            return VolumeProfileDto.builder().bins(Collections.emptyList()).totalVolume(0L).build();
        }
        BigDecimal binWidth = range.divide(BigDecimal.valueOf(VP_BIN_COUNT), 4, RoundingMode.HALF_UP);

        // bin 초기화
        long[] binVolumes = new long[VP_BIN_COUNT];
        long total = 0;
        for (Candle c : candles) {
            // typical_price = (high+low+close)/3
            BigDecimal typical = c.high.add(c.low).add(c.close)
                    .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            int idx = typical.subtract(pMin).divide(binWidth, 4, RoundingMode.HALF_DOWN).intValue();
            if (idx < 0) idx = 0;
            if (idx >= VP_BIN_COUNT) idx = VP_BIN_COUNT - 1;
            binVolumes[idx] += c.volume;
            total += c.volume;
        }

        // bins DTO 생성
        List<VolumeProfileDto.Bin> bins = new ArrayList<>();
        int pocIdx = 0;
        long pocVol = 0;
        for (int i = 0; i < VP_BIN_COUNT; i++) {
            BigDecimal bLow = pMin.add(binWidth.multiply(BigDecimal.valueOf(i)));
            BigDecimal bHigh = bLow.add(binWidth);
            double pct = total > 0 ? (binVolumes[i] * 100.0 / total) : 0.0;
            bins.add(new VolumeProfileDto.Bin(
                    bLow.setScale(0, RoundingMode.HALF_UP),
                    bHigh.setScale(0, RoundingMode.HALF_UP),
                    binVolumes[i],
                    Math.round(pct * 100) / 100.0));
            if (binVolumes[i] > pocVol) {
                pocVol = binVolumes[i];
                pocIdx = i;
            }
        }
        BigDecimal poc = pMin.add(binWidth.multiply(BigDecimal.valueOf(pocIdx))).add(binWidth.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP));

        // Value Area: POC 에서 양옆으로 확장하며 누적 70% 도달
        long target = (long) (total * VP_VALUE_AREA_PCT);
        long cumulative = pocVol;
        int low = pocIdx, high = pocIdx;
        while (cumulative < target && (low > 0 || high < VP_BIN_COUNT - 1)) {
            long leftVol = low > 0 ? binVolumes[low - 1] : -1;
            long rightVol = high < VP_BIN_COUNT - 1 ? binVolumes[high + 1] : -1;
            // 양옆 중 거래량 큰 쪽으로 확장
            if (rightVol >= leftVol) {
                high++;
                cumulative += binVolumes[high];
            } else {
                low--;
                cumulative += binVolumes[low];
            }
        }
        BigDecimal val = pMin.add(binWidth.multiply(BigDecimal.valueOf(low))).setScale(0, RoundingMode.HALF_UP);
        BigDecimal vah = pMin.add(binWidth.multiply(BigDecimal.valueOf(high + 1))).setScale(0, RoundingMode.HALF_UP);

        return VolumeProfileDto.builder()
                .priceMin(pMin.setScale(0, RoundingMode.HALF_UP))
                .priceMax(pMax.setScale(0, RoundingMode.HALF_UP))
                .bins(bins)
                .poc(poc.setScale(0, RoundingMode.HALF_UP))
                .vah(vah)
                .val(val)
                .totalVolume(total)
                .periodDays(candles.size())
                .build();
    }

    // ==================== 내부 데이터 클래스 ====================

    private record Candle(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
                          BigDecimal close, long volume) {}

    private record Pivot(int index, LocalDate date, BigDecimal price, long volume) {}

    /** 같은 가격대 피벗 클러스터 — 평균가, 터치횟수, 최신터치일 누적. */
    private static class Cluster {
        BigDecimal sumPrice;
        BigDecimal avgPrice;
        int touches;
        LocalDate lastTouchDate;

        Cluster(Pivot first) {
            this.sumPrice = first.price;
            this.avgPrice = first.price;
            this.touches = 1;
            this.lastTouchDate = first.date;
        }

        void add(Pivot p) {
            this.sumPrice = this.sumPrice.add(p.price);
            this.touches++;
            this.avgPrice = this.sumPrice.divide(BigDecimal.valueOf(touches), 2, RoundingMode.HALF_UP);
            if (p.date.isAfter(this.lastTouchDate)) {
                this.lastTouchDate = p.date;
            }
        }
    }
}
