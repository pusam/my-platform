package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.SignalAccuracyDto;
import com.myplatform.backend.dto.SignalAccuracyDto.SignalStat;
import com.myplatform.backend.dto.SignalCompareDto;
import com.myplatform.backend.dto.SignalTimeseriesDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 시그널 적중률 추적.
 *
 * 1) {@link #record} — 시그널 생성 시 호출 (RecommendationService 등이 통합).
 *    동일 (시그널/종목/날짜) 중복 INSERT 방지 — 같은 날 여러 번 발생해도 첫 시점만 기록.
 * 2) {@link #evaluatePendingSignals} — 매일 batchScheduler 가 호출. 3일 이상 지난 unevaluated
 *    항목의 현재 가격을 조회해 변동률 + hit 채움. hit 기준: +3% 이상.
 * 3) {@link #getAccuracy} — 최근 N일 시그널 타입별 적중률 / 평균 변동률 통계.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SignalOutcomeService {

    private final SignalOutcomeRepository repository;
    private final StockPriceService stockPriceService;
    // V31 — 시그널 record 시 당일 재료 캐시 스냅샷 (best-effort, Gemini 호출 없음).
    private final com.myplatform.backend.repository.StockCatalystRepository catalystRepository;
    // KOSPI 지수 가격 조회용 — phase 20 alpha 계산.
    // ObjectProvider 로 받아 KIS 미설정 환경에서도 null-safe.
    private final org.springframework.beans.factory.ObjectProvider<KoreaInvestmentService> kisProvider;
    // phase 33: STRONG_BUY 평균 alpha 음수 지속 시 risk 채널 헬스 알림. ObjectProvider 로 받아
    // 텔레그램 미설정 환경(예: 로컬 테스트)에서도 null-safe.
    private final org.springframework.beans.factory.ObjectProvider<TelegramNotificationService> telegramProvider;
    private volatile LocalDate lastAlphaAlertDate = null;

    private static final int EVALUATION_DELAY_DAYS = 3;
    /** hit 기준 — phase 20 변경: 시장 대비 alpha 양수 + 절대 수익률 양수. */
    private static final BigDecimal HIT_ALPHA_THRESHOLD = BigDecimal.ZERO;
    /** BM(KOSPI) 데이터 없을 때 hit 폴백 임계 — 절대 수익률 +3%. */
    private static final BigDecimal FALLBACK_PCT_THRESHOLD = new BigDecimal("3.00");
    private static final String KOSPI_INDEX_CODE = "0001";

    /**
     * 시그널 hit 판정 (phase 20) — 순수 함수로 분리(P1-4 테스트 대상).
     *
     * <p>BM(KOSPI) 대비 alpha 가 있으면 <b>alpha ≥ 0 AND 절대 수익률 &gt; 0</b> 둘 다여야 hit.
     * alpha 가 없으면(BM 데이터 부재) 절대 수익률 ≥ +3% 폴백. 동작은 기존 인라인 로직과 동일.
     */
    static boolean isHit(BigDecimal alpha, BigDecimal pct) {
        if (pct == null) return false;
        if (alpha != null) {
            return alpha.compareTo(HIT_ALPHA_THRESHOLD) >= 0 && pct.signum() > 0;
        }
        return pct.compareTo(FALLBACK_PCT_THRESHOLD) >= 0;
    }

    /**
     * 시그널 발생 기록. 같은 (type/stockCode/날짜) 중복은 무시 — 첫 발생 시점만 보존.
     */
    @Transactional
    public void record(String signalType, String stockCode, String stockName,
                       Integer signalScore, BigDecimal priceAtSignal) {
        record(signalType, stockCode, stockName, signalScore, priceAtSignal,
                null, null, null, null);
    }

    /**
     * 카테고리 점수 스냅샷 포함 기록 — V30. 카테고리 조건부 적중률("수급 주도 vs 기술 주도
     * 추천 중 뭐가 먹혔나") 집계를 위해 시그널 시점의 4 카테고리 점수를 함께 저장.
     */
    @Transactional
    public void record(String signalType, String stockCode, String stockName,
                       Integer signalScore, BigDecimal priceAtSignal,
                       Integer earnings, Integer supplyDemand,
                       Integer technical, Integer sectorMomentum) {
        if (signalType == null || stockCode == null || priceAtSignal == null
                || priceAtSignal.signum() <= 0) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (!repository.findExisting(signalType, stockCode, today).isEmpty()) {
            return;
        }
        try {
            // KOSPI 지수 가격을 한 번 조회 — alpha 계산용 (phase 20).
            // 실패 시 null 로 저장 (기존 evaluate 로직과 호환).
            BigDecimal bmPrice = fetchKospiPriceQuiet();

            // V31 — 당일 재료 캐시가 있으면 스냅샷 (없으면 NULL=미수집, Gemini 호출은 안 함).
            String catalystType = null, catalystDirection = null;
            try {
                var catalyst = catalystRepository.findByStockCodeAndCatalystDate(stockCode, today);
                if (catalyst.isPresent()) {
                    catalystType = catalyst.get().getCatalystType().name();
                    catalystDirection = catalyst.get().getDirection().name();
                }
            } catch (Exception ignore) { /* 재료 스냅샷은 best-effort */ }

            repository.save(SignalOutcome.builder()
                    .signalType(signalType)
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .signalDate(today)
                    .signalScore(signalScore)
                    .priceAtSignal(priceAtSignal)
                    .bmPriceAtSignal(bmPrice)
                    .earningsAtSignal(earnings)
                    .supplyDemandAtSignal(supplyDemand)
                    .technicalAtSignal(technical)
                    .sectorMomentumAtSignal(sectorMomentum)
                    .catalystTypeAtSignal(catalystType)
                    .catalystDirectionAtSignal(catalystDirection)
                    .build());
        } catch (Exception e) {
            log.debug("[SignalOutcome] record 실패 ({}/{}): {}", signalType, stockCode, e.getMessage());
        }
    }

    /**
     * 시그널 이후 3거래일 OHLC 에서 max_high / max_low / MFE / MAE 계산 채움 (phase 25).
     * KIS getDailyOhlcv 가 최신 N일 반환 — signalDate 이후 항목만 필터링하여 high/low 누적.
     * KIS 미설정 또는 조회 실패 시 모든 필드 NULL 유지 (기존 평가는 영향 없음).
     */
    private void fillMfeMae(SignalOutcome outcome) {
        try {
            KoreaInvestmentService kis = kisProvider.getIfAvailable();
            if (kis == null || !kis.isConfigured()) return;
            // 시그널 이후 3거래일 + 안전 마진 = 7일 조회. 주말/공휴일 포함.
            java.util.List<KoreaInvestmentService.OhlcvData> ohlcv =
                    kis.getDailyOhlcv(outcome.getStockCode(), 7);
            if (ohlcv == null || ohlcv.isEmpty()) return;
            BigDecimal maxHigh = null;
            BigDecimal minLow = null;
            for (KoreaInvestmentService.OhlcvData candle : ohlcv) {
                if (candle.getTradeDate() == null) continue;
                if (!candle.getTradeDate().isAfter(outcome.getSignalDate())) continue; // 시그널일 이후만
                if (candle.getHigh() != null && (maxHigh == null || candle.getHigh().compareTo(maxHigh) > 0)) {
                    maxHigh = candle.getHigh();
                }
                if (candle.getLow() != null && (minLow == null || candle.getLow().compareTo(minLow) < 0)) {
                    minLow = candle.getLow();
                }
            }
            if (maxHigh != null) {
                outcome.setMaxHigh3d(maxHigh);
                outcome.setMfePct3d(maxHigh.subtract(outcome.getPriceAtSignal())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(outcome.getPriceAtSignal(), 4, RoundingMode.HALF_UP));
            }
            if (minLow != null) {
                outcome.setMaxLow3d(minLow);
                outcome.setMaePct3d(minLow.subtract(outcome.getPriceAtSignal())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(outcome.getPriceAtSignal(), 4, RoundingMode.HALF_UP));
            }
        } catch (Exception e) {
            log.debug("[SignalOutcome] MFE/MAE 채우기 실패 id={}: {}", outcome.getId(), e.getMessage());
        }
    }

    /** KOSPI 종합지수 현재가 조회 — phase 20. KIS 미설정 시 null. */
    private BigDecimal fetchKospiPriceQuiet() {
        KoreaInvestmentService kis = kisProvider.getIfAvailable();
        if (kis == null || !kis.isConfigured()) return null;
        try {
            JsonNode resp = kis.getIndexPrice(KOSPI_INDEX_CODE);
            if (resp == null || !resp.has("output")) return null;
            JsonNode output = resp.get("output");
            JsonNode priceNode = output.has("bstp_nmix_prpr") ? output.get("bstp_nmix_prpr") : null;
            if (priceNode == null || priceNode.asText().isEmpty()) return null;
            return new BigDecimal(priceNode.asText().replace(",", ""));
        } catch (Exception e) {
            log.debug("[SignalOutcome] KOSPI 가격 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 매일 19:30 — 3일 전 unevaluated 시그널 평가.
     * batchScheduler 로 트레이딩 부담 0.
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 30 19 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void evaluatePendingSignals() {
        LocalDate cutoff = LocalDate.now().minusDays(EVALUATION_DELAY_DAYS);
        List<SignalOutcome> pending = repository.findPendingEvaluation(cutoff);
        if (pending.isEmpty()) {
            // 평가 대상 없어도 헬스 체크는 실행 — 누적 표본 기준이라 신규 평가 0건이어도 의미 있음.
            checkStrongBuyAlphaHealth();
            return;
        }

        log.info("[SignalOutcome] 평가 대상 {}건 (signalDate ≤ {})", pending.size(), cutoff);
        // KOSPI 현재가 한 번 조회 (모든 outcome에 동일 시점) — phase 20 alpha 계산.
        BigDecimal kospiNow = fetchKospiPriceQuiet();
        int evaluated = 0;
        for (SignalOutcome outcome : pending) {
            try {
                StockPriceDto current = stockPriceService.getStockPrice(outcome.getStockCode());
                if (current == null || current.getCurrentPrice() == null
                        || current.getCurrentPrice().signum() <= 0) {
                    continue;
                }
                BigDecimal priceNow = current.getCurrentPrice();
                BigDecimal pct = priceNow.subtract(outcome.getPriceAtSignal())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(outcome.getPriceAtSignal(), 4, RoundingMode.HALF_UP);

                // BM 변동률 + alpha — phase 20.
                BigDecimal bmReturn = null;
                BigDecimal alpha = null;
                BigDecimal bmAtSignal = outcome.getBmPriceAtSignal();
                if (kospiNow != null && bmAtSignal != null && bmAtSignal.signum() > 0) {
                    bmReturn = kospiNow.subtract(bmAtSignal)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(bmAtSignal, 4, RoundingMode.HALF_UP);
                    alpha = pct.subtract(bmReturn);
                }

                // hit 기준 — phase 20: alpha 양수 + 절대 수익 양수. BM 데이터 없으면 +3% 폴백. (isHit 로 분리)
                boolean hit = isHit(alpha, pct);

                outcome.setPriceAfter3d(priceNow);
                outcome.setPctChange3d(pct);
                outcome.setBmReturn3d(bmReturn);
                outcome.setAlpha3d(alpha);
                outcome.setHit(hit);

                // MFE / MAE — phase 25. 시그널일 이후 3거래일 OHLC 수집.
                // KIS getDailyOhlcv 가 최신순 반환. signalDate 이후 항목만 필터링.
                fillMfeMae(outcome);

                outcome.setEvaluatedAt(LocalDateTime.now());
                repository.save(outcome);
                evaluated++;
            } catch (Exception e) {
                log.debug("[SignalOutcome] 평가 실패 id={}: {}", outcome.getId(), e.getMessage());
            }
        }
        log.info("[SignalOutcome] 평가 완료 {}/{} (BM alpha: {})",
                evaluated, pending.size(), kospiNow != null ? "활성" : "비활성(폴백)");
        checkStrongBuyAlphaHealth();
    }

    /**
     * STRONG_BUY 평균 alpha 헬스 체크 — phase 33.
     *
     * <p>최근 7일 STRONG_BUY 시그널의 평균 alpha (vs KOSPI) 가 음수면 risk 채널 알림.
     * 산식에 영향 주는 가드가 아니라 <b>관찰 가드</b>: 운영 관찰 기반 튜닝 (phase 31 등)
     * 이 over-fit 됐을 때 빠르게 감지하기 위함.
     *
     * <p>스팸 방지: 일 1회 (lastAlphaAlertDate). 표본 부족 (null) 이면 스킵.
     * 텔레그램 미설정 환경에서는 로그만 출력.
     */
    private void checkStrongBuyAlphaHealth() {
        try {
            LocalDate today = LocalDate.now();
            if (today.equals(lastAlphaAlertDate)) return;
            BigDecimal avgAlpha = repository.averageAlphaSince("STRONG_BUY", today.minusDays(7));
            if (avgAlpha == null) return;
            if (avgAlpha.signum() >= 0) return;

            BigDecimal scaled = avgAlpha.setScale(2, RoundingMode.HALF_UP);
            log.warn("[SignalOutcome] ⚠ STRONG_BUY 최근 7일 평균 alpha 음수: {}% (산식 검토 권장)", scaled);

            TelegramNotificationService telegram = telegramProvider.getIfAvailable();
            if (telegram != null) {
                try {
                    telegram.sendRisk(String.format(
                            "⚠️ <b>STRONG_BUY 평균 alpha 음수</b>\n\n"
                                    + "• 최근 7일 평균 alpha: <b>%s%%</b>\n"
                                    + "• 추천 산식이 시장 대비 underperform 중\n"
                                    + "• /api/signal-outcomes/compare 로 phase 변경 시점 전후 비교 권장",
                            scaled.toPlainString()));
                } catch (Exception e) {
                    log.debug("[SignalOutcome] alpha 헬스 텔레그램 발송 실패: {}", e.getMessage());
                }
            }
            lastAlphaAlertDate = today;
        } catch (Exception e) {
            log.debug("[SignalOutcome] STRONG_BUY 헬스 체크 실패: {}", e.getMessage());
        }
    }

    /** 시그널별 적중률 통계 — 최근 days 일 기준. */
    public SignalAccuracyDto getAccuracy(int days) {
        LocalDate from = LocalDate.now().minusDays(Math.max(1, days));
        List<Object[]> rows = repository.aggregateStats(from);
        List<SignalStat> stats = new ArrayList<>();
        for (Object[] row : rows) {
            String type = (String) row[0];
            long total = ((Number) row[1]).longValue();
            long hits = row[2] == null ? 0L : ((Number) row[2]).longValue();
            BigDecimal avg = row[3] == null ? BigDecimal.ZERO : new BigDecimal(row[3].toString());
            BigDecimal hitRate = total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(hits)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            stats.add(SignalStat.builder()
                    .signalType(type)
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(hitRate)
                    .avgPctChange(avg.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }
        return SignalAccuracyDto.builder()
                .daysWindow(days)
                .stats(stats)
                .build();
    }

    /**
     * cutoff 시점 전후 windowDays 일 통계 비교 — phase 32.
     *
     * <p>용도: phase 31 추격매수 방지 산식이 alpha/hit-rate 를 실제로 개선했는지 검증.
     * 운영 관찰 기반 산식 변경이 누적되면 검증 없이는 over-fit 위험이 있어 도입.
     *
     * <p>구간: <b>[cutoff − windowDays, cutoff)</b> vs <b>[cutoff, cutoff + windowDays)</b>.
     * before 윈도우는 cutoff 미포함 (반열린 구간).
     *
     * <p>표본이 너무 적으면(< 3) delta 의 의미가 약하므로 sufficientSample 플래그로 표시.
     *
     * @param signalTypeFilter null/blank 이면 전체 시그널, 값이 있으면 해당 type 만
     * @param cutoff phase 변경 적용 시점
     * @param windowDays before/after 각 윈도우의 일수 (기본 30)
     */
    public SignalCompareDto compareAroundCutoff(String signalTypeFilter,
                                                LocalDate cutoff, int windowDays) {
        if (cutoff == null) cutoff = LocalDate.now();
        int w = windowDays < 1 ? 30 : windowDays;
        LocalDate beforeFrom = cutoff.minusDays(w);
        LocalDate afterTo = cutoff.plusDays(w);

        Map<String, SignalCompareDto.Stat> beforeStats =
                toStatMap(repository.aggregateStatsBetween(beforeFrom, cutoff));
        Map<String, SignalCompareDto.Stat> afterStats =
                toStatMap(repository.aggregateStatsBetween(cutoff, afterTo));

        // signalType 필터 적용 (있으면)
        if (signalTypeFilter != null && !signalTypeFilter.isBlank()) {
            beforeStats = filterByType(beforeStats, signalTypeFilter);
            afterStats = filterByType(afterStats, signalTypeFilter);
        }

        // delta 계산 — before/after 중 하나라도 등장한 타입 모두 포함, type 정렬 보존
        Set<String> allTypes = new LinkedHashSet<>();
        allTypes.addAll(beforeStats.keySet());
        allTypes.addAll(afterStats.keySet());

        List<SignalCompareDto.Delta> deltas = new ArrayList<>();
        for (String type : allTypes) {
            SignalCompareDto.Stat b = beforeStats.get(type);
            SignalCompareDto.Stat a = afterStats.get(type);
            deltas.add(SignalCompareDto.Delta.builder()
                    .signalType(type)
                    .hitRateChange(subtractOrNull(
                            a != null ? a.getHitRate() : null,
                            b != null ? b.getHitRate() : null))
                    .avgAlphaChange(subtractOrNull(
                            a != null ? a.getAvgAlpha() : null,
                            b != null ? b.getAvgAlpha() : null))
                    .avgPctChange(subtractOrNull(
                            a != null ? a.getAvgPctChange() : null,
                            b != null ? b.getAvgPctChange() : null))
                    .avgMfeChange(subtractOrNull(
                            a != null ? a.getAvgMfe() : null,
                            b != null ? b.getAvgMfe() : null))
                    .avgMaeChange(subtractOrNull(
                            a != null ? a.getAvgMae() : null,
                            b != null ? b.getAvgMae() : null))
                    .sufficientSample(b != null && b.getTotalSignals() >= 3
                            && a != null && a.getTotalSignals() >= 3)
                    .build());
        }

        return SignalCompareDto.builder()
                .cutoff(cutoff)
                .windowDays(w)
                .signalTypeFilter(signalTypeFilter)
                .before(SignalCompareDto.Window.builder()
                        .from(beforeFrom).to(cutoff)
                        .stats(new ArrayList<>(beforeStats.values()))
                        .build())
                .after(SignalCompareDto.Window.builder()
                        .from(cutoff).to(afterTo)
                        .stats(new ArrayList<>(afterStats.values()))
                        .build())
                .deltas(deltas)
                .build();
    }

    /** aggregateStatsBetween 결과 row 를 signalType → Stat 맵으로 변환. */
    private static Map<String, SignalCompareDto.Stat> toStatMap(List<Object[]> rows) {
        Map<String, SignalCompareDto.Stat> map = new HashMap<>();
        for (Object[] row : rows) {
            String type = (String) row[0];
            long total = ((Number) row[1]).longValue();
            long hits = row[2] == null ? 0L : ((Number) row[2]).longValue();
            BigDecimal hitRate = total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(hits)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            map.put(type, SignalCompareDto.Stat.builder()
                    .signalType(type)
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(hitRate)
                    .avgPctChange(scaleOrNull(row[3]))
                    .avgAlpha(scaleOrNull(row[4]))
                    .avgMfe(scaleOrNull(row[5]))
                    .avgMae(scaleOrNull(row[6]))
                    .build());
        }
        return map;
    }

    private static Map<String, SignalCompareDto.Stat> filterByType(
            Map<String, SignalCompareDto.Stat> source, String type) {
        Map<String, SignalCompareDto.Stat> filtered = new HashMap<>();
        if (source.containsKey(type)) filtered.put(type, source.get(type));
        return filtered;
    }

    private static BigDecimal scaleOrNull(Object value) {
        if (value == null) return null;
        return new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal subtractOrNull(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.subtract(b).setScale(2, RoundingMode.HALF_UP);
    }

    // ================================================================
    // 조건부 적중률 — 점수 구간별 + 카테고리 강세별 (V30)
    // ================================================================

    /** 점수 구간 정의 — BUY 컷(55)부터. [from, to] 닫힌 구간. */
    private static final int[][] SCORE_BANDS = {{55, 64}, {65, 74}, {75, 84}, {85, 100}};
    /** 카테고리 "강세" 판정 임계 — 결론 카드 POSITIVE 기준(15)과 동일. */
    static final int CATEGORY_STRONG_THRESHOLD = 15;

    /** 조건부 적중률 — 최근 days 일 평가 완료분 기준. */
    public com.myplatform.backend.dto.SignalBandAccuracyDto getAccuracyByBand(int days) {
        int d = days < 1 ? 90 : days;
        LocalDate from = LocalDate.now().minusDays(d);
        List<SignalOutcome> rows = repository.findEvaluatedSince(from);
        return com.myplatform.backend.dto.SignalBandAccuracyDto.builder()
                .daysWindow(d)
                .bands(aggregateBands(rows))
                .categories(aggregateCategories(rows))
                .catalysts(aggregateCatalysts(rows))
                .build();
    }

    /**
     * 재료 방향별 집계 (V31) — 순수 함수. "재료 있는 추천이 더 먹히나" 검증용.
     * catalyst 컬럼 NULL(미수집) 행은 제외. NONE = 뉴스는 봤으나 재료 없음.
     */
    static List<com.myplatform.backend.dto.SignalBandAccuracyDto.CatalystStat> aggregateCatalysts(
            List<SignalOutcome> rows) {
        String[][] defs = {
                {"POSITIVE", "호재"},
                {"NEGATIVE", "악재"},
                {"NEUTRAL", "중립"},
                {"NONE", "재료없음"},
        };
        List<com.myplatform.backend.dto.SignalBandAccuracyDto.CatalystStat> result = new ArrayList<>();
        for (String[] def : defs) {
            long total = 0, hits = 0;
            BigDecimal pctSum = BigDecimal.ZERO;
            long pctCount = 0;
            for (SignalOutcome s : rows) {
                if (!def[0].equals(s.getCatalystDirectionAtSignal())) continue;
                total++;
                if (Boolean.TRUE.equals(s.getHit())) hits++;
                if (s.getPctChange3d() != null) { pctSum = pctSum.add(s.getPctChange3d()); pctCount++; }
            }
            result.add(com.myplatform.backend.dto.SignalBandAccuracyDto.CatalystStat.builder()
                    .direction(def[0])
                    .label(def[1])
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(rate(hits, total))
                    .avgPctChange(avg(pctSum, pctCount))
                    .build());
        }
        return result;
    }

    /** 점수 구간별 집계 — 순수 함수 (테스트 대상). signalScore 없는 행은 제외. */
    static List<com.myplatform.backend.dto.SignalBandAccuracyDto.BandStat> aggregateBands(
            List<SignalOutcome> rows) {
        List<com.myplatform.backend.dto.SignalBandAccuracyDto.BandStat> result = new ArrayList<>();
        for (int[] band : SCORE_BANDS) {
            long total = 0, hits = 0;
            BigDecimal pctSum = BigDecimal.ZERO, alphaSum = BigDecimal.ZERO;
            long pctCount = 0, alphaCount = 0;
            for (SignalOutcome s : rows) {
                Integer score = s.getSignalScore();
                if (score == null || score < band[0] || score > band[1]) continue;
                total++;
                if (Boolean.TRUE.equals(s.getHit())) hits++;
                if (s.getPctChange3d() != null) { pctSum = pctSum.add(s.getPctChange3d()); pctCount++; }
                if (s.getAlpha3d() != null) { alphaSum = alphaSum.add(s.getAlpha3d()); alphaCount++; }
            }
            result.add(com.myplatform.backend.dto.SignalBandAccuracyDto.BandStat.builder()
                    .band(band[0] + "~" + band[1])
                    .scoreFrom(band[0])
                    .scoreTo(band[1])
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(rate(hits, total))
                    .avgPctChange(avg(pctSum, pctCount))
                    .avgAlpha(avg(alphaSum, alphaCount))
                    .build());
        }
        return result;
    }

    /** 카테고리 강세(≥15) 표본별 집계 — 순수 함수. V30 컬럼 NULL 행(과거 데이터)은 제외. */
    static List<com.myplatform.backend.dto.SignalBandAccuracyDto.CategoryStat> aggregateCategories(
            List<SignalOutcome> rows) {
        String[][] defs = {
                {"earnings", "실적"},
                {"supplyDemand", "수급"},
                {"technical", "기술"},
                {"sectorMomentum", "섹터"},
        };
        List<com.myplatform.backend.dto.SignalBandAccuracyDto.CategoryStat> result = new ArrayList<>();
        for (String[] def : defs) {
            long total = 0, hits = 0;
            BigDecimal pctSum = BigDecimal.ZERO;
            long pctCount = 0;
            for (SignalOutcome s : rows) {
                Integer score = categoryScore(s, def[0]);
                if (score == null || score < CATEGORY_STRONG_THRESHOLD) continue;
                total++;
                if (Boolean.TRUE.equals(s.getHit())) hits++;
                if (s.getPctChange3d() != null) { pctSum = pctSum.add(s.getPctChange3d()); pctCount++; }
            }
            result.add(com.myplatform.backend.dto.SignalBandAccuracyDto.CategoryStat.builder()
                    .key(def[0])
                    .label(def[1])
                    .strongThreshold(CATEGORY_STRONG_THRESHOLD)
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(rate(hits, total))
                    .avgPctChange(avg(pctSum, pctCount))
                    .build());
        }
        return result;
    }

    private static Integer categoryScore(SignalOutcome s, String key) {
        return switch (key) {
            case "earnings" -> s.getEarningsAtSignal();
            case "supplyDemand" -> s.getSupplyDemandAtSignal();
            case "technical" -> s.getTechnicalAtSignal();
            case "sectorMomentum" -> s.getSectorMomentumAtSignal();
            default -> null;
        };
    }

    private static BigDecimal rate(long hits, long total) {
        if (total == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(hits)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal avg(BigDecimal sum, long count) {
        if (count == 0) return null;
        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /**
     * 일별 시계열 — phase 33. 프론트 그래프용.
     * 최근 N일 (signalDate 기준) 의 시그널별 hit-rate / 평균 변동률 / 평균 alpha 를 일자별로 반환.
     */
    public SignalTimeseriesDto getTimeseries(String signalTypeFilter, int days) {
        int d = days < 1 ? 60 : days;
        LocalDate from = LocalDate.now().minusDays(d);
        List<Object[]> rows = repository.aggregateDailyTimeseries(from);
        List<SignalTimeseriesDto.Point> points = new ArrayList<>();
        boolean filterActive = signalTypeFilter != null && !signalTypeFilter.isBlank();
        for (Object[] row : rows) {
            LocalDate date = (LocalDate) row[0];
            String type = (String) row[1];
            if (filterActive && !signalTypeFilter.equals(type)) continue;
            long total = ((Number) row[2]).longValue();
            long hits = row[3] == null ? 0L : ((Number) row[3]).longValue();
            BigDecimal hitRate = total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(hits)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            points.add(SignalTimeseriesDto.Point.builder()
                    .date(date)
                    .signalType(type)
                    .totalSignals(total)
                    .hitCount(hits)
                    .hitRate(hitRate)
                    .avgPctChange(scaleOrNull(row[4]))
                    .avgAlpha(scaleOrNull(row[5]))
                    .build());
        }
        return SignalTimeseriesDto.builder()
                .daysWindow(d)
                .signalTypeFilter(signalTypeFilter)
                .points(points)
                .build();
    }
}
