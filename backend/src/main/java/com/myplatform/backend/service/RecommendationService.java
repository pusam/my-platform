package com.myplatform.backend.service;

import com.myplatform.backend.dto.*;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 종합 추천 TOP 5 — v6
 *
 * v6 핵심 수정:
 * - 섹터 점수를 AI 스냅샷에서 분리 (수급 종목에게도 시장분위기 점수 부여)
 * - validCount=2 cap 80점으로 상향 (실적+기술만으로도 유의미)
 * - 디버그 로그 info→debug 레벨 조정
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationService {

    private final AiStrategySnapshotService aiStrategyService;
    private final InvestorTradeService investorTradeService;
    private final PreemptiveRadarService radarService;
    private final EarningSurpriseService earningSurpriseService;
    private final QuantScreenerService quantScreenerService;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final SectorTradingService sectorTradingService;
    private final StockAnalysisService stockAnalysisService;
    private final StockPriceService stockPriceService;
    private final StockPriceHistoryRepository priceHistoryRepository;
    private final RecommendationSnapshotRepository snapshotRepository;

    private volatile List<RecommendationDto> cachedTop5 = null;
    private volatile LocalDateTime cacheTime = null;
    private static final long CACHE_MINUTES = 30;
    private static final int NA = -1;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    // ==================== Public API ====================

    public Top5Response getTop5() {
        LocalDateTime now = LocalDateTime.now();
        boolean trading = isTradingHours(now);

        if (trading) {
            if (cachedTop5 != null && cacheTime != null
                    && cacheTime.isAfter(now.minusMinutes(CACHE_MINUTES))) {
                return buildResponse(cachedTop5, cacheTime.format(TIME_FMT) + " 기준", true);
            }
            try {
                List<RecommendationDto> result = calculate();
                if (!result.isEmpty()) {
                    cachedTop5 = result;
                    cacheTime = now;
                    return buildResponse(result, now.format(TIME_FMT) + " 기준", true);
                }
            } catch (Exception e) {
                log.error("[종합추천] 실시간 계산 실패: {}", e.getMessage());
            }
        }

        if (cachedTop5 != null && !cachedTop5.isEmpty()) {
            String label = cacheTime != null ? cacheTime.format(TIME_FMT) + " 기준" : "캐시 데이터";
            return buildResponse(cachedTop5, label, false);
        }

        List<RecommendationDto> fromDb = loadFromDb();
        if (!fromDb.isEmpty()) {
            return buildResponse(fromDb, getSnapshotTimeLabel(), false);
        }

        try {
            List<RecommendationDto> result = calculate();
            if (!result.isEmpty()) {
                cachedTop5 = result;
                cacheTime = now;
                return buildResponse(result, now.format(TIME_FMT) + " 기준", false);
            }
        } catch (Exception e) {
            log.debug("[종합추천] 장 외 폴백 계산 실패: {}", e.getMessage());
        }

        return new Top5Response(Collections.emptyList(), "", false, Collections.emptyMap());
    }

    /**
     * TOP5 종목의 changeRate/currentPrice를 실시간 시세로 갱신
     */
    private void refreshPrices(List<RecommendationDto> items) {
        try {
            List<String> codes = items.stream().map(RecommendationDto::getStockCode).toList();
            Map<String, com.myplatform.backend.dto.StockPriceDto> prices = stockPriceService.getStockPrices(codes);
            for (RecommendationDto dto : items) {
                com.myplatform.backend.dto.StockPriceDto p = prices.get(dto.getStockCode());
                if (p != null) {
                    if (p.getChangeRate() != null) dto.setChangeRate(p.getChangeRate());
                    if (p.getCurrentPrice() != null) dto.setCurrentPrice(p.getCurrentPrice());
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 실시간 시세 갱신 실패: {}", e.getMessage());
        }
    }

    private Top5Response buildResponse(List<RecommendationDto> items, String dataTime, boolean realtime) {
        // 실시간 시세로 changeRate/currentPrice 갱신
        refreshPrices(items);

        Map<String, Integer> deltaMap = new HashMap<>();
        try {
            LocalDateTime cutoff = cacheTime != null ? cacheTime.minusHours(20) : LocalDateTime.now().minusDays(1);
            List<RecommendationSnapshot> prev = snapshotRepository.findPreviousSnapshot(cutoff);
            if (!prev.isEmpty()) {
                Map<String, Integer> prevScores = new HashMap<>();
                for (RecommendationSnapshot s : prev) prevScores.put(s.getStockCode(), s.getTotalScore());
                for (RecommendationDto dto : items) {
                    Integer ps = prevScores.get(dto.getStockCode());
                    if (ps != null) deltaMap.put(dto.getStockCode(), dto.getTotalScore() - ps);
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] delta 실패: {}", e.getMessage());
        }
        return new Top5Response(items, dataTime, realtime, deltaMap);
    }

    @Scheduled(cron = "0 45 15 * * MON-FRI")
    @Transactional
    public void saveClosingSnapshot() {
        log.info("[종합추천] 마감 스냅샷 저장 시작");
        try {
            List<RecommendationDto> result = calculate();
            if (result.isEmpty() && cachedTop5 != null && !cachedTop5.isEmpty()) result = cachedTop5;
            if (result.isEmpty()) { log.warn("[종합추천] 마감 스냅샷 — 데이터 없음"); return; }

            LocalDateTime snapTime = LocalDateTime.now();
            for (int i = 0; i < result.size(); i++) {
                RecommendationDto dto = result.get(i);
                RecommendationSnapshot entity = new RecommendationSnapshot();
                entity.setStockCode(dto.getStockCode());
                entity.setStockName(dto.getStockName());
                entity.setTotalScore(dto.getTotalScore());
                entity.setAiStrategy(dto.getAiStrategy());
                entity.setEarnings(dto.getEarnings());
                entity.setSupplyDemand(dto.getSupplyDemand());
                entity.setTechnical(dto.getTechnical());
                entity.setSectorMomentum(dto.getSectorMomentum());
                entity.setTags(dto.getTags() != null ? String.join(",", dto.getTags()) : "");
                entity.setChangeRate(dto.getChangeRate());
                entity.setRankOrder(i + 1);
                entity.setSnapshotAt(snapTime);
                snapshotRepository.save(entity);
            }
            cachedTop5 = result;
            cacheTime = snapTime;
            snapshotRepository.deleteOlderThan(snapTime.minusDays(7));
            log.info("[종합추천] 마감 스냅샷 {}건 저장", result.size());
        } catch (Exception e) {
            log.error("[종합추천] 마감 스냅샷 실패: {}", e.getMessage());
        }
    }

    // ==================== Core Calculation ====================

    private List<RecommendationDto> calculate() {
        Map<String, StockScore> scoreMap = new HashMap<>();

        int aiCount = scoreAiStrategy(scoreMap);
        scoreEarnings(scoreMap);
        scoreSupplyDemand(scoreMap);
        // 섹터: AI 스냅샷 + 시장분위기 (모든 scoreMap 종목에 부여)
        scoreSectorMomentum(scoreMap);
        // 기술: 마지막 (모든 종목 수집 후)
        scoreTechnical(scoreMap);

        // 디버그 로그
        for (StockScore s : scoreMap.values()) {
            log.debug("[종합추천] {} — AI:{} 실적:{} 수급:{} 기술:{} 섹터:{} (유효 {}개)",
                    s.stockName, s.aiStrategy, s.earnings, s.supplyDemand, s.technical, s.sectorMomentum,
                    countValidCategories(s));
        }
        log.info("[종합추천] scoreMap {}종목 (AI시드 {}개)", scoreMap.size(), aiCount);

        List<RecommendationDto> results = scoreMap.values().stream()
                .filter(s -> countValidCategories(s) >= 4)
                .filter(s -> normalizeScore(
                        s.aiStrategy + s.earnings + s.supplyDemand + s.technical + s.sectorMomentum,
                        countValidCategories(s)) >= 60) // 관망(59↓) 종목 제외
                .sorted(Comparator.comparingInt(StockScore::getNormalizedTotal).reversed()
                        .thenComparing(s -> s.changeRate != null ? s.changeRate.doubleValue() : 0.0,
                                Comparator.reverseOrder()))
                .limit(5)
                .map(this::toDto)
                .toList();

        for (RecommendationDto r : results) {
            log.info("[종합추천] #{} {} — 총{}점 (AI:{} 실적:{} 수급:{} 기술:{} 섹터:{}) 유효{}개",
                    results.indexOf(r) + 1, r.getStockName(), r.getTotalScore(),
                    r.getAiStrategy(), r.getEarnings(), r.getSupplyDemand(),
                    r.getTechnical(), r.getSectorMomentum(), r.getValidCount());
        }
        log.info("[종합추천] TOP {} 계산 완료", results.size());
        return results;
    }

    // ==================== ① AI전략 (/20) ====================

    private int scoreAiStrategy(Map<String, StockScore> scoreMap) {
        int scored = 0;
        try {
            var response = aiStrategyService.getAllLatestSnapshots();
            if (response != null && response.getStrategies() != null) {
                for (var entry : response.getStrategies().entrySet()) {
                    List<AiStrategySnapshotDto> stocks = entry.getValue();
                    if (stocks == null || stocks.isEmpty()) continue;
                    for (int i = 0; i < Math.min(3, stocks.size()); i++) {
                        AiStrategySnapshotDto snap = stocks.get(i);
                        if (snap.getStockCode() == null) continue;
                        StockScore score = scoreMap.computeIfAbsent(snap.getStockCode(),
                                k -> new StockScore(k, snap.getStockName()));
                        int rankPoints = (i == 0) ? 8 : (i == 1) ? 5 : 3;
                        int aiBonus = (snap.getAiScore() != null && snap.getAiScore() > 0)
                                ? Math.min(8, snap.getAiScore() * 8 / 100) : 0;
                        int multiBonus = (score.aiStrategy > 0) ? 4 : 0;
                        score.aiStrategy = Math.min(20, score.aiStrategy + rankPoints + aiBonus + multiBonus);
                        score.tags.add(new String[]{"AI전략1위","AI전략2위","AI전략3위"}[i]);
                        if (snap.getChangeRate() != null) score.changeRate = snap.getChangeRate();
                        scored++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[종합추천] AI전략 실패: {}", e.getMessage());
        }

        // 폴백: AI전략 0개면 DB 스냅샷에서 복원
        if (scored == 0) {
            try {
                List<RecommendationSnapshot> prev = snapshotRepository.findLatestSnapshot();
                for (RecommendationSnapshot snap : prev) {
                    if (snap.getAiStrategy() > 0) {
                        StockScore score = scoreMap.computeIfAbsent(snap.getStockCode(),
                                k -> new StockScore(k, snap.getStockName()));
                        score.aiStrategy = snap.getAiStrategy();
                        score.tags.add("AI전략(이전)");
                        if (snap.getChangeRate() != null) score.changeRate = snap.getChangeRate();
                        scored++;
                    }
                }
                if (scored > 0) log.info("[종합추천] AI전략 DB폴백: {}종목", scored);
            } catch (Exception e) { /* ignore */ }
        }
        return scored;
    }

    // ==================== ② 실적 (/20) ====================

    private void scoreEarnings(Map<String, StockScore> scoreMap) {
        try {
            var surprises = earningSurpriseService.detectEarningSurprises();
            if (surprises == null) return;
            for (EarningSurpriseDto s : surprises) {
                if (s.getStockCode() == null) continue;
                String type = s.getSurpriseType() != null ? s.getSurpriseType().toString() : "";
                if (!"POSITIVE".equals(type) && !"TURNAROUND".equals(type)) continue;
                StockScore score = scoreMap.computeIfAbsent(s.getStockCode(),
                        k -> new StockScore(k, s.getStockName()));
                if ("TURNAROUND".equals(type)) {
                    score.earnings = 20;
                    score.tags.add("흑자전환");
                } else {
                    double cr = safeDouble(s.getOperatingProfitChangeRate());
                    if (cr >= 100) { score.earnings = 20; score.tags.add("실적급증+" + (int)cr + "%"); }
                    else if (cr >= 50) { score.earnings = 16; score.tags.add("실적개선+" + (int)cr + "%"); }
                    else if (cr >= 30) { score.earnings = 12; score.tags.add("실적개선+" + (int)cr + "%"); }
                    else { score.earnings = 8; score.tags.add("실적소폭↑"); }
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 실적 실패: {}", e.getMessage());
        }
    }

    // ==================== ③ 수급 (/20) ====================

    private void scoreSupplyDemand(Map<String, StockScore> scoreMap) {
        int fc = 0, ic = 0, topBuy = 0;
        try {
            // 1. 연속매수 (2일+)
            List<ConsecutiveBuyDto> foreign = investorTradeService.getConsecutiveBuyStocks("FOREIGN", 2);
            if (foreign != null) {
                fc = foreign.size();
                for (ConsecutiveBuyDto cb : foreign) {
                    if (cb.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(cb.getStockCode(),
                            k -> new StockScore(k, cb.getStockName()));
                    int days = cb.getConsecutiveDays() != null ? cb.getConsecutiveDays() : 2;
                    double avg = safeDouble(cb.getAvgDailyAmount());
                    int dp = (days >= 5) ? 10 : (days >= 4) ? 8 : (days >= 3) ? 6 : 4;
                    int ab = (avg >= 50) ? 4 : (avg >= 20) ? 2 : (avg >= 5) ? 1 : 0;
                    score.supplyDemand = Math.min(20, score.supplyDemand + dp + ab);
                    String amtStr = avg >= 1 ? String.format("(일%.0f억)", avg) : "";
                    score.tags.add("외국인" + days + "일연속" + amtStr);
                    if (cb.getChangeRate() != null) score.changeRate = cb.getChangeRate();
                }
            }
            List<ConsecutiveBuyDto> inst = investorTradeService.getConsecutiveBuyStocks("INSTITUTION", 2);
            if (inst != null) {
                ic = inst.size();
                for (ConsecutiveBuyDto cb : inst) {
                    if (cb.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(cb.getStockCode(),
                            k -> new StockScore(k, cb.getStockName()));
                    int days = cb.getConsecutiveDays() != null ? cb.getConsecutiveDays() : 2;
                    double avg = safeDouble(cb.getAvgDailyAmount());
                    int dp = (days >= 5) ? 8 : (days >= 4) ? 6 : (days >= 3) ? 4 : 3;
                    int ab = (avg >= 50) ? 3 : (avg >= 20) ? 1 : 0;
                    score.supplyDemand = Math.min(20, score.supplyDemand + dp + ab);
                    score.tags.add("기관" + days + "일연속");
                }
            }

            // 2. 당일 순매수 상위 (연속 아니어도 대량 매수면 점수 부여)
            List<InvestorTradeDto> foreignTop = investorTradeService.getTopTradesByInvestor("FOREIGN", "BUY", 10);
            if (foreignTop != null) {
                for (InvestorTradeDto t : foreignTop) {
                    if (t.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(t.getStockCode(),
                            k -> new StockScore(k, t.getStockName()));
                    if (score.supplyDemand > 0) continue; // 연속매수로 이미 점수 있으면 스킵
                    double amt = safeDouble(t.getNetBuyAmount());
                    // 당일 순매수: 100억+ 8점, 50억+ 6점, 20억+ 4점, 10억+ 2점
                    int pts = (amt >= 100) ? 8 : (amt >= 50) ? 6 : (amt >= 20) ? 4 : (amt >= 10) ? 2 : 0;
                    if (pts > 0) {
                        score.supplyDemand = Math.min(20, score.supplyDemand + pts);
                        score.tags.add(String.format("외국인순매수%.0f억", amt));
                        topBuy++;
                    }
                }
            }
            List<InvestorTradeDto> instTop = investorTradeService.getTopTradesByInvestor("INSTITUTION", "BUY", 10);
            if (instTop != null) {
                for (InvestorTradeDto t : instTop) {
                    if (t.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(t.getStockCode(),
                            k -> new StockScore(k, t.getStockName()));
                    if (score.supplyDemand > 0) continue;
                    double amt = safeDouble(t.getNetBuyAmount());
                    int pts = (amt >= 100) ? 6 : (amt >= 50) ? 4 : (amt >= 20) ? 3 : (amt >= 10) ? 1 : 0;
                    if (pts > 0) {
                        score.supplyDemand = Math.min(20, score.supplyDemand + pts);
                        score.tags.add("기관순매수");
                        topBuy++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[종합추천] 수급 실패: {}", e.getMessage());
        }
        log.info("[종합추천] 수급: 연속(외국인{}건,기관{}건) + 당일상위 {}건", fc, ic, topBuy);
    }

    // ==================== ④ 섹터 (/20) ====================
    // v6 FIX: AI 스냅샷 의존 분리 — 모든 scoreMap 종목에게 시장분위기 보너스 부여

    private void scoreSectorMomentum(Map<String, StockScore> scoreMap) {
        // 1. 섹터 로테이션 데이터에서 시장분위기 보너스 계산
        int marketMoodBonus = 0;
        try {
            List<SectorRotationDto> rotations = sectorTradingService.getSectorRotation();
            if (rotations != null && !rotations.isEmpty()) {
                List<SectorRotationDto> topSectors = rotations.stream()
                        .filter(r -> safeDouble(r.getAvgChangeRate()) > 0 || "INFLOW".equals(r.getFlowDirection()))
                        .sorted(Comparator.comparing(r -> safeDouble(r.getAvgChangeRate()), Comparator.reverseOrder()))
                        .limit(10)
                        .collect(Collectors.toList());
                if (!topSectors.isEmpty()) {
                    double avg = topSectors.stream().mapToDouble(r -> safeDouble(r.getAvgChangeRate())).average().orElse(0);
                    if (avg > 2.0) marketMoodBonus = 6;
                    else if (avg > 1.0) marketMoodBonus = 4;
                    else if (avg > 0.3) marketMoodBonus = 2;
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 섹터 로테이션 실패: {}", e.getMessage());
        }

        // 2. AI 스냅샷에서 테마 보너스 (있으면)
        Map<String, Integer> themeScores = new HashMap<>();
        Map<String, BigDecimal> snapChangeRates = new HashMap<>();
        try {
            var response = aiStrategyService.getAllLatestSnapshots();
            if (response != null && response.getStrategies() != null) {
                for (List<AiStrategySnapshotDto> stocks : response.getStrategies().values()) {
                    if (stocks == null) continue;
                    for (AiStrategySnapshotDto snap : stocks) {
                        if (snap.getStockCode() == null) continue;
                        int ts = 0;
                        String themes = snap.getAiThemes();
                        if (themes != null && !themes.isBlank()) {
                            ts = Math.min(10, 4 + themes.split(",").length * 2);
                        }
                        if (snap.getChangeRate() != null) {
                            double cr = snap.getChangeRate().doubleValue();
                            if (cr > 3.0) ts += 4;
                            else if (cr > 1.5) ts += 3;
                            else if (cr > 0.5) ts += 2;
                            else if (cr > 0) ts += 1;
                        }
                        themeScores.merge(snap.getStockCode(), ts, Math::max);
                        if (snap.getChangeRate() != null) {
                            snapChangeRates.putIfAbsent(snap.getStockCode(), snap.getChangeRate());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] AI테마 조회 실패: {}", e.getMessage());
        }

        // 3. 모든 scoreMap 종목에게 섹터 점수 부여 (AI 의존 제거!)
        int scored = 0;
        for (StockScore stock : scoreMap.values()) {
            int ss = 0;
            // AI 테마 점수 (있으면)
            Integer ts = themeScores.get(stock.stockCode);
            if (ts != null) ss += ts;
            // 시장분위기 보너스 (모든 종목에 부여)
            ss += marketMoodBonus;
            // 종목 자체 등락률 보너스
            BigDecimal cr = snapChangeRates.getOrDefault(stock.stockCode, stock.changeRate);
            if (cr != null) {
                double v = cr.doubleValue();
                if (v > 3.0) ss += 4;
                else if (v > 1.5) ss += 3;
                else if (v > 0.5) ss += 2;
                else if (v > 0) ss += 1;
            }
            // 장중이면 최소 2점 (시장 열림 = 기본 모멘텀)
            if (ss == 0 && isTradingHours(LocalDateTime.now())) {
                ss = 2;
            }
            if (ss > 0) {
                stock.sectorMomentum = Math.min(20, ss);
                scored++;
            }
        }
        log.info("[종합추천] 섹터: {}종목 부여, 시장분위기 +{}", scored, marketMoodBonus);
    }

    // ==================== ⑤ 기술적 (/20) ====================

    private void scoreTechnical(Map<String, StockScore> scoreMap) {
        int calc = 0, fallback = 0, skip = 0;
        // 부족 종목은 모아뒀다가 비동기로 수집 (API 응답 블로킹 방지)
        List<String> needsCollection = new ArrayList<>();

        for (StockScore stock : new ArrayList<>(scoreMap.values())) {
            try {
                List<StockPriceHistory> history = priceHistoryRepository
                        .findByStockCodeOrderByTradeDateDesc(stock.stockCode, PageRequest.of(0, 60));

                if (history == null || history.size() < 5) {
                    needsCollection.add(stock.stockCode);
                    // 즉시 폴백 점수 부여 (changeRate 없어도 최소 점수)
                    if (stock.changeRate != null) {
                        double cr = stock.changeRate.doubleValue();
                        stock.technical = (cr > 2.0) ? 8 : (cr > 0) ? 5 : (cr > -2.0) ? 3 : 1;
                    } else {
                        stock.technical = 3; // changeRate 없어도 최소 3점 (validCount에 포함)
                    }
                    stock.tags.add("기술(간편)");
                    fallback++;
                    continue;
                }

                List<BigDecimal> prices = history.stream()
                        .sorted(Comparator.comparing(StockPriceHistory::getTradeDate))
                        .map(StockPriceHistory::getClosePrice)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                if (prices.size() < 5) { skip++; continue; }

                TechnicalIndicatorsDto ind = technicalIndicatorService.calculate(prices);
                if (ind == null) { skip++; continue; }

                int ts = 0;
                Integer bss = ind.getBuySignalStrength();
                if (bss != null) ts += Math.min(12, bss * 12 / 100);

                BigDecimal rsi = ind.getRsi14();
                if (rsi != null) {
                    double rv = rsi.doubleValue();
                    if (rv >= 45 && rv <= 55) ts += 3;
                    else if (rv >= 40 && rv <= 60) ts += 2;
                    else if (rv >= 30 && rv < 40) ts += 2;
                    else if (rv < 30) ts += 1;
                }

                boolean gc = Boolean.TRUE.equals(ind.getIsGoldenCross());
                boolean au = Boolean.TRUE.equals(ind.getIsArrangedUp());
                if (gc && au) ts += 5; else if (gc) ts += 3; else if (au) ts += 2;

                stock.technical = Math.min(20, ts);
                if (gc) stock.tags.add("골든크로스");
                else if (au) stock.tags.add("정배열");
                if (rsi != null && rsi.intValue() < 35) stock.tags.add("RSI" + rsi.intValue());
                calc++;
            } catch (Exception e) { skip++; }
        }
        log.debug("[종합추천] 기술: {}건 계산, {}건 폴백, {}건 스킵", calc, fallback, skip);

        // 부족 종목 비동기 수집 (다음 calculate() 호출 시 사용 가능)
        if (!needsCollection.isEmpty()) {
            log.info("[종합추천] 가격히스토리 부족 {}종목 → 비동기 수집 예약", needsCollection.size());
            new Thread(() -> {
                for (String code : needsCollection) {
                    try {
                        stockAnalysisService.collectPriceHistory(code);
                    } catch (Exception e) { /* 무시 */ }
                }
            }, "price-history-collector").start();
        }
    }

    // ==================== N/A & Util ====================

    private int countValidCategories(StockScore s) {
        int c = 0;
        if (s.aiStrategy > 0) c++;
        if (s.earnings > 0) c++;
        if (s.supplyDemand > 0) c++;
        if (s.technical > 0) c++;
        if (s.sectorMomentum > 0) c++;
        return c;
    }

    private List<RecommendationDto> loadFromDb() {
        try {
            List<RecommendationSnapshot> snapshots = snapshotRepository.findLatestSnapshot();
            if (snapshots.isEmpty()) return Collections.emptyList();
            List<RecommendationDto> result = snapshots.stream().map(s -> {
                int vc = 0;
                if (s.getAiStrategy() > 0) vc++;
                if (s.getEarnings() > 0) vc++;
                if (s.getSupplyDemand() > 0) vc++;
                if (s.getTechnical() > 0) vc++;
                if (s.getSectorMomentum() > 0) vc++;
                return RecommendationDto.builder()
                    .stockCode(s.getStockCode()).stockName(s.getStockName())
                    .totalScore(s.getTotalScore())
                    .aiStrategy(s.getAiStrategy()).earnings(s.getEarnings())
                    .supplyDemand(s.getSupplyDemand()).technical(s.getTechnical())
                    .sectorMomentum(s.getSectorMomentum()).validCount(vc)
                    .tags(s.getTags() != null && !s.getTags().isBlank()
                            ? Arrays.asList(s.getTags().split(",")) : Collections.emptyList())
                    .changeRate(s.getChangeRate()).build();
            }).toList();
            cachedTop5 = result;
            cacheTime = snapshots.get(0).getSnapshotAt();
            log.info("[종합추천] DB복원 {}건 (시점: {})", result.size(), cacheTime);
            return result;
        } catch (Exception e) {
            log.error("[종합추천] DB로드 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String getSnapshotTimeLabel() {
        if (cacheTime != null) return cacheTime.format(TIME_FMT) + " 기준 (종가)";
        return "이전 데이터";
    }

    private double safeDouble(BigDecimal bd) { return bd != null ? bd.doubleValue() : 0.0; }

    private boolean isTradingHours(LocalDateTime now) {
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime time = now.toLocalTime();
        return time.isAfter(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(15, 35));
    }

    /** 유효 항목 수별 상한: 5개=100, 4개=88, 3개=75, 2개=60 */
    private static int normalizeScore(int raw, int validCount) {
        if (validCount >= 5) return raw;
        if (validCount <= 0) return 0;
        int scaled = raw * 5 / validCount;
        int cap = switch (validCount) {
            case 4 -> 85;
            case 3 -> 65;
            case 2 -> 50;
            default -> 50;
        };
        return Math.min(cap, scaled);
    }

    private RecommendationDto toDto(StockScore s) {
        int vc = countValidCategories(s);
        int raw = s.aiStrategy + s.earnings + s.supplyDemand + s.technical + s.sectorMomentum;
        int total = normalizeScore(raw, vc);

        return RecommendationDto.builder()
                .stockCode(s.stockCode).stockName(s.stockName)
                .totalScore(total)
                .aiStrategy(s.aiStrategy > 0 ? s.aiStrategy : NA)
                .earnings(s.earnings > 0 ? s.earnings : NA)
                .supplyDemand(s.supplyDemand > 0 ? s.supplyDemand : NA)
                .technical(s.technical > 0 ? s.technical : NA)
                .sectorMomentum(s.sectorMomentum > 0 ? s.sectorMomentum : NA)
                .validCount(vc)
                .tags(new ArrayList<>(s.tags))
                .changeRate(s.changeRate).build();
    }

    // ==================== Inner Classes ====================

    private static class StockScore {
        String stockCode, stockName;
        int aiStrategy = 0, earnings = 0, supplyDemand = 0, technical = 0, sectorMomentum = 0;
        Set<String> tags = new LinkedHashSet<>();
        BigDecimal changeRate;
        StockScore(String code, String name) { stockCode = code; stockName = name; }

        int getNormalizedTotal() {
            int v = 0, sum = 0;
            if (aiStrategy > 0) { v++; sum += aiStrategy; }
            if (earnings > 0) { v++; sum += earnings; }
            if (supplyDemand > 0) { v++; sum += supplyDemand; }
            if (technical > 0) { v++; sum += technical; }
            if (sectorMomentum > 0) { v++; sum += sectorMomentum; }
            return normalizeScore(sum, v);
        }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecommendationDto {
        private String stockCode, stockName;
        private int totalScore, aiStrategy, earnings, supplyDemand, technical, sectorMomentum, validCount;
        private List<String> tags;
        private BigDecimal changeRate;
        private BigDecimal currentPrice;
    }

    @Getter @AllArgsConstructor
    public static class Top5Response {
        private final List<RecommendationDto> items;
        private final String dataTime;
        private final boolean realtime;
        private final Map<String, Integer> delta;
    }
}
