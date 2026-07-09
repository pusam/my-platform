package com.myplatform.backend.service;

import com.myplatform.backend.service.VolatilityRegimeService.Decision;
import com.myplatform.backend.service.VolatilityRegimeService.GateMode;
import com.myplatform.backend.service.VolatilityRegimeService.VolRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * VKOSPI 변동성 국면 게이트 — 순수 판정(백분위 임계·모드 매트릭스·설정 파싱) + evaluateEntry 통합.
 * 불변식: mode OFF = PROCEED(byte-identical) · UNKNOWN = skip(§4c 가짜 NORMAL 금지) · 청산 무관(진입만).
 */
class VolatilityRegimeServiceTest {

    private VolatilityRegimeService svc;

    @BeforeEach
    void setUp() {
        svc = new VolatilityRegimeService(mock(KoreaInvestmentService.class));
        ReflectionTestUtils.setField(svc, "topPercent", 10.0);
        ReflectionTestUtils.setField(svc, "minSamples", 10);
        ReflectionTestUtils.setField(svc, "lookbackDays", 252);
        ReflectionTestUtils.setField(svc, "reducedFactorRaw", 0.5);
    }

    private static List<Double> range(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> (double) i).collect(Collectors.toList());
    }

    // ---------- classifyVolRegime ----------

    @Test
    @DisplayName("상위 10% 임계(90th 백분위) 경계 — 임계 이상 HIGH_VOL, 미만 NORMAL")
    void classify_percentileBoundary() {
        List<Double> hist = range(1, 100);   // 1..100, 90th nearest-rank = sorted[89] = 90
        assertThat(VolatilityRegimeService.classifyVolRegime(95.0, hist, 10.0, 10)).isEqualTo(VolRegime.HIGH_VOL);
        assertThat(VolatilityRegimeService.classifyVolRegime(90.0, hist, 10.0, 10)).isEqualTo(VolRegime.HIGH_VOL); // 경계 포함
        assertThat(VolatilityRegimeService.classifyVolRegime(89.0, hist, 10.0, 10)).isEqualTo(VolRegime.NORMAL);
    }

    @Test
    @DisplayName("§4c 결측/부족/설정오류 → UNKNOWN (가짜 NORMAL 금지)")
    void classify_unknownCases() {
        List<Double> hist = range(1, 100);
        assertThat(VolatilityRegimeService.classifyVolRegime(null, hist, 10.0, 10)).isEqualTo(VolRegime.UNKNOWN);
        assertThat(VolatilityRegimeService.classifyVolRegime(50.0, null, 10.0, 10)).isEqualTo(VolRegime.UNKNOWN);
        assertThat(VolatilityRegimeService.classifyVolRegime(50.0, range(1, 5), 10.0, 10)).isEqualTo(VolRegime.UNKNOWN); // 표본<min
        assertThat(VolatilityRegimeService.classifyVolRegime(50.0, hist, 0.0, 10)).isEqualTo(VolRegime.UNKNOWN);   // topPct 0
        assertThat(VolatilityRegimeService.classifyVolRegime(50.0, hist, 100.0, 10)).isEqualTo(VolRegime.UNKNOWN); // topPct 100
    }

    @Test
    @DisplayName("null 원소 섞인 history — 유효값만 세고 부족하면 UNKNOWN")
    void classify_nullElementsFiltered() {
        List<Double> hist = new ArrayList<>(range(1, 100));
        hist.add(null); hist.add(null);
        assertThat(VolatilityRegimeService.classifyVolRegime(95.0, hist, 10.0, 10)).isEqualTo(VolRegime.HIGH_VOL);
    }

    // ---------- decideGate ----------

    @Test
    @DisplayName("게이트 결정 매트릭스 — HIGH_VOL 만 모드별, 그 외 PROCEED")
    void decideGate_matrix() {
        assertThat(VolatilityRegimeService.decideGate(GateMode.OFF, VolRegime.HIGH_VOL)).isEqualTo(Decision.PROCEED);
        assertThat(VolatilityRegimeService.decideGate(GateMode.BLOCK, VolRegime.HIGH_VOL)).isEqualTo(Decision.BLOCK);
        assertThat(VolatilityRegimeService.decideGate(GateMode.REDUCED, VolRegime.HIGH_VOL)).isEqualTo(Decision.REDUCE);
        assertThat(VolatilityRegimeService.decideGate(GateMode.BLOCK, VolRegime.NORMAL)).isEqualTo(Decision.PROCEED);
        assertThat(VolatilityRegimeService.decideGate(GateMode.BLOCK, VolRegime.UNKNOWN)).isEqualTo(Decision.PROCEED); // §4c skip
        assertThat(VolatilityRegimeService.decideGate(GateMode.REDUCED, VolRegime.UNKNOWN)).isEqualTo(Decision.PROCEED);
    }

    // ---------- parseMode ----------

    @Test
    @DisplayName("모드 파싱 — 대소문자/공백 허용, 결측/오설정은 OFF 폴백")
    void parseMode_lenient() {
        assertThat(VolatilityRegimeService.parseMode("BLOCK")).isEqualTo(GateMode.BLOCK);
        assertThat(VolatilityRegimeService.parseMode(" reduced ")).isEqualTo(GateMode.REDUCED);
        assertThat(VolatilityRegimeService.parseMode("off")).isEqualTo(GateMode.OFF);
        assertThat(VolatilityRegimeService.parseMode(null)).isEqualTo(GateMode.OFF);
        assertThat(VolatilityRegimeService.parseMode("garbage")).isEqualTo(GateMode.OFF);
    }

    // ---------- evaluateEntry 통합 ----------

    @Test
    @DisplayName("mode OFF → 국면 무관 항상 PROCEED (byte-identical)")
    void evaluate_offAlwaysProceed() {
        ReflectionTestUtils.setField(svc, "gateModeRaw", "OFF");
        svc.primeCacheForTest(range(1, 20), 20.0);   // 고변동이어도
        assertThat(svc.evaluateEntry("테스트")).isEqualTo(Decision.PROCEED);
    }

    @Test
    @DisplayName("mode BLOCK + 고변동 → BLOCK")
    void evaluate_blockHighVol() {
        ReflectionTestUtils.setField(svc, "gateModeRaw", "BLOCK");
        svc.primeCacheForTest(range(1, 20), 20.0);   // current 20 ≥ 18(90th) → HIGH_VOL
        assertThat(svc.evaluateEntry("테스트")).isEqualTo(Decision.BLOCK);
    }

    @Test
    @DisplayName("mode BLOCK + 정상 변동 → PROCEED")
    void evaluate_blockNormal() {
        ReflectionTestUtils.setField(svc, "gateModeRaw", "BLOCK");
        svc.primeCacheForTest(range(1, 20), 5.0);    // current 5 < 18 → NORMAL
        assertThat(svc.evaluateEntry("테스트")).isEqualTo(Decision.PROCEED);
    }

    @Test
    @DisplayName("mode REDUCED + 고변동 → REDUCE")
    void evaluate_reduceHighVol() {
        ReflectionTestUtils.setField(svc, "gateModeRaw", "REDUCED");
        svc.primeCacheForTest(range(1, 20), 20.0);
        assertThat(svc.evaluateEntry("테스트")).isEqualTo(Decision.REDUCE);
        assertThat(svc.reducedFactor()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("mode BLOCK + VKOSPI 미수집(캐시 없음) → UNKNOWN skip → PROCEED (§4c)")
    void evaluate_unknownSkips() {
        ReflectionTestUtils.setField(svc, "gateModeRaw", "BLOCK");
        // 캐시 미주입 → cachedSeries()가 실 KIS(mock, 빈 리스트) → null → UNKNOWN
        assertThat(svc.currentVolRegime()).isEqualTo(VolRegime.UNKNOWN);
        assertThat(svc.evaluateEntry("테스트")).isEqualTo(Decision.PROCEED);
    }
}
