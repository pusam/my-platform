package com.myplatform.backend.util;

import com.myplatform.backend.entity.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캔들 패턴 shadow 감지 순수함수 검증 — 합성 일봉 시나리오.
 *
 * <p>구성 원칙: 베이스(횡보) 구간으로 ATR·RVOL·MA 표본을 채운 뒤 패턴 구간을 붙인다.
 * 각 무효 조건은 정상 시나리오에서 해당 조건 하나만 깨뜨려 기각을 확인한다.
 */
class CandlePatternDetectorTest {

    private static final LocalDate D0 = LocalDate.of(2026, 1, 1);

    private static StockPriceHistory bar(int seq, double o, double h, double l, double c, long vol) {
        return StockPriceHistory.builder()
                .stockCode("000001")
                .tradeDate(D0.plusDays(seq))
                .openPrice(BigDecimal.valueOf(o))
                .highPrice(BigDecimal.valueOf(h))
                .lowPrice(BigDecimal.valueOf(l))
                .closePrice(BigDecimal.valueOf(c))
                .volume(BigDecimal.valueOf(vol))
                .build();
    }

    /** 횡보 베이스 봉 — ATR≈1.2, 거래량 1000. */
    private static void addFlatBase(List<StockPriceHistory> bars, int count) {
        for (int i = 0; i < count; i++) {
            bars.add(bar(bars.size(), 99.8, 100.6, 99.4, 100.2, 1000));
        }
    }

    private static List<StockPriceHistory> latestFirst(List<StockPriceHistory> chrono) {
        List<StockPriceHistory> copy = new ArrayList<>(chrono);
        Collections.reverse(copy);
        return copy;
    }

    // ==================== 패턴 1: BULL_CONTINUATION ====================

    /** 장대양봉 → 도지 → 저거래 눌림 음봉 → 장대양봉 확정의 정상 시퀀스. */
    private static List<StockPriceHistory> bullContinuationBars(long pullbackVol, double confClose) {
        List<StockPriceHistory> bars = new ArrayList<>();
        addFlatBase(bars, 40);
        bars.add(bar(bars.size(), 100.0, 111.0, 99.8, 110.5, 3000));   // 리더 장대양봉 (body 10.5)
        bars.add(bar(bars.size(), 110.3, 111.2, 109.6, 110.5, 800));   // 도지 (body 0.2 / range 1.6)
        bars.add(bar(bars.size(), 110.4, 110.6, 105.9, 106.2, pullbackVol)); // 눌림 음봉 (body 4.2)
        double confOpen = confClose - 11.5;                             // 확정 장대양봉 (body 11.5)
        bars.add(bar(bars.size(), confOpen, confClose + 0.4, confOpen - 0.2, confClose, 2000));
        return bars;
    }

    @Test
    void bullContinuation_정상시퀀스_확정봉에서_감지() {
        List<StockPriceHistory> bars = bullContinuationBars(400, 118.0);

        List<CandlePatternDetector.Detection> out =
                CandlePatternDetector.detectRecent(latestFirst(bars), 1);

        assertThat(out).hasSize(1);
        CandlePatternDetector.Detection d = out.get(0);
        assertThat(d.type).isEqualTo(CandlePatternDetector.PatternType.BULL_CONTINUATION);
        assertThat(d.tradeDate).isEqualTo(bars.get(bars.size() - 1).getTradeDate());
        assertThat(d.close).isEqualByComparingTo(BigDecimal.valueOf(118.0));
        assertThat(d.params).containsKeys("atr14", "dojiCount", "pullbackRvol", "thresholds");
        assertThat((Integer) d.params.get("dojiCount")).isEqualTo(1);
        assertThat((Double) d.params.get("pullbackRvol")).isLessThan(0.7);
    }

    @Test
    void bullContinuation_눌림봉_거래량이_많으면_기각() {
        // RVOL ≈ 1500/1090 ≈ 1.38 ≥ 0.7 → 저거래량 눌림 아님
        List<StockPriceHistory> bars = bullContinuationBars(1500, 118.0);

        assertThat(CandlePatternDetector.detectRecent(latestFirst(bars), 1)).isEmpty();
    }

    @Test
    void bullContinuation_확정봉이_20봉고점_미돌파면_기각() {
        // 확정 종가 105 < 도지 고가 111.2 — 위치 필터(신고가 돌파) 미충족
        List<StockPriceHistory> bars = bullContinuationBars(400, 105.0);

        assertThat(CandlePatternDetector.detectRecent(latestFirst(bars), 1)).isEmpty();
    }

    @Test
    void bullContinuation_도지없이_리더와_눌림이_붙으면_기각() {
        List<StockPriceHistory> bars = new ArrayList<>();
        addFlatBase(bars, 40);
        bars.add(bar(bars.size(), 100.0, 111.0, 99.8, 110.5, 3000));   // 리더
        bars.add(bar(bars.size(), 110.4, 110.6, 105.9, 106.2, 400));   // 곧장 눌림 음봉 (도지 0개)
        bars.add(bar(bars.size(), 106.5, 118.4, 106.3, 118.0, 2000));  // 확정

        assertThat(CandlePatternDetector.detectRecent(latestFirst(bars), 1)).isEmpty();
    }

    @Test
    void bullContinuation_도지가_아닌_중간봉이면_기각() {
        List<StockPriceHistory> bars = new ArrayList<>();
        addFlatBase(bars, 40);
        bars.add(bar(bars.size(), 100.0, 111.0, 99.8, 110.5, 3000));   // 리더
        bars.add(bar(bars.size(), 108.0, 111.2, 107.8, 110.9, 800));   // body 2.9 / range 3.4 = 0.85 > 0.3
        bars.add(bar(bars.size(), 110.4, 110.6, 105.9, 106.2, 400));   // 눌림 음봉
        bars.add(bar(bars.size(), 106.5, 118.4, 106.3, 118.0, 2000));  // 확정

        assertThat(CandlePatternDetector.detectRecent(latestFirst(bars), 1)).isEmpty();
    }

    @Test
    void detectRecent_확정봉_뒤에_봉이_추가돼도_최근N봉_재스캔으로_잡는다() {
        // 히스토리 지연 유입 시나리오 — 확정 봉이 마지막-1 위치
        List<StockPriceHistory> bars = bullContinuationBars(400, 118.0);
        bars.add(bar(bars.size(), 117.8, 118.6, 117.4, 118.2, 1000));

        List<CandlePatternDetector.Detection> out =
                CandlePatternDetector.detectRecent(latestFirst(bars), 3);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).tradeDate).isEqualTo(bars.get(bars.size() - 2).getTradeDate());
    }

    // ==================== 위치 필터 ====================

    @Test
    void 위치필터_하락추세_MA기울기_음수면_기각() {
        // 꾸준한 하락 — 마지막 봉 종가를 MA20 위로 올려도 기울기 음수라 무효
        List<StockPriceHistory> bars = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double c = 130 - i * 0.8;
            bars.add(bar(i, c + 0.2, c + 0.8, c - 0.6, c, 1000));
        }
        List<StockPriceHistory> chrono = new ArrayList<>(bars);
        // 마지막 봉만 반등시켜 종가 > MA20 이 되게 (MA20 ≈ 114.6, 종가 120)
        chrono.set(39, bar(39, 99.0, 120.5, 98.8, 120.0, 1000));

        assertThat(CandlePatternDetector.passesMaFilter(chrono, 39)).isFalse();
    }

    @Test
    void 위치필터_20봉고점_돌파상태_판정() {
        List<StockPriceHistory> bars = new ArrayList<>();
        addFlatBase(bars, 40);
        bars.add(bar(bars.size(), 100.0, 103.0, 99.8, 102.5, 1000));   // 종가 102.5 > 베이스 고점 100.6

        assertThat(CandlePatternDetector.breaksRecentHigh(bars, 40, 40)).isTrue();
        assertThat(CandlePatternDetector.breaksRecentHigh(bars, 39, 39)).isFalse(); // 베이스 봉은 미돌파
    }

    // ==================== 패턴 2: CONVERGENCE_BOX ====================

    /**
     * 수렴(고점 하락·저점 상승) 18봉 → 돌파 → 박스 3봉의 정상 시퀀스.
     *
     * @param convLowStart 수렴 저점 시작값 — 78 로 내리면 돌파가 저점 대비 +40% 급등이 되어 무효
     * @param convVol      수렴 구간 거래량 — 300 이면 평균 RVOL < 0.5 로 무효
     * @param boxBars      돌파 후 박스 봉 수 (3 미만이면 시그널 미확정)
     */
    private static List<StockPriceHistory> convergenceBoxBars(double convLowStart, long convVol, int boxBars) {
        List<StockPriceHistory> bars = new ArrayList<>();
        addFlatBase(bars, 25);
        for (int i = 0; i < 18; i++) {                                  // 수렴: 고점 110↘, 저점 상승↗
            double high = 110 - 0.3 * i;
            double low = convLowStart + 0.25 * i;
            double close = (high + low) / 2;
            double open = Math.min(high, Math.max(low, close + 0.1));
            bars.add(bar(bars.size(), open, high, low, close, convVol));
        }
        bars.add(bar(bars.size(), 105.0, 112.0, 104.5, 111.0, 2500));   // 돌파 (추세선 상단 위 종가)
        double[][] box = {{111.0, 112.3, 110.2, 111.5}, {111.5, 112.5, 110.6, 111.8},
                          {111.8, 112.8, 111.0, 112.6}, {112.0, 112.9, 111.2, 112.4},
                          {112.2, 113.0, 111.4, 112.5}};
        for (int i = 0; i < boxBars && i < box.length; i++) {
            bars.add(bar(bars.size(), box[i][0], box[i][1], box[i][2], box[i][3], 1100));
        }
        return bars;
    }

    @Test
    void convergenceBox_수렴_돌파_박스3봉이면_확정() {
        List<StockPriceHistory> bars = convergenceBoxBars(97.0, 900, 3);

        List<CandlePatternDetector.Detection> out =
                CandlePatternDetector.detectRecent(latestFirst(bars), 1);

        assertThat(out).hasSize(1);
        CandlePatternDetector.Detection d = out.get(0);
        assertThat(d.type).isEqualTo(CandlePatternDetector.PatternType.CONVERGENCE_BOX);
        assertThat(d.tradeDate).isEqualTo(bars.get(bars.size() - 1).getTradeDate());
        assertThat(d.params).containsKeys("convLen", "boxLen", "highSlope", "lowSlope", "convAvgRvol");
        assertThat((Double) d.params.get("highSlope")).isLessThan(0);
        assertThat((Double) d.params.get("convAvgRvol")).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void convergenceBox_수렴구간_저거래량이면_무효() {
        // 수렴 RVOL ≈ 300/1000 = 0.3 < 0.5
        List<StockPriceHistory> bars = convergenceBoxBars(97.0, 300, 3);

        assertThat(CandlePatternDetector.detectRecent(latestFirst(bars), 1)).isEmpty();
    }

    @Test
    void convergenceBox_저점대비_40퍼센트_급등이면_무효() {
        // 수렴 저점 78 → 돌파 종가 111 = +42.3% ≥ 40%
        List<StockPriceHistory> bars = convergenceBoxBars(78.0, 900, 3);

        assertThat(CandlePatternDetector.detectRecent(latestFirst(bars), 1)).isEmpty();
    }

    @Test
    void convergenceBox_돌파만_있고_박스_미형성이면_시그널없음() {
        // 돌파 후 2봉뿐 — 박스 최소 3봉 미달
        List<StockPriceHistory> bars = convergenceBoxBars(97.0, 900, 2);

        assertThat(CandlePatternDetector.detectRecent(latestFirst(bars), 1)).isEmpty();
    }

    @Test
    void convergenceBox_고점이_상승추세면_수렴아님_기각() {
        List<StockPriceHistory> bars = new ArrayList<>();
        addFlatBase(bars, 25);
        for (int i = 0; i < 18; i++) {                                  // 고점·저점 모두 상승 = 수렴 아님
            double high = 104 + 0.3 * i;
            double low = 97 + 0.25 * i;
            double close = (high + low) / 2;
            bars.add(bar(bars.size(), close - 0.1, high, low, close, 900));
        }
        bars.add(bar(bars.size(), 105.0, 112.0, 104.5, 111.0, 2500));
        bars.add(bar(bars.size(), 111.0, 112.3, 110.2, 111.5, 1100));
        bars.add(bar(bars.size(), 111.5, 112.5, 110.6, 111.8, 1100));
        bars.add(bar(bars.size(), 111.8, 112.8, 111.0, 112.6, 1000));

        assertThat(CandlePatternDetector.detectRecent(latestFirst(bars), 1)).isEmpty();
    }

    // ==================== 기각 사유 집계 (sanity 로그용) ====================

    @Test
    void 기각집계_눌림_거래량기각이_사유별로_카운트된다() {
        List<StockPriceHistory> bars = bullContinuationBars(1500, 118.0);
        CandlePatternDetector.RejectionStats stats = new CandlePatternDetector.RejectionStats();

        List<CandlePatternDetector.Detection> out =
                CandlePatternDetector.detectRecent(latestFirst(bars), 1, stats);

        assertThat(out).isEmpty();
        assertThat(stats.candidates()).isEqualTo(2);   // 1봉 × (bull + box)
        assertThat(stats.asMap().get(CandlePatternDetector.RejectReason.BULL_PULLBACK_RVOL)).isEqualTo(1);
        // 전 사유가 0 포함으로 노출("기각 0"과 "도달 0" 구분) + 후보 = 감지 + 기각 합
        assertThat(stats.asMap()).hasSize(CandlePatternDetector.RejectReason.values().length);
        int totalRejects = stats.asMap().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(totalRejects).isEqualTo(stats.candidates());
    }

    @Test
    void 기각집계_수렴저거래는_가장깊이_도달한_단계로_기록된다() {
        List<StockPriceHistory> bars = convergenceBoxBars(97.0, 300, 3);
        CandlePatternDetector.RejectionStats stats = new CandlePatternDetector.RejectionStats();

        CandlePatternDetector.detectRecent(latestFirst(bars), 1, stats);

        // 다른 (boxLen, convLen) 조합은 수렴 형태에서 먼저 죽지만, 최심 단계는 RVOL 무효
        assertThat(stats.asMap().get(CandlePatternDetector.RejectReason.BOX_CONV_LOW_RVOL)).isEqualTo(1);
    }

    @Test
    void 기각집계_감지된_후보는_기각으로_세지_않는다() {
        List<StockPriceHistory> bars = bullContinuationBars(400, 118.0);
        CandlePatternDetector.RejectionStats stats = new CandlePatternDetector.RejectionStats();

        List<CandlePatternDetector.Detection> out =
                CandlePatternDetector.detectRecent(latestFirst(bars), 1, stats);

        assertThat(out).hasSize(1);
        int totalRejects = stats.asMap().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(totalRejects).isEqualTo(stats.candidates() - out.size());
    }

    // ==================== §4c fail-closed ====================

    @Test
    void 결측봉이_창에_끼면_감지자체를_버린다() {
        List<StockPriceHistory> bars = bullContinuationBars(400, 118.0);
        StockPriceHistory broken = bars.get(41);                        // 도지 봉의 거래량 결측
        broken.setVolume(null);

        assertThat(CandlePatternDetector.detectRecent(latestFirst(bars), 1)).isEmpty();
    }
}
