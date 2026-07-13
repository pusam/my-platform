package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.backend.dto.ManualJournalSectorExposureDto;
import com.myplatform.backend.dto.ManualJournalStatsDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.BotTradingPosition;
import com.myplatform.backend.entity.ManualTradeJournal;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.entity.StockCatalyst;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.BotTradingPositionRepository;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

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
    // Phase 2 — 평가용 시세(단일 경로) + KOSPI 벤치마크(KIS 미설정 환경 null-safe).
    private final StockPriceService stockPriceService;
    private final ObjectProvider<KoreaInvestmentService> kisProvider;
    // Phase 3 — 섹터 집중 경고(경고만, 차단 없음). 봇 포지션은 §4d 준수 read-only.
    private final SectorStockConfig sectorConfig;
    private final BotTradingPositionRepository botPositionRepository;
    // "3거래일 후" 컷오프 계산 — signal_outcome 과 같은 잣대(달력일 아님). null-safe(단위테스트 미주입 보존).
    private final ObjectProvider<MarketCalendarService> calendarProvider;

    /** ATR14 일봉 로드 행 수 — StockConclusionService 와 동일. */
    private static final int ATR_HISTORY_ROWS = 40;
    /** 평가 지연 거래일 — signal_outcome(EVALUATION_DELAY_DAYS)과 같은 잣대. */
    private static final int EVALUATION_DELAY_DAYS = 3;
    /** 통계 신뢰 최소 표본 — 미만이면 insufficientSample=true(§4c). */
    static final int MIN_SAMPLE = 10;
    /** RSI 과열 breakdown 임계 — 과열 페널티 1단계(70)와 동일 기준. */
    static final BigDecimal RSI_OVERBOUGHT = new BigDecimal("70");
    private static final String KOSPI_INDEX_CODE = "0001";

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
        // 벤치마크(KOSPI) 가격 — 3거래일 후 alpha 계산용(V44). 실패=null(§4c, 평가 시 pct 폴백).
        j.setBmPriceAtBuy(quiet(this::fetchKospiPriceQuiet, "KOSPI", stockCode));
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

    /** 내 매매 통계 — 집계는 순수 함수 {@link #computeStats}(테스트 대상). */
    @Transactional(readOnly = true)
    public ManualJournalStatsDto stats(String username) {
        return computeStats(journalRepository.findByUsernameOrderByBuyAtDesc(username));
    }

    /** 특정 종목의 내 기록(종목상세 신호 이력 마커용). */
    @Transactional(readOnly = true)
    public List<ManualTradeJournal> listByStock(String username, String stockCode) {
        return journalRepository.findByUsernameAndStockCodeOrderByBuyAtDesc(username, stockCode);
    }

    /**
     * 섹터 집중 노출 — 매수 폼 경고용(경고만, 차단 없음). 보유 = 열린 저널 + 봇 포지션(read-only).
     * 봇 포지션 조회 실패는 저널만으로 계산(best-effort). 집계는 순수 함수 {@link #computeSectorExposure}.
     */
    @Transactional(readOnly = true)
    public ManualJournalSectorExposureDto sectorExposure(String username, String stockCode) {
        List<ManualTradeJournal> open = journalRepository.findByUsernameOrderByBuyAtDesc(username)
                .stream().filter(j -> j.getSellAt() == null).toList();
        List<BotTradingPosition> bot = quiet(botPositionRepository::findAll, "봇포지션", stockCode);
        return computeSectorExposure(stockCode, sectorConfig.getAllSectors(),
                open, bot == null ? List.of() : bot);
    }

    // ==================== Phase 2 — 자동 평가 배치 ====================

    /**
     * 매일 19:40 — 매수 3거래일 경과한 미평가 저널 평가(signal_outcome 19:30 배치와 동일 잣대,
     * 시각만 분리해 순차 실행). pct/alpha/hit 를 채우고 {@code evaluatedAt} 확정.
     *
     * <p><b>멱등</b>: pending 조회 → 필드 채움 → 동일 PK UPDATE. 평가되면 다음 pending 에서 빠지므로
     * 더블런에도 중복 레코드 없음 — SignalOutcomeService.evaluatePendingSignals 와 같은 이유로
     * fail-closed 락 불필요. 시세 미확보 건은 건너뛰고 다음 배치에서 재시도(§4c — 임시값 평가 금지).
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 40 19 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void evaluatePendingJournals() {
        // buyDate ≤ (오늘-3거래일) 과 동치인 datetime 컷 (signal_outcome 의 signalDate ≤ cutoff 와 같은 잣대).
        // 거래일 역산 — 달력일 minusDays(3)는 금요일 매수가 1거래일 만에 평가되는 왜곡. 캘린더 미가용 시 달력일 폴백.
        MarketCalendarService calendar = calendarProvider != null ? calendarProvider.getIfAvailable() : null;
        LocalDate cutoffDate = calendar != null
                ? calendar.minusTradingDays(LocalDate.now(), EVALUATION_DELAY_DAYS)
                : LocalDate.now().minusDays(EVALUATION_DELAY_DAYS);
        LocalDateTime cutoff = cutoffDate.plusDays(1).atStartOfDay();
        List<ManualTradeJournal> pending = journalRepository.findPendingEvaluation(cutoff);
        if (pending.isEmpty()) return;

        log.info("[수동저널] 평가 대상 {}건 (buyAt < {})", pending.size(), cutoff);
        BigDecimal kospiNow = fetchKospiPriceQuiet();   // 모든 건에 동일 시점 BM
        int evaluated = 0;
        for (ManualTradeJournal j : pending) {
            try {
                StockPriceDto current = stockPriceService.getStockPrice(j.getStockCode());
                Evaluation ev = evaluate(j.getBuyPrice(),
                        current == null ? null : current.getCurrentPrice(),
                        j.getBmPriceAtBuy(), kospiNow);
                if (ev == null) continue;   // 시세 미확보 — 다음 배치 재시도
                j.setPctChange3d(ev.pctChange());
                j.setAlpha3d(ev.alpha());
                j.setHit(ev.hit());
                j.setEvaluatedAt(LocalDateTime.now());
                journalRepository.save(j);
                evaluated++;
            } catch (Exception e) {
                log.debug("[수동저널] 평가 실패 id={}: {}", j.getId(), e.getMessage());
            }
        }
        log.info("[수동저널] 평가 완료 {}/{} (BM alpha: {})",
                evaluated, pending.size(), kospiNow != null ? "활성" : "비활성(폴백)");
    }

    /** KOSPI 종합지수 현재가 — SignalOutcomeService.fetchKospiPriceQuiet 동일(private 라 소량 재현). */
    private BigDecimal fetchKospiPriceQuiet() {
        KoreaInvestmentService kis = kisProvider.getIfAvailable();
        if (kis == null || !kis.isConfigured()) return null;
        try {
            JsonNode resp = kis.getIndexPrice(KOSPI_INDEX_CODE);
            if (resp == null || !resp.has("output")) return null;
            JsonNode priceNode = resp.get("output").get("bstp_nmix_prpr");
            if (priceNode == null || priceNode.asText().isEmpty()) return null;
            return new BigDecimal(priceNode.asText().replace(",", ""));
        } catch (Exception e) {
            log.debug("[수동저널] KOSPI 가격 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 순수 함수 (테스트 대상) ====================

    /** 평가 산출값 — hit 판정은 {@link SignalOutcomeService#isHit}(같은 잣대) 재사용. */
    record Evaluation(BigDecimal pctChange, BigDecimal alpha, boolean hit) {}

    /**
     * 3거래일 평가 — 순수. 종목 수익률(pct)과 BM(KOSPI) 대비 alpha 를 구하고
     * hit = alpha≥0 AND pct&gt;0 (BM 결측 시 pct≥+3% 폴백 — SignalOutcomeService.isHit).
     * 매수가/현재가 결측·0 이하 = null(평가 불가 — 다음 배치 재시도).
     */
    static Evaluation evaluate(BigDecimal buyPrice, BigDecimal priceNow,
                               BigDecimal bmAtBuy, BigDecimal bmNow) {
        if (buyPrice == null || buyPrice.signum() <= 0
                || priceNow == null || priceNow.signum() <= 0) return null;
        // scale 4 — signal_outcome(SignalOutcomeService) 와 동일 정밀도로 통일(AUDIT_2026-07-08 #2).
        // isHit 경계(pct 0/3.00·alpha 0)에서 "내 저널"과 "신호 이력"의 hit 판정이 갈리지 않게 한다(동일 잣대).
        BigDecimal pct = priceNow.subtract(buyPrice).multiply(BigDecimal.valueOf(100))
                .divide(buyPrice, 4, RoundingMode.HALF_UP);
        BigDecimal alpha = null;
        if (bmNow != null && bmAtBuy != null && bmAtBuy.signum() > 0) {
            BigDecimal bmReturn = bmNow.subtract(bmAtBuy).multiply(BigDecimal.valueOf(100))
                    .divide(bmAtBuy, 4, RoundingMode.HALF_UP);
            alpha = pct.subtract(bmReturn);
        }
        return new Evaluation(pct, alpha, SignalOutcomeService.isHit(alpha, pct));
    }

    /**
     * 통계 집계 — 순수. 표본 0건의 비율/평균은 null(§4c). RSI/재료 breakdown 은 구조 제공 +
     * 평가 표본 n&lt;{@value #MIN_SAMPLE} 이면 insufficientSample=true.
     * RSI null(미수집)은 과열/정상 어느 bucket 에도 넣지 않는다. 재료 null 은 '없음/미수집' 합산(구분 불가 — 정직 라벨).
     */
    static ManualJournalStatsDto computeStats(List<ManualTradeJournal> rows) {
        List<ManualTradeJournal> all = rows == null ? List.of() : rows;
        long open = all.stream().filter(j -> j.getSellAt() == null).count();

        List<ManualTradeJournal> evaluated = all.stream()
                .filter(j -> j.getEvaluatedAt() != null).toList();
        long hits = evaluated.stream().filter(j -> Boolean.TRUE.equals(j.getHit())).count();

        List<BigDecimal> alphas = evaluated.stream().map(ManualTradeJournal::getAlpha3d)
                .filter(java.util.Objects::nonNull).toList();
        List<BigDecimal> pcts = evaluated.stream().map(ManualTradeJournal::getPctChange3d)
                .filter(java.util.Objects::nonNull).toList();

        List<ManualTradeJournal> realized = all.stream()
                .filter(j -> j.getRealizedPct() != null).toList();
        long wins = realized.stream().filter(j -> j.getRealizedPct().signum() > 0).count();
        List<BigDecimal> realizedPcts = realized.stream()
                .map(ManualTradeJournal::getRealizedPct).toList();

        List<ManualJournalStatsDto.Breakdown> breakdowns = new ArrayList<>();
        breakdowns.add(breakdown("rsiOverbought", "RSI≥70 매수", all,
                j -> j.getRsi() != null && j.getRsi().compareTo(RSI_OVERBOUGHT) >= 0));
        breakdowns.add(breakdown("rsiNormal", "RSI<70 매수", all,
                j -> j.getRsi() != null && j.getRsi().compareTo(RSI_OVERBOUGHT) < 0));
        breakdowns.add(breakdown("catalystPresent", "재료 보유", all,
                j -> j.getCatalystType() != null));
        breakdowns.add(breakdown("catalystAbsent", "재료 없음/미수집", all,
                j -> j.getCatalystType() == null));

        return ManualJournalStatsDto.builder()
                .totalTrades(all.size())
                .openTrades(open)
                .closedTrades(all.size() - open)
                .evaluatedTrades(evaluated.size())
                .hitCount(hits)
                .hitRate(rate(hits, evaluated.size()))
                .avgAlpha3d(avg(alphas))
                .avgPctChange3d(avg(pcts))
                .realizedTrades(realized.size())
                .realizedWinCount(wins)
                .realizedWinRate(rate(wins, realized.size()))
                .avgRealizedPct(avg(realizedPcts))
                .insufficientSample(evaluated.size() < MIN_SAMPLE)
                .breakdowns(breakdowns)
                .build();
    }

    /** 조건 bucket 집계 — 순수. hitRate/avgAlpha 는 평가 완료 표본만. */
    private static ManualJournalStatsDto.Breakdown breakdown(
            String key, String label, List<ManualTradeJournal> all, Predicate<ManualTradeJournal> cond) {
        List<ManualTradeJournal> bucket = all.stream().filter(cond).toList();
        List<ManualTradeJournal> evaluated = bucket.stream()
                .filter(j -> j.getEvaluatedAt() != null).toList();
        long hits = evaluated.stream().filter(j -> Boolean.TRUE.equals(j.getHit())).count();
        List<BigDecimal> alphas = evaluated.stream().map(ManualTradeJournal::getAlpha3d)
                .filter(java.util.Objects::nonNull).toList();
        return ManualJournalStatsDto.Breakdown.builder()
                .key(key)
                .label(label)
                .totalTrades(bucket.size())
                .evaluatedTrades(evaluated.size())
                .hitCount(hits)
                .hitRate(rate(hits, evaluated.size()))
                .avgAlpha3d(avg(alphas))
                .insufficientSample(evaluated.size() < MIN_SAMPLE)
                .build();
    }

    /**
     * 섹터 집중 노출 집계 — 순수. 대상 종목이 속한 섹터마다 이미 보유 중인 종목(열린 저널+봇,
     * 코드 중복 제거·JOURNAL 우선)을 모은다. 매핑 밖 종목 = mapped:false(§4c — 프론트 미표시).
     * 한 종목이 복수 섹터에 속할 수 있어(예: 삼성전자) 섹터별 블록으로 반환.
     */
    static ManualJournalSectorExposureDto computeSectorExposure(
            String stockCode, List<SectorStockConfig.SectorInfo> allSectors,
            List<ManualTradeJournal> openJournals, List<BotTradingPosition> botPositions) {
        // 보유 종목: code → holding (저널 먼저 넣어 JOURNAL 우선, 이후 봇은 신규 코드만)
        java.util.LinkedHashMap<String, ManualJournalSectorExposureDto.Holding> held =
                new java.util.LinkedHashMap<>();
        for (ManualTradeJournal j : openJournals) {
            held.putIfAbsent(j.getStockCode(), ManualJournalSectorExposureDto.Holding.builder()
                    .stockCode(j.getStockCode()).stockName(j.getStockName()).source("JOURNAL").build());
        }
        for (BotTradingPosition p : botPositions) {
            held.putIfAbsent(p.getStockCode(), ManualJournalSectorExposureDto.Holding.builder()
                    .stockCode(p.getStockCode()).stockName(p.getStockName()).source("BOT").build());
        }

        List<ManualJournalSectorExposureDto.SectorBlock> blocks = new ArrayList<>();
        boolean mapped = false;
        for (SectorStockConfig.SectorInfo sector : allSectors) {
            if (sector.getStockCodes() == null || !sector.getStockCodes().contains(stockCode)) continue;
            mapped = true;
            List<ManualJournalSectorExposureDto.Holding> holdings = held.values().stream()
                    .filter(h -> sector.getStockCodes().contains(h.getStockCode()))
                    .toList();
            blocks.add(ManualJournalSectorExposureDto.SectorBlock.builder()
                    .sectorCode(sector.getCode())
                    .sectorName(sector.getName())
                    .count(holdings.size())
                    .holdings(holdings)
                    .build());
        }
        return ManualJournalSectorExposureDto.builder()
                .mapped(mapped)
                .sectors(blocks)
                .build();
    }

    /** 비율 % — 표본 0건 = null(§4c, 0% 위장 금지). */
    static BigDecimal rate(long hits, long total) {
        if (total == 0) return null;
        return BigDecimal.valueOf(hits).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    /** 평균 — 표본 0건 = null(§4c). */
    static BigDecimal avg(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) sum = sum.add(v);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

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
