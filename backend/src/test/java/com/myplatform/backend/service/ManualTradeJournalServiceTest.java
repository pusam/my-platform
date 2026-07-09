package com.myplatform.backend.service;

import com.myplatform.backend.entity.ManualTradeJournal;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.entity.StockCatalyst;
import com.myplatform.backend.entity.StockPriceHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수동 저널 스냅샷 조립 순수 함수 테스트 — 각 소스 null = 해당 필드 null(§4c), 기록 자체는 성공.
 */
class ManualTradeJournalServiceTest {

    @Test
    @DisplayName("assembleSnapshot: 전 소스 존재 → 12필드 매핑, 재료 NONE 은 null 정규화")
    void assemble_full() {
        RecommendationSnapshot snap = new RecommendationSnapshot();
        snap.setTotalScore(72); snap.setEarnings(15); snap.setSupplyDemand(8);
        snap.setTechnical(14); snap.setSectorMomentum(12);
        StockCatalyst cat = StockCatalyst.builder()
                .catalystType(StockCatalyst.CatalystType.ORDER_WIN)
                .direction(StockCatalyst.Direction.POSITIVE).build();

        ManualTradeJournal j = new ManualTradeJournal();
        ManualTradeJournalService.assembleSnapshot(j, snap, cat,
                new BigDecimal("68.5"), new BigDecimal("2.30"), "BULL",
                new BigDecimal("-2.5"), new BigDecimal("7.10"));

        assertThat(j.getTotalScore()).isEqualTo(72);
        assertThat(j.getTechnical()).isEqualTo(14);
        assertThat(j.getCatalystType()).isEqualTo("ORDER_WIN");
        assertThat(j.getCatalystDirection()).isEqualTo("POSITIVE");
        assertThat(j.getRsi()).isEqualByComparingTo("68.5");
        assertThat(j.getRvol()).isEqualByComparingTo("2.30");
        assertThat(j.getRegime()).isEqualTo("BULL");
        assertThat(j.getAtrStopPct()).isEqualByComparingTo("-2.5");
        assertThat(j.getFiveDayReturn()).isEqualByComparingTo("7.10");

        // 재료 NONE → null (미표시)
        ManualTradeJournal j2 = new ManualTradeJournal();
        ManualTradeJournalService.assembleSnapshot(j2, null,
                StockCatalyst.builder().catalystType(StockCatalyst.CatalystType.NONE)
                        .direction(StockCatalyst.Direction.NONE).build(),
                null, null, null, null, null);
        assertThat(j2.getCatalystType()).isNull();
    }

    @Test
    @DisplayName("assembleSnapshot: 전 소스 null → 전 필드 null(§4c), 예외 없음")
    void assemble_allNull() {
        ManualTradeJournal j = new ManualTradeJournal();
        ManualTradeJournalService.assembleSnapshot(j, null, null, null, null, null, null, null);
        assertThat(j.getTotalScore()).isNull();
        assertThat(j.getCatalystType()).isNull();
        assertThat(j.getRsi()).isNull();
        assertThat(j.getRegime()).isNull();
        assertThat(j.getFiveDayReturn()).isNull();
    }

    private List<StockPriceHistory> closes(String... vals) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (String v : vals) rows.add(StockPriceHistory.builder().closePrice(new BigDecimal(v)).build());
        return rows;
    }

    @Test
    @DisplayName("fiveDayReturn: 6개 종가 → (0번-5번)/5번×100, 5개 이하/결측 → null")
    void fiveDay() {
        // 최신 110, 5거래일 전 100 → +10.00%
        assertThat(ManualTradeJournalService.fiveDayReturn(
                closes("110", "108", "105", "103", "101", "100"))).isEqualByComparingTo("10.00");
        assertThat(ManualTradeJournalService.fiveDayReturn(closes("110", "108"))).isNull();
        assertThat(ManualTradeJournalService.fiveDayReturn(null)).isNull();
    }

    // ==================== Phase 2 — 평가/통계 순수 함수 ====================

    @Test
    @DisplayName("evaluate: pct·alpha 산출 + hit=alpha≥0 AND pct>0 (signal_outcome 동일 잣대)")
    void evaluate_withBenchmark() {
        // 종목 +5.00%, KOSPI +1.00% → alpha +4.00, hit
        var ev = ManualTradeJournalService.evaluate(
                new BigDecimal("10000"), new BigDecimal("10500"),
                new BigDecimal("2500.00"), new BigDecimal("2525.00"));
        assertThat(ev.pctChange()).isEqualByComparingTo("5.00");
        assertThat(ev.alpha()).isEqualByComparingTo("4.00");
        assertThat(ev.hit()).isTrue();

        // 종목 +1.00% 이나 KOSPI +5.00% → alpha 음수 → miss (절대수익 양수여도)
        var underperform = ManualTradeJournalService.evaluate(
                new BigDecimal("10000"), new BigDecimal("10100"),
                new BigDecimal("2500.00"), new BigDecimal("2625.00"));
        assertThat(underperform.alpha()).isEqualByComparingTo("-4.00");
        assertThat(underperform.hit()).isFalse();
    }

    @Test
    @DisplayName("evaluate: scale 4 정밀도 — 미세 경계(pct 0.004%)가 scale2 처럼 0 으로 뭉개지지 않음 (AUDIT #2 동일 잣대)")
    void evaluate_scale4Boundary() {
        // 종목 +0.004%(10000→10000.40), KOSPI 0% → scale2 면 pct=0.00(miss), scale4 면 pct=0.0040>0(hit)
        var ev = ManualTradeJournalService.evaluate(
                new BigDecimal("10000"), new BigDecimal("10000.40"),
                new BigDecimal("2500.00"), new BigDecimal("2500.00"));
        assertThat(ev.pctChange()).isEqualByComparingTo("0.0040");   // scale4 유지 — 0 으로 반올림 안 됨
        assertThat(ev.alpha()).isEqualByComparingTo("0.0040");       // alpha = pct - 0
        assertThat(ev.hit()).isTrue();   // alpha≥0 AND pct>0 — signal_outcome(scale4)와 동일 판정
    }

    @Test
    @DisplayName("evaluate: BM 결측 → alpha null + pct≥+3% 폴백, 가격 결측 → null(재시도)")
    void evaluate_fallbackAndMissing() {
        var fallbackHit = ManualTradeJournalService.evaluate(
                new BigDecimal("10000"), new BigDecimal("10300"), null, null);
        assertThat(fallbackHit.alpha()).isNull();
        assertThat(fallbackHit.hit()).isTrue();     // +3.00% ≥ 3%

        var fallbackMiss = ManualTradeJournalService.evaluate(
                new BigDecimal("10000"), new BigDecimal("10200"), null, null);
        assertThat(fallbackMiss.hit()).isFalse();   // +2.00% < 3%

        assertThat(ManualTradeJournalService.evaluate(
                new BigDecimal("10000"), null, null, null)).isNull();
        assertThat(ManualTradeJournalService.evaluate(
                null, new BigDecimal("10000"), null, null)).isNull();
    }

    private ManualTradeJournal journal(Boolean hit, String alpha, String pct,
                                       String rsi, String catalystType, String realizedPct) {
        return ManualTradeJournal.builder()
                .buyAt(java.time.LocalDateTime.now())
                .buyPrice(new BigDecimal("10000"))
                .evaluatedAt(hit == null ? null : java.time.LocalDateTime.now())
                .hit(hit)
                .alpha3d(alpha == null ? null : new BigDecimal(alpha))
                .pctChange3d(pct == null ? null : new BigDecimal(pct))
                .rsi(rsi == null ? null : new BigDecimal(rsi))
                .catalystType(catalystType)
                .sellAt(realizedPct == null ? null : java.time.LocalDateTime.now())
                .realizedPct(realizedPct == null ? null : new BigDecimal(realizedPct))
                .build();
    }

    @Test
    @DisplayName("computeStats: 적중률·평균 alpha·실현 승률 + RSI/재료 breakdown, n<10 → insufficientSample")
    void computeStats_mixed() {
        List<ManualTradeJournal> rows = List.of(
                journal(true, "4.00", "5.00", "72", "ORDER_WIN", "5.00"),   // 평가hit·RSI과열·재료·실현+
                journal(false, "-2.00", "1.00", "55", null, "-3.00"),       // 평가miss·RSI정상·실현-
                journal(null, null, null, null, null, null));               // 평가 대기·RSI 미수집·보유중

        var stats = ManualTradeJournalService.computeStats(rows);
        assertThat(stats.getTotalTrades()).isEqualTo(3);
        assertThat(stats.getOpenTrades()).isEqualTo(1);
        assertThat(stats.getClosedTrades()).isEqualTo(2);
        assertThat(stats.getEvaluatedTrades()).isEqualTo(2);
        assertThat(stats.getHitCount()).isEqualTo(1);
        assertThat(stats.getHitRate()).isEqualByComparingTo("50.00");
        assertThat(stats.getAvgAlpha3d()).isEqualByComparingTo("1.00");    // (4-2)/2
        assertThat(stats.getRealizedTrades()).isEqualTo(2);
        assertThat(stats.getRealizedWinCount()).isEqualTo(1);
        assertThat(stats.getRealizedWinRate()).isEqualByComparingTo("50.00");
        assertThat(stats.getAvgRealizedPct()).isEqualByComparingTo("1.00");
        assertThat(stats.isInsufficientSample()).isTrue();                  // 평가 2건 < 10

        // breakdown — RSI 미수집(null)은 과열/정상 어느 bucket 에도 미포함(§4c)
        var byKey = stats.getBreakdowns().stream()
                .collect(java.util.stream.Collectors.toMap(b -> b.getKey(), b -> b));
        assertThat(byKey.get("rsiOverbought").getTotalTrades()).isEqualTo(1);
        assertThat(byKey.get("rsiOverbought").getHitRate()).isEqualByComparingTo("100.00");
        assertThat(byKey.get("rsiNormal").getTotalTrades()).isEqualTo(1);
        assertThat(byKey.get("catalystPresent").getTotalTrades()).isEqualTo(1);
        assertThat(byKey.get("catalystAbsent").getTotalTrades()).isEqualTo(2);
        assertThat(byKey.get("rsiOverbought").isInsufficientSample()).isTrue();
    }

    @Test
    @DisplayName("computeStats: 표본 0건 → 비율/평균 전부 null(§4c, 0% 위장 금지)")
    void computeStats_empty() {
        var stats = ManualTradeJournalService.computeStats(List.of());
        assertThat(stats.getTotalTrades()).isZero();
        assertThat(stats.getHitRate()).isNull();
        assertThat(stats.getAvgAlpha3d()).isNull();
        assertThat(stats.getRealizedWinRate()).isNull();
        assertThat(stats.getAvgRealizedPct()).isNull();
        assertThat(stats.isInsufficientSample()).isTrue();
        assertThat(ManualTradeJournalService.computeStats(null).getTotalTrades()).isZero();
    }

    @Test
    @DisplayName("computeSectorExposure: 동일 섹터 보유 집계(저널 우선 dedup), 매핑 밖 → mapped=false")
    void sectorExposure() {
        var semis = new com.myplatform.backend.config.SectorStockConfig.SectorInfo(
                "SEMI", "반도체", "#000", List.of("005930", "000660", "042700"));
        var bio = new com.myplatform.backend.config.SectorStockConfig.SectorInfo(
                "BIO", "바이오", "#000", List.of("068270"));

        List<ManualTradeJournal> openJournals = List.of(
                ManualTradeJournal.builder().stockCode("000660").stockName("SK하이닉스").build());
        List<com.myplatform.backend.entity.BotTradingPosition> botPositions = List.of(
                com.myplatform.backend.entity.BotTradingPosition.builder()
                        .stockCode("000660").stockName("SK하이닉스").build(),   // 저널과 중복 → JOURNAL 우선
                com.myplatform.backend.entity.BotTradingPosition.builder()
                        .stockCode("042700").stockName("한미반도체").build(),
                com.myplatform.backend.entity.BotTradingPosition.builder()
                        .stockCode("068270").stockName("셀트리온").build());    // 다른 섹터 — 제외

        var dto = ManualTradeJournalService.computeSectorExposure(
                "005930", List.of(semis, bio), openJournals, botPositions);
        assertThat(dto.isMapped()).isTrue();
        assertThat(dto.getSectors()).hasSize(1);
        var block = dto.getSectors().get(0);
        assertThat(block.getSectorCode()).isEqualTo("SEMI");
        assertThat(block.getCount()).isEqualTo(2);   // 000660(dedup) + 042700
        assertThat(block.getHoldings()).extracting("stockCode", "source")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("000660", "JOURNAL"),
                        org.assertj.core.groups.Tuple.tuple("042700", "BOT"));

        // 매핑 밖 종목 → mapped=false(§4c — 프론트 미표시)
        var unmapped = ManualTradeJournalService.computeSectorExposure(
                "999999", List.of(semis, bio), openJournals, botPositions);
        assertThat(unmapped.isMapped()).isFalse();
        assertThat(unmapped.getSectors()).isEmpty();
    }

    @Test
    @DisplayName("realizedPct: (매도-매수)/매수×100, 매수 0/null → null")
    void realized() {
        assertThat(ManualTradeJournalService.realizedPct(
                new BigDecimal("10000"), new BigDecimal("10500"))).isEqualByComparingTo("5.00");
        assertThat(ManualTradeJournalService.realizedPct(
                new BigDecimal("10000"), new BigDecimal("9700"))).isEqualByComparingTo("-3.00");
        assertThat(ManualTradeJournalService.realizedPct(null, new BigDecimal("1"))).isNull();
        assertThat(ManualTradeJournalService.realizedPct(BigDecimal.ZERO, new BigDecimal("1"))).isNull();
    }
}
