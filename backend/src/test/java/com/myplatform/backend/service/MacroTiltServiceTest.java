package com.myplatform.backend.service;

import com.myplatform.backend.entity.MacroTiltSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매크로 tilt 분류(P3-7) 순수 함수 테스트 — 임계 경계 + null 축 제외(§4c) 검증.
 * 임계값은 임시값(스냅샷 축적 후 캘리브레이션) — 이 테스트는 현재 규칙의 회귀 가드.
 */
class MacroTiltServiceTest {

    // ==================== classifyMacroRegime ====================

    @Test
    @DisplayName("VKOSPI >= 30 → 공포 강제 RISK_OFF (타 축이 +2 투표여도)")
    void vkospiPanicOverride() {
        // rate -20bp(+1) + sox +5%(+1) = +2 지만 공포 강제가 이김
        assertThat(MacroTiltService.classifyMacroRegime(30.0, -20.0, 5.0)).isEqualTo("RISK_OFF");
        // 오버라이드 직전(29.99)은 여전히 >=25 구간 → vkospi -1 투표가 +2 를 +1 로 깎아 NEUTRAL
        assertThat(MacroTiltService.classifyMacroRegime(29.99, -20.0, 5.0)).isEqualTo("NEUTRAL");
        // vkospi 가 중립 구간(24.0)이어야 rate+sox 합 +2 로 RISK_ON
        assertThat(MacroTiltService.classifyMacroRegime(24.0, -20.0, 5.0)).isEqualTo("RISK_ON");
    }

    @Test
    @DisplayName("3축 위험선호 → RISK_ON / 3축 위험회피 → RISK_OFF")
    void allAxes() {
        assertThat(MacroTiltService.classifyMacroRegime(15.0, -20.0, 4.0)).isEqualTo("RISK_ON");
        assertThat(MacroTiltService.classifyMacroRegime(26.0, 20.0, -4.0)).isEqualTo("RISK_OFF");
    }

    @Test
    @DisplayName("VKOSPI 경계: 17.99 → +1 / 18.0 → 0 / 24.99 → 0 / 25.0 → -1")
    void vkospiBoundary() {
        // vkospi +1 과 sox +1 → RISK_ON. vkospi 0 이면 sox 단독 +1 → NEUTRAL
        assertThat(MacroTiltService.classifyMacroRegime(17.99, null, 3.0)).isEqualTo("RISK_ON");
        assertThat(MacroTiltService.classifyMacroRegime(18.0, null, 3.0)).isEqualTo("NEUTRAL");
        // vkospi -1 과 sox -1 → RISK_OFF. vkospi 0 이면 sox 단독 -1 → NEUTRAL
        assertThat(MacroTiltService.classifyMacroRegime(25.0, null, -3.0)).isEqualTo("RISK_OFF");
        assertThat(MacroTiltService.classifyMacroRegime(24.99, null, -3.0)).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("금리 추세 경계: -15.0bp → +1 / -14.99bp → 0 / +15.0bp → -1")
    void rateBoundary() {
        assertThat(MacroTiltService.classifyMacroRegime(17.0, -15.0, null)).isEqualTo("RISK_ON");
        assertThat(MacroTiltService.classifyMacroRegime(17.0, -14.99, null)).isEqualTo("NEUTRAL");
        assertThat(MacroTiltService.classifyMacroRegime(25.5, 15.0, null)).isEqualTo("RISK_OFF");
        assertThat(MacroTiltService.classifyMacroRegime(25.5, 14.99, null)).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("SOX 추세 경계: +3.0% → +1 / +2.99% → 0 / -3.0% → -1")
    void soxBoundary() {
        assertThat(MacroTiltService.classifyMacroRegime(17.0, null, 3.0)).isEqualTo("RISK_ON");
        assertThat(MacroTiltService.classifyMacroRegime(17.0, null, 2.99)).isEqualTo("NEUTRAL");
        assertThat(MacroTiltService.classifyMacroRegime(26.0, null, -3.0)).isEqualTo("RISK_OFF");
        assertThat(MacroTiltService.classifyMacroRegime(26.0, null, -2.99)).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("합 +1/-1 은 NEUTRAL (±2 필요)")
    void singleVoteNeutral() {
        assertThat(MacroTiltService.classifyMacroRegime(15.0, null, null)).isEqualTo("NEUTRAL");
        assertThat(MacroTiltService.classifyMacroRegime(26.0, null, null)).isEqualTo("NEUTRAL");
        // 상충 투표: +1 -1 = 0
        assertThat(MacroTiltService.classifyMacroRegime(15.0, 20.0, null)).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("null 축은 투표 제외 — 잔여 2축 극단으로 RISK_ON/OFF 가능(금리 축 ECOS 키 발급 전 상태)")
    void nullAxisExcluded() {
        assertThat(MacroTiltService.classifyMacroRegime(15.0, null, 4.0)).isEqualTo("RISK_ON");
        assertThat(MacroTiltService.classifyMacroRegime(27.0, null, -4.0)).isEqualTo("RISK_OFF");
    }

    @Test
    @DisplayName("전부 null → NEUTRAL (데이터 없음 안전 기본값)")
    void allNullNeutral() {
        assertThat(MacroTiltService.classifyMacroRegime(null, null, null)).isEqualTo("NEUTRAL");
    }

    // ==================== computeSoxTrend (웜업/자체 축적) ====================

    private MacroTiltSnapshot snap(String date, Double soxLevel) {
        return MacroTiltSnapshot.builder()
                .snapshotDate(LocalDate.parse(date))
                .tilt("NEUTRAL")
                .soxLevel(soxLevel == null ? null : BigDecimal.valueOf(soxLevel))
                .build();
    }

    @Test
    @DisplayName("SOX 추세: 최근 7행 중 최고령 유효점 대비 % (asOf = 그 스냅샷 일자)")
    void soxTrendNormal() {
        List<MacroTiltSnapshot> desc = List.of(
                snap("2026-07-03", 5200.0), snap("2026-07-02", 5100.0), snap("2026-06-29", 5000.0));
        MacroTiltService.SoxTrend t =
                MacroTiltService.computeSoxTrend(5250.0, desc, LocalDate.parse("2026-07-06"));
        assertThat(t.pct()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.001));   // (5250-5000)/5000
        assertThat(t.asOf()).isEqualTo(LocalDate.parse("2026-06-29"));
    }

    @Test
    @DisplayName("과거 유효점 없음(콜드스타트 웜업) → null — 가짜 추세 생성 금지(§4c)")
    void soxTrendWarmup() {
        assertThat(MacroTiltService.computeSoxTrend(5250.0, List.of(), LocalDate.parse("2026-07-06")).pct()).isNull();
        // 레벨 null 행만 있으면 여전히 웜업
        assertThat(MacroTiltService.computeSoxTrend(5250.0,
                List.of(snap("2026-07-03", null)), LocalDate.parse("2026-07-06")).pct()).isNull();
    }

    @Test
    @DisplayName("당일 행(재실행 잔재)은 비교 대상에서 제외 / 오늘 레벨 null → null")
    void soxTrendEdge() {
        // 당일 행만 존재 → 과거점 없음 → null
        assertThat(MacroTiltService.computeSoxTrend(5250.0,
                List.of(snap("2026-07-06", 5240.0)), LocalDate.parse("2026-07-06")).pct()).isNull();
        assertThat(MacroTiltService.computeSoxTrend(null,
                List.of(snap("2026-07-03", 5200.0)), LocalDate.parse("2026-07-06")).pct()).isNull();
    }
}
