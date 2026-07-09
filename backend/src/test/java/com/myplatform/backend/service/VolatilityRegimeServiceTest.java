package com.myplatform.backend.service;

import com.myplatform.backend.service.VolatilityRegimeService.Decision;
import com.myplatform.backend.service.VolatilityRegimeService.GateMode;
import com.myplatform.backend.service.VolatilityRegimeService.VolRegime;
import com.myplatform.backend.service.VolatilityRegimeService.UnknownAlertDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VKOSPI 변동성 국면 게이트 — 순수 판정(백분위 임계·모드 매트릭스·설정 파싱) + evaluateEntry 통합.
 * 불변식: mode OFF = PROCEED(byte-identical) · UNKNOWN = skip(§4c 가짜 NORMAL 금지) · 청산 무관(진입만).
 */
class VolatilityRegimeServiceTest {

    private VolatilityRegimeService svc;
    private TelegramNotificationService telegram;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        telegram = mock(TelegramNotificationService.class);
        when(telegram.isEnabled()).thenReturn(true);
        ObjectProvider<TelegramNotificationService> tgProvider = mock(ObjectProvider.class);
        when(tgProvider.getIfAvailable()).thenReturn(telegram);
        svc = new VolatilityRegimeService(mock(KoreaInvestmentService.class), tgProvider);
        ReflectionTestUtils.setField(svc, "topPercent", 10.0);
        ReflectionTestUtils.setField(svc, "minSamples", 10);
        ReflectionTestUtils.setField(svc, "lookbackDays", 252);
        ReflectionTestUtils.setField(svc, "reducedFactorRaw", 0.5);
        ReflectionTestUtils.setField(svc, "unknownAlertDays", 3);
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

    // ---------- UNKNOWN 연속일 경보 ----------

    @Test
    @DisplayName("decideUnknownAlert: 새 날 UNKNOWN 마다 +1, threshold 도달 시 발송, 같은 날 재발송 안 함")
    void unknownAlert_streakAndCooldown() {
        LocalDate d1 = LocalDate.of(2026, 7, 6), d2 = d1.plusDays(1), d3 = d2.plusDays(1);
        UnknownAlertDecision r1 = VolatilityRegimeService.decideUnknownAlert(0, null, null, true, d1, 3);
        assertThat(r1.newStreak()).isEqualTo(1);
        assertThat(r1.fireAlert()).isFalse();
        // 같은 날 반복 호출 → 증가 안 함(장중 틱마다 안 셈)
        assertThat(VolatilityRegimeService.decideUnknownAlert(1, d1, null, true, d1, 3).newStreak()).isEqualTo(1);
        // day2 → 2
        UnknownAlertDecision r2 = VolatilityRegimeService.decideUnknownAlert(1, d1, null, true, d2, 3);
        assertThat(r2.newStreak()).isEqualTo(2);
        assertThat(r2.fireAlert()).isFalse();
        // day3 → 3 도달 → 발송
        UnknownAlertDecision r3 = VolatilityRegimeService.decideUnknownAlert(2, d2, null, true, d3, 3);
        assertThat(r3.newStreak()).isEqualTo(3);
        assertThat(r3.fireAlert()).isTrue();
        // day3 같은 날 재호출 → 이미 발송(lastAlert=d3) → 쿨다운으로 재발송 안 함
        assertThat(VolatilityRegimeService.decideUnknownAlert(3, d3, d3, true, d3, 3).fireAlert()).isFalse();
    }

    @Test
    @DisplayName("decideUnknownAlert: 국면 확인(known) → 스트릭 리셋")
    void unknownAlert_resetOnKnown() {
        LocalDate d = LocalDate.of(2026, 7, 6);
        UnknownAlertDecision r = VolatilityRegimeService.decideUnknownAlert(2, d.minusDays(1), null, false, d, 3);
        assertThat(r.newStreak()).isZero();
        assertThat(r.fireAlert()).isFalse();
    }

    @Test
    @DisplayName("evaluateEntry: UNKNOWN 임계 도달 → risk 경보 1회(게이트는 PROCEED 유지 = fail-open)")
    void evaluate_unknownStreakAlertsOnce() {
        ReflectionTestUtils.setField(svc, "gateModeRaw", "BLOCK");
        ReflectionTestUtils.setField(svc, "unknownAlertDays", 1);   // 첫 UNKNOWN 날 즉시 경보(테스트 결정성)

        assertThat(svc.evaluateEntry("테스트")).isEqualTo(Decision.PROCEED);   // 게이트는 fail-open
        assertThat(svc.evaluateEntry("테스트")).isEqualTo(Decision.PROCEED);   // 같은 날 재호출

        verify(telegram, times(1)).sendRisk(anyString());   // 하루 1회 쿨다운
    }

    @Test
    @DisplayName("evaluateEntry: mode OFF → UNKNOWN 이어도 경보 안 함(게이트 미작동이라 무의미)")
    void evaluate_offNoAlert() {
        ReflectionTestUtils.setField(svc, "gateModeRaw", "OFF");
        ReflectionTestUtils.setField(svc, "unknownAlertDays", 1);

        svc.evaluateEntry("테스트");

        verify(telegram, never()).sendRisk(anyString());
    }
}
