package com.myplatform.backend.service;

import com.myplatform.backend.entity.ManualTradeJournal;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.entity.StockCatalyst;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.ManualTradeJournalRepository;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.StockCatalystRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.util.AtrCalculator;
import com.myplatform.backend.util.AtrExitRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 수동 매매 저널 (V43) — 매수/매도 기록 + 매수 시점 신호 스냅샷. <b>봇/주문 경로와 완전 분리</b>(수동 전용).
 *
 * <p>스냅샷 조립은 순수 함수 {@link #assembleSnapshot}(테스트 대상) — 서비스는 각 소스를 best-effort 로
 * 조회만 하고(실패=null §4c, 기록 자체는 항상 성공), 조립은 이미 조회된 값으로만 한다.
 * 재료는 일캐시 <b>read-only</b>(classify 호출 없음 — §4b). 시세/RVOL 은 기존 단일 경로 재사용.
 * <p>v1: 매도는 전량 가정(부분매도 스코프 밖).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManualTradeJournalService {

    private final ManualTradeJournalRepository journalRepository;
    private final RecommendationSnapshotRepository snapshotRepository;
    private final StockCatalystRepository catalystRepository;
    private final StockPriceHistoryRepository priceHistoryRepository;
    private final RvolService rvolService;
    private final ObjectProvider<MarketRegimeClient> regimeProvider;
    private final ObjectProvider<StockAnalysisService> analysisProvider;

    /** ATR14 일봉 로드 행 수 — StockConclusionService 와 동일. */
    private static final int ATR_HISTORY_ROWS = 40;

    /** 매수 기록 — 스냅샷 자동 채움(각 소스 실패=해당 필드 null, 저장은 항상 성공). */
    @Transactional
    public ManualTradeJournal recordBuy(String username, String stockCode, String stockName,
                                        BigDecimal buyPrice, BigDecimal quantity, String memo) {
        ManualTradeJournal j = ManualTradeJournal.builder()
                .username(username).stockCode(stockCode).stockName(stockName)
                .buyAt(LocalDateTime.now()).buyPrice(buyPrice).quantity(quantity).memo(memo)
                .build();

        // ---- 소스별 best-effort 조회 (실패 = null 인자) ----
        RecommendationSnapshot snap = quiet(() ->
                snapshotRepository.findLatestByStockCode(stockCode).orElse(null), "추천스냅샷", stockCode);
        StockCatalyst catalyst = quiet(() ->
                catalystRepository.findByStockCodeAndCatalystDate(stockCode, LocalDate.now()).orElse(null),
                "재료(read-only)", stockCode);
        BigDecimal rvol = quiet(() -> rvolService.getRvolQuiet(stockCode), "RVOL", stockCode);
        String regime = quiet(() -> {
            MarketRegimeClient rc = regimeProvider.getIfAvailable();
            return rc == null ? null : rc.getCurrentRegimeQuiet();
        }, "국면", stockCode);
        BigDecimal rsi = quiet(() -> {
            StockAnalysisService a = analysisProvider.getIfAvailable();
            if (a == null) return null;
            var diag = a.diagnose(stockCode);   // heavy 하나 사용자 단발 액션이라 허용(핸드오프 결정)
            return diag != null && diag.getTechnicalAnalysis() != null
                    ? diag.getTechnicalAnalysis().getRsi14() : null;
        }, "RSI", stockCode);
        List<StockPriceHistory> history = quiet(() ->
                priceHistoryRepository.findByStockCodeOrderByTradeDateDesc(
                        stockCode, PageRequest.of(0, ATR_HISTORY_ROWS)), "일봉", stockCode);
        BigDecimal atrStopPct = quiet(() -> {
            if (history == null || buyPrice == null) return null;
            AtrExitRule.Levels lv = AtrExitRule.judge(buyPrice, AtrCalculator.atr14(history));
            return lv == null ? null : lv.stopPct().setScale(1, RoundingMode.HALF_UP);
        }, "ATR", stockCode);

        assembleSnapshot(j, snap, catalyst, rsi, rvol, regime, atrStopPct, fiveDayReturn(history));
        return journalRepository.save(j);
    }

    /** 매도 기록(전량 가정) — realizedPct 확정. 소유 불일치/미존재 = empty. 이미 매도된 건 재매도 불가. */
    @Transactional
    public Optional<ManualTradeJournal> recordSell(String username, Long id,
                                                   BigDecimal sellPrice, LocalDateTime sellAt) {
        Optional<ManualTradeJournal> opt = journalRepository.findByIdAndUsername(id, username);
        if (opt.isEmpty()) return Optional.empty();
        ManualTradeJournal j = opt.get();
        if (j.getSellAt() != null) return Optional.empty();   // 이미 매도 확정 — 덮어쓰기 방지
        if (sellPrice == null || sellPrice.signum() <= 0) return Optional.empty();
        j.setSellAt(sellAt != null ? sellAt : LocalDateTime.now());
        j.setSellPrice(sellPrice);
        j.setRealizedPct(realizedPct(j.getBuyPrice(), sellPrice));
        return Optional.of(journalRepository.save(j));
    }

    @Transactional(readOnly = true)
    public List<ManualTradeJournal> list(String username) {
        return journalRepository.findByUsernameOrderByBuyAtDesc(username);
    }

    @Transactional(readOnly = true)
    public Optional<ManualTradeJournal> get(String username, Long id) {
        return journalRepository.findByIdAndUsername(id, username);
    }

    // ==================== 순수 함수 (테스트 대상) ====================

    /**
     * 스냅샷 조립 — <b>순수</b>: 이미 조회된 값만 엔티티에 매핑. null 인자 = 해당 필드 미수집(§4c).
     * 재료 NONE(재료 없음)은 null 정규화(배지/집계에서 '없음' = 미표시).
     */
    static void assembleSnapshot(ManualTradeJournal j, RecommendationSnapshot snap, StockCatalyst catalyst,
                                 BigDecimal rsi, BigDecimal rvol, String regime,
                                 BigDecimal atrStopPct, BigDecimal fiveDayReturn) {
        if (snap != null) {
            j.setTotalScore(snap.getTotalScore());
            j.setEarnings(snap.getEarnings());
            j.setSupplyDemand(snap.getSupplyDemand());
            j.setTechnical(snap.getTechnical());
            j.setSectorMomentum(snap.getSectorMomentum());
        }
        if (catalyst != null && catalyst.getCatalystType() != null
                && catalyst.getCatalystType() != StockCatalyst.CatalystType.NONE) {
            j.setCatalystType(catalyst.getCatalystType().name());
            j.setCatalystDirection(catalyst.getDirection() == null ? null : catalyst.getDirection().name());
        }
        j.setRsi(rsi);
        j.setRvol(rvol);
        j.setRegime(regime);
        j.setAtrStopPct(atrStopPct);
        j.setFiveDayReturn(fiveDayReturn);
    }

    /** 5거래일 누적 등락률 %(최신순 일봉 6개 필요) — 부족/결측 = null(§4c). 순수. */
    static BigDecimal fiveDayReturn(List<StockPriceHistory> history) {
        if (history == null || history.size() < 6) return null;
        BigDecimal now = history.get(0) == null ? null : history.get(0).getClosePrice();
        BigDecimal ago = history.get(5) == null ? null : history.get(5).getClosePrice();
        if (now == null || ago == null || ago.signum() <= 0) return null;
        return now.subtract(ago).multiply(BigDecimal.valueOf(100))
                .divide(ago, 2, RoundingMode.HALF_UP);
    }

    /** 실현 수익률 % = (매도-매수)/매수×100. 순수. */
    static BigDecimal realizedPct(BigDecimal buy, BigDecimal sell) {
        if (buy == null || sell == null || buy.signum() <= 0) return null;
        return sell.subtract(buy).multiply(BigDecimal.valueOf(100))
                .divide(buy, 2, RoundingMode.HALF_UP);
    }

    // best-effort 조회 래퍼 — 실패는 debug 로그 + null(§4c, 기록은 계속).
    private <T> T quiet(java.util.concurrent.Callable<T> call, String label, String code) {
        try {
            return call.call();
        } catch (Exception e) {
            log.debug("[수동저널] {} 스냅샷 실패({}): {}", label, code, e.getMessage());
            return null;
        }
    }
}
