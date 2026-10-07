package com.myplatform.backend.service;

import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.entity.ManualTradeJournal;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.ManualTradeJournalRepository;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.StockCatalystRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수동 매매 저널 — 같은 매수를 두 번 저장하지 않고, 이름이 비면 종목 마스터로 보인다(2026-10-07 화면 점검).
 *
 * <p>재현: 저널에 '07.08 10:28 · 003490 · 28,000×130' 이 똑같이 두 줄이었다 — 같은 매수가 두 번 저장돼 통계(기록 2건·적중률
 * 분모)가 두 배가 됐다(매수 기록은 종목 진단까지 돌아 느려 다시 누르기 쉽다). 종목명도 비어 코드만 보였다(그때 KIS 가 이름을 주지
 * 않았다 — 9/22 마스터 폴백 이전).
 */
class ManualJournalDuplicateNameTest {

    private static ManualTradeJournal row(LocalDateTime buyAt, String price, String qty) {
        return ManualTradeJournal.builder().username("u").stockCode("003490")
                .buyAt(buyAt).buyPrice(new BigDecimal(price)).quantity(qty == null ? null : new BigDecimal(qty)).build();
    }

    @Test
    @DisplayName("60초 안의 같은 종목·가격·수량 열린 기록은 중복 — 수량·가격이 다르거나, 매도됐거나, 60초가 지났으면 아니다")
    void recentDuplicateRule() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 8, 10, 28, 30);
        ManualTradeJournal first = row(now.minusSeconds(10), "28000", "130");
        assertThat(ManualTradeJournalService.recentDuplicate(List.of(first), new BigDecimal("28000.00"), new BigDecimal("130"), now))
                .containsSame(first);

        assertThat(ManualTradeJournalService.recentDuplicate(List.of(first), new BigDecimal("28000"), new BigDecimal("100"), now)).isEmpty();
        assertThat(ManualTradeJournalService.recentDuplicate(List.of(first), new BigDecimal("27950"), new BigDecimal("130"), now)).isEmpty();
        assertThat(ManualTradeJournalService.recentDuplicate(List.of(row(now.minusSeconds(61), "28000", "130")),
                new BigDecimal("28000"), new BigDecimal("130"), now)).isEmpty();
        ManualTradeJournal sold = row(now.minusSeconds(10), "28000", "130");
        sold.setSellAt(now.minusSeconds(5));
        assertThat(ManualTradeJournalService.recentDuplicate(List.of(sold), new BigDecimal("28000"), new BigDecimal("130"), now)).isEmpty();
        // 수량을 안 적은 두 기록도 같은 것으로 본다
        assertThat(ManualTradeJournalService.recentDuplicate(List.of(row(now.minusSeconds(3), "28000", null)),
                new BigDecimal("28000"), null, now)).isPresent();
    }

    @SuppressWarnings("unchecked")
    private static ManualTradeJournalService service(ManualTradeJournalRepository repo, StockMasterService master) {
        ObjectProvider<StockMasterService> masterProvider = mock(ObjectProvider.class);
        when(masterProvider.getIfAvailable()).thenReturn(master);
        return new ManualTradeJournalService(repo, mock(RecommendationSnapshotRepository.class),
                mock(StockCatalystRepository.class), mock(StockPriceHistoryRepository.class), mock(RvolService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(StockPriceService.class),
                mock(ObjectProvider.class), mock(SectorStockConfig.class), mock(BotTradingPositionRepository.class),
                mock(ObjectProvider.class), masterProvider);
    }

    @Test
    @DisplayName("재현: 방금 저장한 매수를 다시 보내면 새 행을 만들지 않고 그 기록을 돌려준다")
    void doubleSubmitReturnsExisting() {
        ManualTradeJournalRepository repo = mock(ManualTradeJournalRepository.class);
        ManualTradeJournal first = row(LocalDateTime.now().minusSeconds(5), "28000", "130");
        when(repo.findByUsernameAndStockCodeOrderByBuyAtDesc("u", "003490")).thenReturn(List.of(first));

        ManualTradeJournal r = service(repo, mock(StockMasterService.class))
                .recordBuy("u", "003490", null, new BigDecimal("28000"), new BigDecimal("130"), null);

        assertThat(r).isSameAs(first);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("재현: 이름 없이 저장된 기록은 종목 마스터 이름으로 보인다 — 마스터에도 없으면 그대로")
    void missingNameFromMaster() {
        ManualTradeJournalRepository repo = mock(ManualTradeJournalRepository.class);
        ManualTradeJournal noName = row(LocalDateTime.now().minusDays(90), "28000", "130");
        ManualTradeJournal unknown = ManualTradeJournal.builder().username("u").stockCode("999999")
                .buyAt(LocalDateTime.now().minusDays(1)).buyPrice(BigDecimal.TEN).build();
        when(repo.findByUsernameOrderByBuyAtDesc("u")).thenReturn(List.of(noName, unknown));
        StockMasterService master = mock(StockMasterService.class);
        when(master.getName("003490")).thenReturn("대한항공");

        List<ManualTradeJournal> rows = service(repo, master).list("u");

        assertThat(rows.get(0).getStockName()).isEqualTo("대한항공");
        assertThat(rows.get(1).getStockName()).isNull();
        verify(repo, never()).save(any());
    }
}
