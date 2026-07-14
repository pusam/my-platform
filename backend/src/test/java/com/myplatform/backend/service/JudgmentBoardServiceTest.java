package com.myplatform.backend.service;

import com.myplatform.backend.dto.JudgmentBoardDto.Row;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockCatalyst;
import com.myplatform.backend.entity.StockCatalyst.CatalystType;
import com.myplatform.backend.entity.StockCatalyst.Direction;
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
        assertThat(a.isScored()).isTrue();                           // momentum 행은 4-cat 있음

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

    private Row rowOf(String code) {
        return Row.builder().stockCode(code).stockName(code + "명").build();
    }

    private StockCatalyst cat(String code, CatalystType type, Direction dir, java.time.LocalDate date) {
        return StockCatalyst.builder().stockCode(code).catalystType(type).direction(dir).catalystDate(date).build();
    }

    @Test
    @DisplayName("재료 배지 매핑 — 호재/악재 라벨+방향+경과일(오늘=0/어제=1), NONE/미캐시 생략(§4b/§4c)")
    void applyCatalyst_mapsAgeAndSkips() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 7, 2);
        Row a = rowOf("005930");   // 오늘 호재
        Row b = rowOf("000660");   // NONE → 생략
        Row c = rowOf("035420");   // 캐시 없음 → 생략
        Row d = rowOf("298040");   // 어제 재료(경과 1)
        Map<String, StockCatalyst> catMap = Map.of(
                "005930", cat("005930", CatalystType.ORDER_WIN, Direction.POSITIVE, today),
                "000660", cat("000660", CatalystType.NONE, Direction.NONE, today),
                "298040", cat("298040", CatalystType.OTHER, Direction.POSITIVE, today.minusDays(1)));

        JudgmentBoardService.applyCatalyst(List.of(a, b, c, d), catMap, today);

        assertThat(a.getCatalystLabel()).isEqualTo("수주");
        assertThat(a.getCatalystType()).isEqualTo("ORDER_WIN");
        assertThat(a.getCatalystDirection()).isEqualTo("POSITIVE");
        assertThat(a.getCatalystAgeDays()).isEqualTo(0);    // 오늘
        assertThat(b.getCatalystLabel()).isNull();          // NONE 생략
        assertThat(c.getCatalystLabel()).isNull();          // 미캐시 생략
        assertThat(d.getCatalystLabel()).isEqualTo("기타");
        assertThat(d.getCatalystAgeDays()).isEqualTo(1);    // 어제
    }

    @Test
    @DisplayName("pickLatestMeaningful: 오늘 NONE 이 어제 실재료 안 가림 / 오늘 real 최신 / 둘 다 NONE 무배지")
    void pickLatestMeaningful_todayNoneKeepsYesterdayReal() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 7, 2);
        var m = JudgmentBoardService.pickLatestMeaningful(List.of(
                cat("298040", CatalystType.ORDER_WIN, Direction.POSITIVE, today.minusDays(1)),  // 어제 실재료
                cat("298040", CatalystType.NONE, Direction.NONE, today),                         // 오늘 NONE — 가리면 안 됨
                cat("005930", CatalystType.ORDER_WIN, Direction.POSITIVE, today.minusDays(1)),   // 어제
                cat("005930", CatalystType.EARNINGS, Direction.POSITIVE, today),                 // 오늘 real → 최신
                cat("035420", CatalystType.NONE, Direction.NONE, today),                         // 둘 다 NONE
                cat("035420", CatalystType.NONE, Direction.NONE, today.minusDays(1))));

        // 298040: 오늘 NONE 무시 → 어제 ORDER_WIN 유지(2일 백업 취지)
        assertThat(m.get("298040").getCatalystType()).isEqualTo(CatalystType.ORDER_WIN);
        assertThat(m.get("298040").getCatalystDate()).isEqualTo(today.minusDays(1));
        // 005930: 오늘 real 이 최신
        assertThat(m.get("005930").getCatalystType()).isEqualTo(CatalystType.EARNINGS);
        assertThat(m.get("005930").getCatalystDate()).isEqualTo(today);
        // 035420: 둘 다 NONE → 없음
        assertThat(m).doesNotContainKey("035420");
    }

    @Test
    @DisplayName("거래대금 — 실측 누적 우선, 없으면 현재가×거래량 폴백, 둘 다 없으면 null(§4c)")
    void resolveTradingValue_fallback() {
        StockPriceDto acc = new StockPriceDto();            // 실측 누적
        acc.setAccumulatedTradingValue(new BigDecimal("50000000000"));
        acc.setCurrentPrice(new BigDecimal("70000"));
        acc.setVolume(new BigDecimal("1000"));
        assertThat(JudgmentBoardService.resolveTradingValue(acc)).isEqualByComparingTo("50000000000");

        StockPriceDto calc = new StockPriceDto();           // 폴백: 현재가×거래량
        calc.setCurrentPrice(new BigDecimal("70000"));
        calc.setVolume(new BigDecimal("1000"));
        assertThat(JudgmentBoardService.resolveTradingValue(calc)).isEqualByComparingTo("70000000");

        StockPriceDto empty = new StockPriceDto();          // 둘 다 없음 → null(임시값 금지)
        assertThat(JudgmentBoardService.resolveTradingValue(empty)).isNull();
        assertThat(JudgmentBoardService.resolveTradingValue(null)).isNull();
    }

    @Test
    @DisplayName("신호 이력 집계 매핑 — [code,total,hit,avgAlpha] → TrackRecord(scale2), 0건/결측 skip")
    void toTrackRecordMap_mapsAndSkips() {
        List<Object[]> agg = List.<Object[]>of(
                new Object[]{"005930", 5L, 3L, new BigDecimal("1.2345")},
                new Object[]{"000660", 2L, 0L, null},                       // alpha 미산출 → null 유지(§4c)
                new Object[]{"035420", 0L, 0L, null},                       // 0건 → skip
                new Object[]{null, 3L, 1L, null});                          // 코드 결측 → skip

        var m = JudgmentBoardService.toTrackRecordMap(agg);

        assertThat(m).hasSize(2);
        assertThat(m.get("005930").count()).isEqualTo(5);
        assertThat(m.get("005930").hitCount()).isEqualTo(3);
        assertThat(m.get("005930").avgAlpha()).isEqualByComparingTo("1.23");
        assertThat(m.get("000660").avgAlpha()).isNull();

        assertThat(JudgmentBoardService.toTrackRecordMap(null)).isEmpty();
    }

    @Test
    @DisplayName("신호 이력 매핑(② 참고) — 이력 있는 종목만 세팅, 없는 종목은 null 유지(§4c)")
    void applyTrackRecord_setsOnlyMatched() {
        Row a = rowOf("005930");
        Row b = rowOf("035420");
        JudgmentBoardService.applyTrackRecord(List.of(a, b), Map.of(
                "005930", new JudgmentBoardService.TrackRecord(5, 3, new BigDecimal("1.20"))));

        assertThat(a.getTrackCount()).isEqualTo(5);
        assertThat(a.getTrackHitCount()).isEqualTo(3);
        assertThat(a.getTrackAvgAlpha()).isEqualByComparingTo("1.20");
        assertThat(b.getTrackCount()).isNull();
        assertThat(b.getTrackHitCount()).isNull();
        assertThat(b.getTrackAvgAlpha()).isNull();

        // 빈/누락 맵은 no-op
        JudgmentBoardService.applyTrackRecord(List.of(b), Map.of());
        JudgmentBoardService.applyTrackRecord(List.of(b), null);
        assertThat(b.getTrackCount()).isNull();
    }

    @Test
    @DisplayName("RVOL(V41 ② 참고) — 산출 종목만 세팅, 미산출은 null 유지(§4c)")
    void applyRvol_setsOnlyComputed() {
        Row a = Row.builder().stockCode("005930").build();
        Row b = Row.builder().stockCode("035420").build();
        JudgmentBoardService.applyRvol(List.of(a, b), Map.of("005930", new BigDecimal("3.20")));
        assertThat(a.getRvol()).isEqualByComparingTo("3.20");
        assertThat(b.getRvol()).isNull();

        // 빈/누락 맵은 no-op
        JudgmentBoardService.applyRvol(List.of(b), Map.of());
        JudgmentBoardService.applyRvol(List.of(b), null);
        assertThat(b.getRvol()).isNull();
    }

    @Test
    @DisplayName("추세채널(② 참고) — 히스토리(최신순)→코드별 채널, 10거래일 미만·가격 결측 코드는 null 유지(§4c)")
    void computeChannels_andApply() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 7, 14);
        java.util.List<com.myplatform.backend.entity.StockPriceHistory> history = new java.util.ArrayList<>();
        // A: 30거래일 상승 추세 (repo 정렬대로 최신→과거 순 적재)
        for (int i = 0; i < 30; i++) {
            double close = 130 - i;   // 최신 130 → 과거 101
            history.add(com.myplatform.backend.entity.StockPriceHistory.builder()
                    .stockCode("A").tradeDate(today.minusDays(i))
                    .highPrice(BigDecimal.valueOf(close + 1)).lowPrice(BigDecimal.valueOf(close - 1))
                    .closePrice(BigDecimal.valueOf(close)).build());
        }
        // B: 5거래일뿐 → 채널 미산출
        for (int i = 0; i < 5; i++) {
            history.add(com.myplatform.backend.entity.StockPriceHistory.builder()
                    .stockCode("B").tradeDate(today.minusDays(i))
                    .highPrice(BigDecimal.valueOf(101)).lowPrice(BigDecimal.valueOf(99))
                    .closePrice(BigDecimal.valueOf(100)).build());
        }

        var channels = JudgmentBoardService.computeChannels(history);
        assertThat(channels).containsKey("A").doesNotContainKey("B");
        assertThat(channels.get("A").direction()).isEqualTo("UP");

        Row a = Row.builder().stockCode("A").build();
        Row b = Row.builder().stockCode("B").build();
        JudgmentBoardService.applyTrendChannel(List.of(a, b), channels);
        assertThat(a.getChannelDirection()).isEqualTo("UP");
        assertThat(a.getChannelPositionPct()).isBetween(0, 100);
        assertThat(a.getChannelSlopePctPerDay()).isNotNull();
        assertThat(b.getChannelDirection()).isNull();   // 미산출 = null (§4c)
        assertThat(b.getChannelPositionPct()).isNull();

        // 빈/누락 맵은 no-op
        JudgmentBoardService.applyTrendChannel(List.of(b), Map.of());
        JudgmentBoardService.applyTrendChannel(List.of(b), null);
        assertThat(b.getChannelDirection()).isNull();
    }
}
