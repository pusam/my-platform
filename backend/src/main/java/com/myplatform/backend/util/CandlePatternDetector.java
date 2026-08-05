package com.myplatform.backend.util;

import com.myplatform.backend.entity.StockPriceHistory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 일봉 캔들 패턴 감지 — <b>순수 계산 유틸, shadow 전용</b>(산식/봇/게이트 미연결 — §4c 검증 전 합산 금지).
 *
 * <p>소스는 {@link StockPriceHistory} 일봉(시세 단일 경로에서 적재된 히스토리 — 새 가격 조회 경로 아님).
 * 분봉 적용 금지 — 임계값(ATR 배수·RVOL)이 전부 일봉 기준으로 정의됐다.
 *
 * <p><b>패턴 1 BULL_CONTINUATION</b>(상승지속): 장대양봉(몸통 ≥ ATR14×1.0) → 도지 1~3봉(몸통/범위 ≤ 0.3)
 * → 저거래량 음봉(RVOL &lt; 0.7, 몸통 ≥ ATR×0.8) → 직후 장대양봉 출현 시 확정.
 *
 * <p><b>패턴 2 CONVERGENCE_BOX</b>(수렴돌파+박스): 고점 하락·저점 상승/수평 수렴 15봉 이상
 * (무효: 수렴 평균 RVOL &lt; 0.5 / 직전 20봉 저점 대비 +40% 급등) → 상단 추세선 돌파 →
 * 돌파 후 3~7봉 박스(고저 범위 ≤ ATR×1.5, 저점이 돌파선 위 유지) 형성 시 확정. 돌파만으로는 시그널 아님.
 *
 * <p><b>위치 필터(공통, 미충족 시 감지 자체를 버림)</b>: 확정 봉 종가가 직전 20봉 고점 상향 돌파 상태
 * + 20일선 위 + 20일선 기울기(5봉 전 대비) 비음수.
 *
 * <p><b>§4c / fail-closed</b>: ATR·RVOL·MA 산출에 필요한 데이터가 결측이면 그 창의 감지를 <b>버린다</b>
 * (결측을 그럴듯한 값으로 채워 감지하지 않는다). shadow 기록이라 놓침은 안전, 오탐이 비용.
 *
 * <p>임계값은 전부 <b>잠정</b>(백테스트 검증 전) — 감지 시 실측값과 함께 params 스냅샷으로 남겨
 * 임계 튜닝 후에도 과거 행이 재현 가능하게 한다.
 */
public final class CandlePatternDetector {

    public enum PatternType { BULL_CONTINUATION, CONVERGENCE_BOX }

    /**
     * 기각 사유 — 후보(종목×봉×패턴) 하나당 정확히 1회 집계(감지되면 미집계). 순서 = 검사 순서.
     * BOX 는 (boxLen×convLen) 조합 탐색이라 <b>가장 깊이 도달한 단계의 실패</b>를 그 후보의 사유로 기록
     * — "어느 조건이 100% 기각인지"(임계 사망 진단)를 보려면 조합별이 아니라 후보별 최심 단계가 맞다.
     */
    public enum RejectReason {
        BULL_DATA_MISSING,        // 확정 봉 OHLC 결측(§4c fail-closed)
        BULL_MA_FILTER,           // 위치필터: 종가 < MA20 또는 기울기 음수 (판정불가 결측 포함)
        BULL_NO_HIGH_BREAKOUT,    // 위치필터: 직전 20봉 고점 미돌파
        BULL_ATR_MISSING,         // ATR14 미산출
        BULL_CONF_NOT_LONG_BULL,  // 확정 봉이 장대양봉 미달 (LONG_BODY_ATR_MULT)
        BULL_PULLBACK_SHAPE,      // 직전 봉이 음봉 아님/몸통 미달 (PULLBACK_BODY_ATR_MULT)
        BULL_PULLBACK_RVOL,       // 눌림 RVOL ≥ 상한 또는 결측 (PULLBACK_RVOL_MAX)
        BULL_DOJI_LEADER,         // 도지 1~3봉 또는 리더 장대양봉 미충족
        BOX_DATA_MISSING,
        BOX_MA_FILTER,
        BOX_ATR_MISSING,
        BOX_NO_CONV_WINDOW,       // 검사 가능한 수렴 창 자체가 없음(히스토리 짧음/결측)
        BOX_NO_CONVERGENCE,       // 고점하락+저점상승/수평 수렴 형태 없음
        BOX_CONV_LOW_RVOL,        // 수렴 구간 평균 RVOL < 하한 (CONV_AVG_RVOL_MIN)
        BOX_SURGE_INVALID,        // 직전 20봉 저점 대비 급등 무효 (CONV_SURGE_INVALID_PCT)
        BOX_NO_BREAKOUT,          // 상단 추세선 돌파 봉 없음
        BOX_NO_HIGH_BREAKOUT,     // 위치필터: 돌파 상태(돌파 봉 앞 20봉 고점 위) 미유지
        BOX_NOT_FORMED            // 박스 범위 초과/돌파선 아래 이탈 (BOX_RANGE_ATR_MULT)
    }

    /** 배치 1회분 기각 집계 — 사유별 카운트 + 검사 후보 수. 값 0 도 노출("기각 0"과 "도달 0" 구분용). */
    public static final class RejectionStats {
        private final java.util.EnumMap<RejectReason, Integer> counts =
                new java.util.EnumMap<>(RejectReason.class);
        private int candidates;

        void count(RejectReason reason) { counts.merge(reason, 1, Integer::sum); }

        void addCandidate() { candidates++; }

        /** 검사한 후보 수 = 종목 × 최근 봉 × 2패턴. */
        public int candidates() { return candidates; }

        public Map<RejectReason, Integer> asMap() {
            Map<RejectReason, Integer> out = new LinkedHashMap<>();
            for (RejectReason r : RejectReason.values()) out.put(r, counts.getOrDefault(r, 0));
            return out;
        }

        /** 로그 한 줄용 — 전 사유 고정 순서, 0 포함. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (RejectReason r : RejectReason.values()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(r.name()).append('=').append(counts.getOrDefault(r, 0));
            }
            return sb.toString();
        }
    }

    // ---- 공통 위치 필터 ----
    /** 직전 N봉 고점 돌파 요구 — "최근 20봉 고점 상향 돌파 상태에서만 유효". */
    static final int POSITION_HIGH_LOOKBACK = 20;
    /** 이동평균 기간(20일선). */
    static final int MA_PERIOD = 20;
    /** 20일선 기울기 판정 — 현재 MA20 vs {@value}봉 전 MA20. 음수면 하락추세로 무효. */
    static final int MA_SLOPE_LOOKBACK = 5;

    // ---- 패턴 1 (BULL_CONTINUATION) ----
    /** 장대양봉 몸통 최소 = ATR14 × 이 배수. */
    static final double LONG_BODY_ATR_MULT = 1.0;
    /** 도지 판정 — 몸통/전체범위 상한. */
    static final double DOJI_BODY_RANGE_MAX = 0.3;
    /** 도지 봉 수 허용 범위. */
    static final int DOJI_MIN = 1, DOJI_MAX = 3;
    /** 눌림 음봉 RVOL 상한(저거래량). */
    static final double PULLBACK_RVOL_MAX = 0.7;
    /** 눌림 음봉 몸통 최소 = ATR × 이 배수 — 요청 스펙 그대로(≥ 0.8). */
    static final double PULLBACK_BODY_ATR_MULT = 0.8;

    // ---- 패턴 2 (CONVERGENCE_BOX) ----
    /** 수렴 구간 최소/최대 봉 수 (최대는 탐색 상한 — 스펙은 "최소 15봉"만 규정). */
    static final int CONV_MIN_BARS = 15, CONV_MAX_BARS = 40;
    /** 수렴 구간 평균 RVOL 하한 — 미만이면 무효(죽은 수렴). */
    static final double CONV_AVG_RVOL_MIN = 0.5;
    /** 돌파 직전 20봉 저점 대비 급등률 상한 — 이상이면 무효(이미 과열). */
    static final double CONV_SURGE_INVALID_PCT = 40.0;
    /** 저점 추세선 "수평" 허용 오차 — 평균 종가의 이 비율(%/봉)까지 음의 기울기를 수평으로 본다. */
    static final double CONV_LOW_SLOPE_FLAT_TOL_PCT = 0.02;
    /** 돌파 후 박스 봉 수 허용 범위. */
    static final int BOX_MIN = 3, BOX_MAX = 7;
    /** 박스 고저 범위 상한 = ATR × 이 배수. */
    static final double BOX_RANGE_ATR_MULT = 1.5;

    /** 봉별 RVOL 분모 표본(직전 거래일 수) — 부족하면 null(§4c). */
    static final int RVOL_WINDOW = 20;

    private CandlePatternDetector() {}

    /** 감지 결과 — 확정 봉 기준. params 는 임계·실측 스냅샷(직렬화는 호출부 몫). */
    public static final class Detection {
        public final PatternType type;
        public final LocalDate tradeDate;
        public final BigDecimal close;
        public final Map<String, Object> params;

        Detection(PatternType type, LocalDate tradeDate, BigDecimal close, Map<String, Object> params) {
            this.type = type;
            this.tradeDate = tradeDate;
            this.close = close;
            this.params = params;
        }
    }

    /**
     * 최근 {@code recentBars}개 봉 각각을 확정 봉 후보로 검사 — 히스토리가 하루 늦게 유입돼도
     * (마지막 봉만 보면 놓치는) 확정 봉을 다음 배치에서 잡는다. 중복 기록은 호출부의
     * UNIQUE(stock, type, date) + 쿨다운이 막는다.
     *
     * @param rowsLatestFirst 리포지토리 표준 정렬(최신순, {@code findByStockCodeOrderByTradeDateDesc})
     */
    public static List<Detection> detectRecent(List<StockPriceHistory> rowsLatestFirst, int recentBars) {
        return detectRecent(rowsLatestFirst, recentBars, null);
    }

    /** stats 가 주어지면 후보별 기각 사유를 집계한다(감지/기각 어느 쪽이든 후보 1건으로 카운트). */
    public static List<Detection> detectRecent(
            List<StockPriceHistory> rowsLatestFirst, int recentBars, RejectionStats stats) {
        List<Detection> out = new ArrayList<>();
        if (rowsLatestFirst == null || rowsLatestFirst.isEmpty()) return out;
        List<StockPriceHistory> chrono = new ArrayList<>(rowsLatestFirst);
        Collections.reverse(chrono);
        int n = chrono.size();
        for (int endIdx = Math.max(0, n - recentBars); endIdx < n; endIdx++) {
            if (stats != null) { stats.addCandidate(); stats.addCandidate(); }   // bull + box
            Detection bull = detectBullContinuationAt(chrono, endIdx, stats);
            if (bull != null) out.add(bull);
            Detection box = detectConvergenceBoxAt(chrono, endIdx, stats);
            if (box != null) out.add(box);
        }
        return out;
    }

    // ==================== 패턴 1: 상승지속 ====================

    /** 확정 봉이 {@code endIdx}(시간순)일 때의 BULL_CONTINUATION 검사. 미충족/데이터 부족 → null. */
    static Detection detectBullContinuationAt(List<StockPriceHistory> chrono, int endIdx, RejectionStats stats) {
        StockPriceHistory conf = barAt(chrono, endIdx);
        if (conf == null) return reject(stats, RejectReason.BULL_DATA_MISSING);
        // 위치 필터 — 확정 봉이 직전 20봉 고점을 스스로 돌파(신선한 돌파).
        if (!passesMaFilter(chrono, endIdx)) return reject(stats, RejectReason.BULL_MA_FILTER);
        if (!breaksRecentHigh(chrono, endIdx, endIdx)) return reject(stats, RejectReason.BULL_NO_HIGH_BREAKOUT);

        BigDecimal atr = atrAt(chrono, endIdx);
        if (atr == null) return reject(stats, RejectReason.BULL_ATR_MISSING);
        double atrV = atr.doubleValue();

        // 확정 봉 = 장대양봉
        if (!isBullish(conf) || body(conf) < atrV * LONG_BODY_ATR_MULT) {
            return reject(stats, RejectReason.BULL_CONF_NOT_LONG_BULL);
        }

        // 직전 봉 = 저거래량 음봉
        int pullIdx = endIdx - 1;
        StockPriceHistory pull = barAt(chrono, pullIdx);
        if (pull == null || isBullish(pull) || body(pull) < atrV * PULLBACK_BODY_ATR_MULT) {
            return reject(stats, RejectReason.BULL_PULLBACK_SHAPE);
        }
        Double pullRvol = rvolAt(chrono, pullIdx);
        if (pullRvol == null || pullRvol >= PULLBACK_RVOL_MAX) {
            return reject(stats, RejectReason.BULL_PULLBACK_RVOL);
        }

        // 음봉 앞 도지 1~3봉 + 그 앞 장대양봉(리더)
        for (int d = DOJI_MIN; d <= DOJI_MAX; d++) {
            int leadIdx = pullIdx - d - 1;
            StockPriceHistory lead = barAt(chrono, leadIdx);
            if (lead == null) break;                    // 더 긴 d 는 더 과거 — 없으면 종료
            if (!allDoji(chrono, pullIdx - d, pullIdx - 1)) continue;
            if (!isBullish(lead) || body(lead) < atrV * LONG_BODY_ATR_MULT) continue;

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("atr14", round4(atrV));
            p.put("confBody", round4(body(conf)));
            p.put("leadBody", round4(body(lead)));
            p.put("dojiCount", d);
            p.put("pullbackBody", round4(body(pull)));
            p.put("pullbackRvol", round4(pullRvol));
            putThresholds(p);
            return new Detection(PatternType.BULL_CONTINUATION, conf.getTradeDate(), conf.getClosePrice(), p);
        }
        return reject(stats, RejectReason.BULL_DOJI_LEADER);
    }

    // ==================== 패턴 2: 수렴돌파 + 박스 ====================

    // BOX 후보의 기각 funnel 단계 — 조합 탐색이라 "가장 깊이 도달한 단계"를 그 후보의 사유로 기록.
    private static final int STG_NONE = -1, STG_CONV_SHAPE = 0, STG_CONV_RVOL = 1, STG_SURGE = 2,
            STG_BREAKOUT = 3, STG_HIGH_BREAKOUT = 4, STG_BOX_FORM = 5;

    /** 확정 봉(박스 마지막 봉)이 {@code endIdx}일 때의 CONVERGENCE_BOX 검사. 미충족/데이터 부족 → null. */
    static Detection detectConvergenceBoxAt(List<StockPriceHistory> chrono, int endIdx, RejectionStats stats) {
        StockPriceHistory conf = barAt(chrono, endIdx);
        if (conf == null) return reject(stats, RejectReason.BOX_DATA_MISSING);
        // 위치 필터 절반(추세) — 20봉 고점 돌파 판정은 돌파 봉이 정해진 뒤 루프 안에서(박스 유지 상태 기준).
        if (!passesMaFilter(chrono, endIdx)) return reject(stats, RejectReason.BOX_MA_FILTER);

        BigDecimal atr = atrAt(chrono, endIdx);
        if (atr == null) return reject(stats, RejectReason.BOX_ATR_MISSING);
        double atrV = atr.doubleValue();

        int deepest = STG_NONE;
        for (int boxLen = BOX_MIN; boxLen <= BOX_MAX; boxLen++) {
            int boxStart = endIdx - boxLen + 1;         // 박스에 확정 봉 포함
            int breakoutIdx = boxStart - 1;
            if (breakoutIdx < CONV_MIN_BARS) break;

            for (int convLen = CONV_MIN_BARS; convLen <= CONV_MAX_BARS; convLen++) {
                int convStart = breakoutIdx - convLen;
                if (convStart < 0) break;
                int convEnd = breakoutIdx - 1;
                if (!allValidOhlcv(chrono, convStart, endIdx)) break;

                // 수렴: 고점 하락 추세선 + 저점 상승/수평(허용오차 내)
                double[] highLine = regress(chrono, convStart, convEnd, true);
                double[] lowLine = regress(chrono, convStart, convEnd, false);
                if (highLine == null || lowLine == null) continue;
                double meanClose = meanClose(chrono, convStart, convEnd);
                double flatTol = meanClose * CONV_LOW_SLOPE_FLAT_TOL_PCT / 100.0;
                if (highLine[1] >= 0 || lowLine[1] < -flatTol) {
                    deepest = Math.max(deepest, STG_CONV_SHAPE);
                    continue;
                }

                // 무효 1: 수렴 구간 평균 RVOL < 0.5 (거래량 결측 시 fail-closed)
                Double avgRvol = convZoneRvol(chrono, convStart, convEnd);
                if (avgRvol == null || avgRvol < CONV_AVG_RVOL_MIN) {
                    deepest = Math.max(deepest, STG_CONV_RVOL);
                    continue;
                }

                // 무효 2: 돌파 직전 20봉 저점 대비 +40% 이상 급등(이미 과열)
                Double surgePct = surgeFromRecentLowPct(chrono, breakoutIdx);
                if (surgePct == null || surgePct >= CONV_SURGE_INVALID_PCT) {
                    deepest = Math.max(deepest, STG_SURGE);
                    continue;
                }

                // 돌파: 돌파 봉 종가 > 고점 하락 추세선의 그 시점 값
                double breakoutLevel = highLine[0] + highLine[1] * convLen; // x=convLen == breakoutIdx 위치
                StockPriceHistory breakout = chrono.get(breakoutIdx);
                if (breakout.getClosePrice().doubleValue() <= breakoutLevel) {
                    deepest = Math.max(deepest, STG_BREAKOUT);
                    continue;
                }

                // 위치 필터 나머지 — "20봉 고점 돌파 상태": 확정 봉 종가가 돌파 봉 이전 20봉 고점 위를 유지.
                // (창을 확정 봉 기준으로 잡으면 박스 자체 고점에 걸려 박스 유지가 불가능 — 앵커는 돌파 봉.)
                if (!breaksRecentHigh(chrono, endIdx, breakoutIdx)) {
                    deepest = Math.max(deepest, STG_HIGH_BREAKOUT);
                    continue;
                }

                // 박스: 돌파 후 3~7봉, 고저 범위 ≤ ATR×1.5, 저점이 돌파선 위 유지("상단에서" 형성)
                double boxHigh = Double.NEGATIVE_INFINITY, boxLow = Double.POSITIVE_INFINITY;
                for (int i = boxStart; i <= endIdx; i++) {
                    boxHigh = Math.max(boxHigh, chrono.get(i).getHighPrice().doubleValue());
                    boxLow = Math.min(boxLow, chrono.get(i).getLowPrice().doubleValue());
                }
                if (boxHigh - boxLow > atrV * BOX_RANGE_ATR_MULT || boxLow < breakoutLevel) {
                    deepest = Math.max(deepest, STG_BOX_FORM);
                    continue;
                }

                Map<String, Object> p = new LinkedHashMap<>();
                p.put("atr14", round4(atrV));
                p.put("convLen", convLen);
                p.put("boxLen", boxLen);
                p.put("highSlope", round4(highLine[1]));
                p.put("lowSlope", round4(lowLine[1]));
                p.put("convAvgRvol", round4(avgRvol));
                p.put("surgeFromLowPct", round4(surgePct));
                p.put("breakoutLevel", round4(breakoutLevel));
                p.put("boxRange", round4(boxHigh - boxLow));
                putThresholds(p);
                return new Detection(PatternType.CONVERGENCE_BOX, conf.getTradeDate(), conf.getClosePrice(), p);
            }
        }
        return reject(stats, boxReasonForStage(deepest));
    }

    private static RejectReason boxReasonForStage(int deepest) {
        switch (deepest) {
            case STG_CONV_SHAPE:    return RejectReason.BOX_NO_CONVERGENCE;
            case STG_CONV_RVOL:     return RejectReason.BOX_CONV_LOW_RVOL;
            case STG_SURGE:         return RejectReason.BOX_SURGE_INVALID;
            case STG_BREAKOUT:      return RejectReason.BOX_NO_BREAKOUT;
            case STG_HIGH_BREAKOUT: return RejectReason.BOX_NO_HIGH_BREAKOUT;
            case STG_BOX_FORM:      return RejectReason.BOX_NOT_FORMED;
            default:                return RejectReason.BOX_NO_CONV_WINDOW;
        }
    }

    /** 기각 헬퍼 — stats 가 있으면 사유 집계 후 null 반환. */
    private static Detection reject(RejectionStats stats, RejectReason reason) {
        if (stats != null) stats.count(reason);
        return null;
    }

    // ==================== 공통 위치 필터 ====================

    /**
     * 위치 필터(추세 절반) — 확정 봉 종가 ≥ MA20 + MA20 기울기({@value #MA_SLOPE_LOOKBACK}봉 전 대비) 비음수.
     * 판정 불가(데이터 부족·결측)면 false — 감지 자체를 버린다(fail-closed).
     */
    static boolean passesMaFilter(List<StockPriceHistory> chrono, int endIdx) {
        int need = MA_PERIOD + MA_SLOPE_LOOKBACK - 1;
        if (chrono == null || endIdx < need || endIdx >= chrono.size()) return false;
        if (!allValidOhlcv(chrono, endIdx - need, endIdx)) return false;

        double close = chrono.get(endIdx).getClosePrice().doubleValue();
        Double maNow = sma(chrono, endIdx, MA_PERIOD);
        Double maPast = sma(chrono, endIdx - MA_SLOPE_LOOKBACK, MA_PERIOD);
        if (maNow == null || maPast == null) return false;
        return close >= maNow && maNow >= maPast;
    }

    /**
     * 위치 필터(돌파 절반) — {@code closeIdx} 봉 종가가 {@code refIdx} 직전 {@value #POSITION_HIGH_LOOKBACK}봉
     * 고점보다 위인지. BULL_CONTINUATION 은 refIdx=확정 봉(신선한 돌파), CONVERGENCE_BOX 는 refIdx=돌파 봉
     * (돌파 상태 '유지' — 창을 확정 봉 기준으로 잡으면 박스 자체 고점에 걸린다).
     */
    static boolean breaksRecentHigh(List<StockPriceHistory> chrono, int closeIdx, int refIdx) {
        if (chrono == null || refIdx < POSITION_HIGH_LOOKBACK || closeIdx >= chrono.size()) return false;
        if (!allValidOhlcv(chrono, refIdx - POSITION_HIGH_LOOKBACK, closeIdx)) return false;

        double close = chrono.get(closeIdx).getClosePrice().doubleValue();
        double prevMaxHigh = Double.NEGATIVE_INFINITY;
        for (int i = refIdx - POSITION_HIGH_LOOKBACK; i < refIdx; i++) {
            prevMaxHigh = Math.max(prevMaxHigh, chrono.get(i).getHighPrice().doubleValue());
        }
        return close > prevMaxHigh;
    }

    // ==================== 보조 계산 (순수) ====================

    /** ATR14 — {@link AtrCalculator} 단일 출처 재사용(백테스트·봇과 동일 정의). endIdx 시점까지의 값. */
    static BigDecimal atrAt(List<StockPriceHistory> chrono, int endIdx) {
        List<StockPriceHistory> latestFirst = new ArrayList<>();
        for (int i = endIdx; i >= 0; i--) latestFirst.add(chrono.get(i));
        return AtrCalculator.atr14(latestFirst);
    }

    /**
     * 봉별 RVOL = 해당 봉 거래량 ÷ 직전 {@value #RVOL_WINDOW}봉 평균 거래량.
     * 과거 확정 봉에 대한 자기완결 근사 — 장중 거래대금 기반 {@code RvolService}(표시용)와 정의가 다르다.
     * 표본 부족·거래량 결측 시 null(§4c).
     */
    static Double rvolAt(List<StockPriceHistory> chrono, int idx) {
        if (idx < RVOL_WINDOW) return null;
        StockPriceHistory bar = chrono.get(idx);
        if (bar.getVolume() == null || bar.getVolume().signum() <= 0) return null;
        double sum = 0;
        for (int i = idx - RVOL_WINDOW; i < idx; i++) {
            BigDecimal v = chrono.get(i).getVolume();
            if (v == null || v.signum() <= 0) return null;
            sum += v.doubleValue();
        }
        double avg = sum / RVOL_WINDOW;
        return avg <= 0 ? null : bar.getVolume().doubleValue() / avg;
    }

    /**
     * 수렴 구간 평균 RVOL = 구간 평균 거래량 ÷ <b>구간 시작 전</b> {@value #RVOL_WINDOW}봉 평균 거래량.
     * 분모를 구간 밖 베이스라인에 고정한다 — 봉별 롤링 RVOL 평균으로 하면 긴 수렴에서 분모가
     * 저거래 봉들로 채워져 1.0 근처로 정규화돼 "말라붙은 수렴"을 못 잡는다. 결측 시 null(fail-closed).
     */
    static Double convZoneRvol(List<StockPriceHistory> chrono, int from, int to) {
        if (from < RVOL_WINDOW || to < from) return null;
        double baseSum = 0;
        for (int i = from - RVOL_WINDOW; i < from; i++) {
            BigDecimal v = chrono.get(i).getVolume();
            if (v == null || v.signum() <= 0) return null;
            baseSum += v.doubleValue();
        }
        double baseAvg = baseSum / RVOL_WINDOW;
        if (baseAvg <= 0) return null;
        double zoneSum = 0;
        for (int i = from; i <= to; i++) {
            BigDecimal v = chrono.get(i).getVolume();
            if (v == null || v.signum() <= 0) return null;
            zoneSum += v.doubleValue();
        }
        return (zoneSum / (to - from + 1)) / baseAvg;
    }

    /** 직전 20봉 저점 대비 해당 봉 종가 급등률(%) — 저점 결측 시 null. */
    static Double surgeFromRecentLowPct(List<StockPriceHistory> chrono, int idx) {
        if (idx < POSITION_HIGH_LOOKBACK) return null;
        double minLow = Double.POSITIVE_INFINITY;
        for (int i = idx - POSITION_HIGH_LOOKBACK; i < idx; i++) {
            BigDecimal low = chrono.get(i).getLowPrice();
            if (low == null || low.signum() <= 0) return null;
            minLow = Math.min(minLow, low.doubleValue());
        }
        return (chrono.get(idx).getClosePrice().doubleValue() / minLow - 1.0) * 100.0;
    }

    /** [from..to] 전 봉이 도지(몸통/전체범위 ≤ {@value #DOJI_BODY_RANGE_MAX})인지. 범위 0 봉은 도지로 본다. */
    static boolean allDoji(List<StockPriceHistory> chrono, int from, int to) {
        for (int i = from; i <= to; i++) {
            StockPriceHistory b = barAt(chrono, i);
            if (b == null) return false;
            double range = b.getHighPrice().doubleValue() - b.getLowPrice().doubleValue();
            if (range <= 0) continue;                   // H==L → 몸통도 0, 극단 도지
            if (body(b) / range > DOJI_BODY_RANGE_MAX) return false;
        }
        return true;
    }

    /** 단순이평 — endIdx 포함 직전 period 봉 종가 평균. 데이터 부족 시 null. */
    static Double sma(List<StockPriceHistory> chrono, int endIdx, int period) {
        if (endIdx + 1 < period) return null;
        double sum = 0;
        for (int i = endIdx - period + 1; i <= endIdx; i++) {
            BigDecimal c = chrono.get(i).getClosePrice();
            if (c == null || c.signum() <= 0) return null;
            sum += c.doubleValue();
        }
        return sum / period;
    }

    /**
     * [from..to] 고가(또는 저가) 최소제곱 회귀 — 반환 [절편, 기울기] (x=0 이 from).
     * 감지 휴리스틱이라 double 정밀도로 충분.
     */
    static double[] regress(List<StockPriceHistory> chrono, int from, int to, boolean useHigh) {
        int n = to - from + 1;
        if (n < 2) return null;
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            BigDecimal v = useHigh ? chrono.get(from + i).getHighPrice() : chrono.get(from + i).getLowPrice();
            if (v == null || v.signum() <= 0) return null;
            double y = v.doubleValue();
            sumX += i;
            sumY += y;
            sumXY += (double) i * y;
            sumXX += (double) i * i;
        }
        double denom = n * sumXX - sumX * sumX;
        if (denom == 0) return null;
        double slope = (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;
        return new double[]{intercept, slope};
    }

    private static double meanClose(List<StockPriceHistory> chrono, int from, int to) {
        double sum = 0;
        for (int i = from; i <= to; i++) sum += chrono.get(i).getClosePrice().doubleValue();
        return sum / (to - from + 1);
    }

    private static boolean allValidOhlcv(List<StockPriceHistory> chrono, int from, int to) {
        for (int i = from; i <= to; i++) {
            if (barAt(chrono, i) == null) return false;
        }
        return true;
    }

    /** OHLCV 유효 봉만 반환, 아니면 null — 결측 봉이 낀 창은 감지에서 버린다(§4c fail-closed). */
    private static StockPriceHistory barAt(List<StockPriceHistory> chrono, int idx) {
        if (chrono == null || idx < 0 || idx >= chrono.size()) return null;
        StockPriceHistory b = chrono.get(idx);
        if (b == null || b.getTradeDate() == null) return null;
        if (b.getOpenPrice() == null || b.getOpenPrice().signum() <= 0) return null;
        if (b.getHighPrice() == null || b.getHighPrice().signum() <= 0) return null;
        if (b.getLowPrice() == null || b.getLowPrice().signum() <= 0) return null;
        if (b.getClosePrice() == null || b.getClosePrice().signum() <= 0) return null;
        if (b.getHighPrice().compareTo(b.getLowPrice()) < 0) return null;
        return b;
    }

    private static boolean isBullish(StockPriceHistory b) {
        return b.getClosePrice().compareTo(b.getOpenPrice()) > 0;
    }

    private static double body(StockPriceHistory b) {
        return Math.abs(b.getClosePrice().doubleValue() - b.getOpenPrice().doubleValue());
    }

    private static double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /** 스냅샷에 임계값도 같이 — 임계 튜닝 후에도 과거 행의 판정 기준이 재현되게. */
    private static void putThresholds(Map<String, Object> p) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("longBodyAtrMult", LONG_BODY_ATR_MULT);
        t.put("dojiBodyRangeMax", DOJI_BODY_RANGE_MAX);
        t.put("pullbackRvolMax", PULLBACK_RVOL_MAX);
        t.put("pullbackBodyAtrMult", PULLBACK_BODY_ATR_MULT);
        t.put("convMinBars", CONV_MIN_BARS);
        t.put("convAvgRvolMin", CONV_AVG_RVOL_MIN);
        t.put("convSurgeInvalidPct", CONV_SURGE_INVALID_PCT);
        t.put("boxRangeAtrMult", BOX_RANGE_ATR_MULT);
        t.put("positionHighLookback", POSITION_HIGH_LOOKBACK);
        t.put("maSlopeLookback", MA_SLOPE_LOOKBACK);
        p.put("thresholds", t);
    }
}
