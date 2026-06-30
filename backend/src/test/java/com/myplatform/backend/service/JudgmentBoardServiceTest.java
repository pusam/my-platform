package com.myplatform.backend.service;

import com.myplatform.backend.dto.JudgmentBoardDto.Row;
import com.myplatform.backend.service.RecommendationService.RecommendationDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종합 판단 보드(B안) 조립 순수 함수 테스트 — 산식 변경 없이 필드 매핑/신호 조인/역상관 의심 플래그.
 */
class JudgmentBoardServiceTest {

    private RecommendationDto dto(String code, int total, int tech, int earn, int supply, int sector) {
        return RecommendationDto.builder()
                .stockCode(code).stockName(code + "명")
                .totalScore(total).technical(tech).earnings(earn)
                .supplyDemand(supply).sectorMomentum(sector)
                .currentPrice(new BigDecimal("10000")).changeRate(new BigDecimal("1.5"))
                .tags(List.of("태그"))
                .build();
    }

    @Test
    @DisplayName("후보 → 행: 4카테고리 매핑 + 타이밍/섹터강도 조인 + 출처태그 momentum")
    void assembleRows_mapsAndJoins() {
        List<RecommendationDto> cands = List.of(
                dto("005930", 80, 16, 18, 15, 12),   // 수급 15 → 의심
                dto("000660", 70, 10, 0, 8, 6));     // 수급 8 → 의심 아님, 타이밍/섹터 없음
        Map<String, Integer> timing = Map.of("005930", 3);
        Map<String, String> stockSector = Map.of("005930", "반도체");
        Map<String, BigDecimal> sectorRel = Map.of("반도체", new BigDecimal("1.56"));

        List<Row> rows = JudgmentBoardService.assembleRows(cands, timing, stockSector, sectorRel);

        assertThat(rows).hasSize(2);
        Row a = rows.get(0);
        assertThat(a.getStockCode()).isEqualTo("005930");
        assertThat(a.getTotalScore()).isEqualTo(80);
        assertThat(a.getTechnical()).isEqualTo(16);
        assertThat(a.getSources()).containsExactly("momentum");
        assertThat(a.getTimingScore()).isEqualTo(3);                 // 타이밍 조인
        assertThat(a.getSector()).isEqualTo("반도체");
        assertThat(a.getSectorStrengthRel()).isEqualByComparingTo("1.56");
        assertThat(a.isSupplyInverseSuspect()).isTrue();             // 수급 15 ≥ 10

        Row b = rows.get(1);
        assertThat(b.getTimingScore()).isNull();                     // 타이밍 신호 없음
        assertThat(b.getSectorStrengthRel()).isNull();               // 섹터 매핑 없음
        assertThat(b.isSupplyInverseSuspect()).isFalse();            // 수급 8 < 10
    }

    @Test
    @DisplayName("역상관 의심 경계 — 수급 10=의심 / 9=아님 (표본 작음, 확정 아님)")
    void supplyInverseSuspect_boundary() {
        var rows = JudgmentBoardService.assembleRows(
                List.of(dto("A", 60, 5, 0, 10, 4), dto("B", 60, 5, 0, 9, 4)),
                Map.of(), Map.of(), Map.of());
        assertThat(rows.get(0).isSupplyInverseSuspect()).isTrue();
        assertThat(rows.get(1).isSupplyInverseSuspect()).isFalse();
    }

    @Test
    @DisplayName("parseSectorRel: ranked[{sector,rel_strength}] → 맵, 결측 skip / null → 빈 맵")
    void parseSectorRel_works() {
        Map<String, Object> ss = Map.of("ranked", List.of(
                Map.of("sector", "반도체", "rel_strength", "1.56"),
                Map.of("sector", "2차전지", "rel_strength", "-0.80"),
                Map.of("sector", "결측")));            // rel_strength 없음 → skip
        Map<String, BigDecimal> rel = JudgmentBoardService.parseSectorRel(ss);
        assertThat(rel).hasSize(2);
        assertThat(rel.get("반도체")).isEqualByComparingTo("1.56");
        assertThat(rel.get("2차전지")).isEqualByComparingTo("-0.80");

        assertThat(JudgmentBoardService.parseSectorRel(null)).isEmpty();
    }
}
