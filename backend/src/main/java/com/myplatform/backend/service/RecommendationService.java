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
 * AI 종합 추천 TOP 5 — 차등 스코어링 엔진 v4
 *
 * 5개 항목 각 /20, 합계 /100
 * 데이터 없는 항목 = -1 (N/A) → 유효 항목만으로 100점 환산
 * 최소 3개 항목 유효해야 등급 판정
 *
 * v4 수정:
 * - AI전략 Gemini 실패 시 DB 스냅샷 폴백
 * - 수급/섹터: scoreMap에 독립적으로 종목 추가 (AI전략 의존 제거)
 * - 섹터: scoreMap.get → computeIfAbsent (빈 scoreMap에서도 동작)
 * - 2개 항목만으로 100점 환산 방지 (최소 3개, 2개면 상한 70점)
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

    private Top5Response buildResponse(List<RecommendationDto> items, String dataTime, boolean realtime) {
        Map<String, Integer> deltaMap = new HashMap<>();
        try {
            LocalDateTime cutoff = cacheTime != null ? cacheTime.minusHours(20) : LocalDateTime.now().minusDays(1);
            List<RecommendationSnapshot> prev = snapshotRepository.findPreviousSnapshot(cutoff);
            if (!prev.isEmpty()) {
                Map<String, Integer> prevScores = new HashMap<>();
                for (RecommendationSnapshot s : prev) {
                    prevScores.put(s.getStockCode(), s.getTotalScore());
                }
                for (RecommendationDto dto : items) {
                    Integer prevScore = prevScores.get(dto.getStockCode());
                    if (prevScore != null) {
                        deltaMap.put(dto.getStockCode(), dto.getTotalScore() - prevScore);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] delta 계산 실패: {}", e.getMessage());
        }
        return new Top5Response(items, dataTime, realtime, deltaMap);
    }

    @Scheduled(cron = "0 45 15 * * MON-FRI")
    @Transactional
    public void saveClosingSnapshot() {
        log.info("[종합추천] 마감 스냅샷 저장 시작");
        try {
            List<RecommendationDto> result = calculate();
            if (result.isEmpty() && cachedTop5 != null && !cachedTop5.isEmpty()) {
                result = cachedTop5;
            }
            if (result.isEmpty()) {
                log.warn("[종합추천] 마감 스냅샷 저장 실패 — 데이터 없음");
                return;
            }

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
            log.info("[종합추천] 마감 스냅샷 {}건 저장 완료", result.size());
        } catch (Exception e) {
            log.error("[종합추천] 마감 스냅샷 저장 실패: {}", e.getMessage());
        }
    }

    // ==================== Core Calculation ====================

    private List<RecommendationDto> calculate() {
        Map<String, StockScore> scoreMap = new HashMap<>();

        // 각 항목이 독립적으로 종목 추가 (AI전략 실패해도 나머지 동작)
        int aiCount = scoreAiStrategy(scoreMap);
        scoreEarnings(scoreMap);
        scoreSupplyDemand(scoreMap);
        scoreTechnical(scoreMap);
        scoreSectorMomentum(scoreMap);

        log.info("[종합추천] scoreMap: {}종목 (AI전략 {}개 시드)", scoreMap.size(), aiCount);

        List<RecommendationDto> results = scoreMap.values().stream()
                .filter(s -> countValidCategories(s) >= 2)
                .sorted(Comparator.comparingInt(StockScore::getNormalizedTotal).reversed()
                        .thenComparing(s -> s.changeRate != null ? s.changeRate.doubleValue() : 0.0,
                                Comparator.reverseOrder()))
                .limit(5)
                .map(this::toDto)
                .toList();

        log.info("[종합추천] TOP {} 계산 완료", results.size());
        return results;
    }

    // ==================== ① AI전략 신호 (/20) ====================
    // FIX: Gemini 실패 시 DB에서 직전 스냅샷 폴백

    private int scoreAiStrategy(Map<String, StockScore> scoreMap) {
        int scored = 0;
        try {
            var response = aiStrategyService.getAllLatestSnapshots();
            if (response != null && response.getStrategies() != null) {
                for (Map.Entry<String, List<AiStrategySnapshotDto>> entry : response.getStrategies().entrySet()) {
                    List<AiStrategySnapshotDto> stocks = entry.getValue();
                    if (stocks == null || stocks.isEmpty()) continue;

                    for (int i = 0; i < Math.min(3, stocks.size()); i++) {
                        AiStrategySnapshotDto snap = stocks.get(i);
                        if (snap.getStockCode() == null) continue;

                        StockScore score = scoreMap.computeIfAbsent(snap.getStockCode(),
                                k -> new StockScore(k, snap.getStockName()));

                        int rankPoints = (i == 0) ? 8 : (i == 1) ? 5 : 3;
                        int aiBonus = 0;
                        if (snap.getAiScore() != null && snap.getAiScore() > 0) {
                            aiBonus = Math.min(8, snap.getAiScore() * 8 / 100);
                        }
                        int multiBonus = (score.aiStrategy > 0) ? 4 : 0;

                        score.aiStrategy = Math.min(20, score.aiStrategy + rankPoints + aiBonus + multiBonus);

                        String[] labels = {"AI전략1위", "AI전략2위", "AI전략3위"};
                        score.tags.add(labels[i]);
                        if (snap.getChangeRate() != null) score.changeRate = snap.getChangeRate();
                        scored++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[종합추천] AI 전략 스코어 실패: {}", e.getMessage());
        }

        // FIX: AI전략이 0개면 DB 스냅샷에서 AI전략 점수 폴백
        if (scored == 0) {
            log.warn("[종합추천] AI전략 0개 — DB 스냅샷에서 폴백 시도");
            try {
                List<RecommendationSnapshot> prevSnap = snapshotRepository.findLatestSnapshot();
                for (RecommendationSnapshot snap : prevSnap) {
                    if (snap.getAiStrategy() > 0) {
                        StockScore score = scoreMap.computeIfAbsent(snap.getStockCode(),
                                k -> new StockScore(k, snap.getStockName()));
                        score.aiStrategy = snap.getAiStrategy();
                        score.tags.add("AI전략(이전)");
                        if (snap.getChangeRate() != null) score.changeRate = snap.getChangeRate();
                        scored++;
                    }
                }
                if (scored > 0) log.info("[종합추천] AI전략 DB 폴백: {}종목 복원", scored);
            } catch (Exception e) {
                log.debug("[종합추천] AI전략 DB 폴백 실패: {}", e.getMessage());
            }
        }

        return scored;
    }

    // ==================== ② 실적 개선세 (/20) ====================

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
                    double changeRate = safeDouble(s.getOperatingProfitChangeRate());
                    if (changeRate >= 100) {
                        score.earnings = 20;
                        score.tags.add("실적급증+" + (int) changeRate + "%");
                    } else if (changeRate >= 50) {
                        score.earnings = 16;
                        score.tags.add("실적개선+" + (int) changeRate + "%");
                    } else if (changeRate >= 30) {
                        score.earnings = 12;
                        score.tags.add("실적개선+" + (int) changeRate + "%");
                    } else {
                        score.earnings = 8;
                        score.tags.add("실적소폭↑");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 실적 스코어 실패: {}", e.getMessage());
        }
    }

    // ==================== ③ 기관/외국인 수급 (/20) ====================
    // FIX: 로그 강화 — 데이터 있는데 연결 안 되는 원인 추적

    private void scoreSupplyDemand(Map<String, StockScore> scoreMap) {
        int foreignCount = 0, instCount = 0, scored = 0;
        try {
            List<ConsecutiveBuyDto> foreign = investorTradeService.getConsecutiveBuyStocks("FOREIGN", 2);
            foreignCount = (foreign != null) ? foreign.size() : 0;
            if (foreign != null) {
                for (ConsecutiveBuyDto cb : foreign) {
                    if (cb.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(cb.getStockCode(),
                            k -> new StockScore(k, cb.getStockName()));
                    int days = cb.getConsecutiveDays() != null ? cb.getConsecutiveDays() : 2;
                    double avgAmount = safeDouble(cb.getAvgDailyAmount());

                    int dayPoints = (days >= 5) ? 10 : (days >= 4) ? 8 : (days >= 3) ? 6 : 4;
                    int amountBonus = (avgAmount >= 50) ? 4 : (avgAmount >= 20) ? 2 : (avgAmount >= 5) ? 1 : 0;

                    score.supplyDemand = Math.min(20, score.supplyDemand + dayPoints + amountBonus);
                    String amountStr = avgAmount >= 1 ? String.format("(일%.0f억)", avgAmount) : "";
                    score.tags.add("외국인" + days + "일연속" + amountStr);
                    if (cb.getChangeRate() != null) score.changeRate = cb.getChangeRate();
                    scored++;
                }
            }

            List<ConsecutiveBuyDto> inst = investorTradeService.getConsecutiveBuyStocks("INSTITUTION", 2);
            instCount = (inst != null) ? inst.size() : 0;
            if (inst != null) {
                for (ConsecutiveBuyDto cb : inst) {
                    if (cb.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(cb.getStockCode(),
                            k -> new StockScore(k, cb.getStockName()));
                    int days = cb.getConsecutiveDays() != null ? cb.getConsecutiveDays() : 2;
                    double avgAmount = safeDouble(cb.getAvgDailyAmount());

                    int dayPoints = (days >= 5) ? 8 : (days >= 4) ? 6 : (days >= 3) ? 4 : 3;
                    int amountBonus = (avgAmount >= 50) ? 3 : (avgAmount >= 20) ? 1 : 0;

                    score.supplyDemand = Math.min(20, score.supplyDemand + dayPoints + amountBonus);
                    score.tags.add("기관" + days + "일연속");
                    scored++;
                }
            }
        } catch (Exception e) {
            log.warn("[종합추천] 수급 스코어 실패: {}", e.getMessage());
        }
        log.info("[종합추천] 수급: 외국인 {}건, 기관 {}건, 점수부여 {}종목", foreignCount, instCount, scored);
    }

    // ==================== ④ 기술적 위치 (/20) ====================

    private void scoreTechnical(Map<String, StockScore> scoreMap) {
        int calculated = 0, skipped = 0;
        for (StockScore stock : new ArrayList<>(scoreMap.values())) {
            try {
                List<StockPriceHistory> history = priceHistoryRepository
                        .findByStockCodeOrderByTradeDateDesc(stock.stockCode, PageRequest.of(0, 120));
                if (history == null || history.size() < 20) { skipped++; continue; }

                List<BigDecimal> prices = history.stream()
                        .sorted(Comparator.comparing(StockPriceHistory::getTradeDate))
                        .map(StockPriceHistory::getClosePrice)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                if (prices.size() < 20) { skipped++; continue; }

                TechnicalIndicatorsDto indicators = technicalIndicatorService.calculate(prices);
                if (indicators == null) { skipped++; continue; }

                int techScore = 0;

                Integer bss = indicators.getBuySignalStrength();
                if (bss != null) {
                    techScore += Math.min(12, bss * 12 / 100);
                }

                BigDecimal rsi = indicators.getRsi14();
                if (rsi != null) {
                    double rv = rsi.doubleValue();
                    if (rv >= 45 && rv <= 55) techScore += 3;
                    else if (rv >= 40 && rv <= 60) techScore += 2;
                    else if (rv >= 30 && rv < 40) techScore += 2;
                    else if (rv < 30) techScore += 1;
                }

                boolean gc = Boolean.TRUE.equals(indicators.getIsGoldenCross());
                boolean au = Boolean.TRUE.equals(indicators.getIsArrangedUp());
                if (gc && au) techScore += 5;
                else if (gc) techScore += 3;
                else if (au) techScore += 2;

                stock.technical = Math.min(20, techScore);

                if (gc) stock.tags.add("골든크로스");
                else if (au) stock.tags.add("정배열");
                if (rsi != null && rsi.intValue() < 35) stock.tags.add("RSI" + rsi.intValue());

                calculated++;
            } catch (Exception e) {
                skipped++;
            }
        }
        log.debug("[종합추천] 기술적: {}건 계산, {}건 스킵", calculated, skipped);
    }

    // ==================== ⑤ 섹터 모멘텀 (/20) ====================
    // FIX: scoreMap.get → computeIfAbsent (AI전략 빈 상태에서도 종목 추가)

    private void scoreSectorMomentum(Map<String, StockScore> scoreMap) {
        List<SectorRotationDto> topSectors = new ArrayList<>();
        try {
            List<SectorRotationDto> rotations = sectorTradingService.getSectorRotation();
            if (rotations != null && !rotations.isEmpty()) {
                topSectors = rotations.stream()
                        .filter(r -> safeDouble(r.getAvgChangeRate()) > 0 || "INFLOW".equals(r.getFlowDirection()))
                        .sorted(Comparator.comparing(r -> safeDouble(r.getAvgChangeRate()), Comparator.reverseOrder()))
                        .limit(10)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.debug("[종합추천] 섹터 로테이션 실패: {}", e.getMessage());
        }

        int marketMoodBonus = 0;
        if (!topSectors.isEmpty()) {
            double avg = topSectors.stream().mapToDouble(r -> safeDouble(r.getAvgChangeRate())).average().orElse(0);
            if (avg > 2.0) marketMoodBonus = 6;
            else if (avg > 1.0) marketMoodBonus = 4;
            else if (avg > 0.3) marketMoodBonus = 2;
        }

        int scored = 0;
        try {
            var response = aiStrategyService.getAllLatestSnapshots();
            if (response == null || response.getStrategies() == null) return;

            for (List<AiStrategySnapshotDto> stocks : response.getStrategies().values()) {
                if (stocks == null) continue;
                for (AiStrategySnapshotDto snap : stocks) {
                    if (snap.getStockCode() == null) continue;

                    // FIX: computeIfAbsent로 변경 (AI전략 빈 상태에서도 동작)
                    StockScore score = scoreMap.computeIfAbsent(snap.getStockCode(),
                            k -> new StockScore(k, snap.getStockName()));

                    int ss = 0;

                    String themes = snap.getAiThemes();
                    if (themes != null && !themes.isBlank()) {
                        int tagCount = themes.split(",").length;
                        ss += Math.min(10, 4 + tagCount * 2);
                    }

                    ss += marketMoodBonus;

                    if (snap.getChangeRate() != null) {
                        double cr = snap.getChangeRate().doubleValue();
                        if (cr > 3.0) ss += 4;
                        else if (cr > 1.5) ss += 3;
                        else if (cr > 0.5) ss += 2;
                        else if (cr > 0) ss += 1;
                    }

                    if (ss > score.sectorMomentum) {
                        score.sectorMomentum = Math.min(20, ss);
                        scored++;
                    }
                    if (snap.getChangeRate() != null && score.changeRate == null) {
                        score.changeRate = snap.getChangeRate();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 섹터 모멘텀 실패: {}", e.getMessage());
        }
        log.debug("[종합추천] 섹터: {}종목 점수 부여, 시장분위기 +{}", scored, marketMoodBonus);
    }

    // ==================== N/A 보정 ====================

    private int countValidCategories(StockScore s) {
        int count = 0;
        if (s.aiStrategy > 0) count++;
        if (s.earnings > 0) count++;
        if (s.supplyDemand > 0) count++;
        if (s.technical > 0) count++;
        if (s.sectorMomentum > 0) count++;
        return count;
    }

    // ==================== DB 복원 ====================

    private List<RecommendationDto> loadFromDb() {
        try {
            List<RecommendationSnapshot> snapshots = snapshotRepository.findLatestSnapshot();
            if (snapshots.isEmpty()) {
                log.debug("[종합추천] DB 스냅샷 없음");
                return Collections.emptyList();
            }
            List<RecommendationDto> result = snapshots.stream().map(s -> RecommendationDto.builder()
                    .stockCode(s.getStockCode()).stockName(s.getStockName())
                    .totalScore(s.getTotalScore())
                    .aiStrategy(s.getAiStrategy()).earnings(s.getEarnings())
                    .supplyDemand(s.getSupplyDemand()).technical(s.getTechnical())
                    .sectorMomentum(s.getSectorMomentum())
                    .tags(s.getTags() != null && !s.getTags().isBlank()
                            ? Arrays.asList(s.getTags().split(",")) : Collections.emptyList())
                    .changeRate(s.getChangeRate()).build()).toList();

            cachedTop5 = result;
            cacheTime = snapshots.get(0).getSnapshotAt();
            log.info("[종합추천] DB 스냅샷 {}건 복원 (시점: {})", result.size(), cacheTime);
            return result;
        } catch (Exception e) {
            log.error("[종합추천] DB 스냅샷 로드 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String getSnapshotTimeLabel() {
        if (cacheTime != null) return cacheTime.format(TIME_FMT) + " 기준 (종가)";
        return "이전 데이터";
    }

    // ==================== Utility ====================

    private double safeDouble(BigDecimal bd) {
        return bd != null ? bd.doubleValue() : 0.0;
    }

    private boolean isTradingHours(LocalDateTime now) {
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime time = now.toLocalTime();
        return time.isAfter(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(15, 35));
    }

    private RecommendationDto toDto(StockScore s) {
        int validCount = countValidCategories(s);
        int rawTotal = s.aiStrategy + s.earnings + s.supplyDemand + s.technical + s.sectorMomentum;

        // FIX: 유효항목 2개면 상한 70점, 3개 이상이어야 100점 환산
        int normalizedTotal;
        if (validCount >= 5) {
            normalizedTotal = rawTotal;
        } else if (validCount >= 3) {
            normalizedTotal = Math.min(100, rawTotal * 5 / validCount);
        } else if (validCount == 2) {
            // 2개 항목만으로는 최대 70점 (강력매수 불가)
            normalizedTotal = Math.min(70, rawTotal * 5 / validCount);
        } else {
            normalizedTotal = validCount > 0 ? Math.min(50, rawTotal * 5 / validCount) : 0;
        }

        return RecommendationDto.builder()
                .stockCode(s.stockCode).stockName(s.stockName)
                .totalScore(normalizedTotal)
                .aiStrategy(s.aiStrategy > 0 ? s.aiStrategy : NA)
                .earnings(s.earnings > 0 ? s.earnings : NA)
                .supplyDemand(s.supplyDemand > 0 ? s.supplyDemand : NA)
                .technical(s.technical > 0 ? s.technical : NA)
                .sectorMomentum(s.sectorMomentum > 0 ? s.sectorMomentum : NA)
                .validCount(validCount)
                .tags(new ArrayList<>(s.tags))
                .changeRate(s.changeRate).build();
    }

    // ==================== Inner Classes ====================

    private static class StockScore {
        String stockCode;
        String stockName;
        int aiStrategy = 0;
        int earnings = 0;
        int supplyDemand = 0;
        int technical = 0;
        int sectorMomentum = 0;
        Set<String> tags = new LinkedHashSet<>();
        BigDecimal changeRate;

        StockScore(String code, String name) { stockCode = code; stockName = name; }

        int getNormalizedTotal() {
            int valid = 0, sum = 0;
            if (aiStrategy > 0) { valid++; sum += aiStrategy; }
            if (earnings > 0) { valid++; sum += earnings; }
            if (supplyDemand > 0) { valid++; sum += supplyDemand; }
            if (technical > 0) { valid++; sum += technical; }
            if (sectorMomentum > 0) { valid++; sum += sectorMomentum; }
            if (valid >= 5) return sum;
            if (valid >= 3) return Math.min(100, sum * 5 / valid);
            if (valid == 2) return Math.min(70, sum * 5 / valid);
            return valid > 0 ? Math.min(50, sum * 5 / valid) : 0;
        }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecommendationDto {
        private String stockCode;
        private String stockName;
        private int totalScore;
        private int aiStrategy;
        private int earnings;
        private int supplyDemand;
        private int technical;
        private int sectorMomentum;
        private int validCount;  // 유효 항목 수 (UI에서 신뢰도 표시용)
        private List<String> tags;
        private BigDecimal changeRate;
    }

    @Getter @AllArgsConstructor
    public static class Top5Response {
        private final List<RecommendationDto> items;
        private final String dataTime;
        private final boolean realtime;
        private final Map<String, Integer> delta;
    }
}
