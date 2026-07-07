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
