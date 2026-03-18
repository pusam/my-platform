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
 * AI 종합 추천 TOP 5 — 차등 스코어링 엔진
 *
 * 5개 항목 각 /20, 합계 /100
 * 데이터 없는 항목 = -1 (N/A) → 배점 제외 후 점수 재계산
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

        // ① 장중: 실시간 계산 (30분 캐시)
        if (trading) {
            if (cachedTop5 != null && cacheTime != null
                    && cacheTime.isAfter(now.minusMinutes(CACHE_MINUTES))) {
                return new Top5Response(cachedTop5, cacheTime.format(TIME_FMT) + " 기준", true);
            }
            try {
                List<RecommendationDto> result = calculate();
                if (!result.isEmpty()) {
                    cachedTop5 = result;
                    cacheTime = now;
                    return new Top5Response(result, now.format(TIME_FMT) + " 기준", true);
                }
            } catch (Exception e) {
                log.error("[종합추천] 실시간 계산 실패: {}", e.getMessage());
            }
        }

        // ② 인메모리 캐시 (장 외에도 유효 — 만료 없음)
        if (cachedTop5 != null && !cachedTop5.isEmpty()) {
            String label = cacheTime != null ? cacheTime.format(TIME_FMT) + " 기준" : "캐시 데이터";
            return new Top5Response(cachedTop5, label, false);
        }

        // ③ DB 스냅샷에서 복원
        List<RecommendationDto> fromDb = loadFromDb();
        if (!fromDb.isEmpty()) {
            return new Top5Response(fromDb, getSnapshotTimeLabel(), false);
        }

        // ④ 최후 수단: 장 외라도 실시간 계산 시도
        try {
            List<RecommendationDto> result = calculate();
            if (!result.isEmpty()) {
                cachedTop5 = result;
                cacheTime = now;
                return new Top5Response(result, now.format(TIME_FMT) + " 기준", false);
            }
        } catch (Exception e) {
            log.debug("[종합추천] 장 외 폴백 계산 실패: {}", e.getMessage());
        }

        return new Top5Response(Collections.emptyList(), "", false);
    }

    /** 장 마감 후 DB에 스냅샷 저장 — 평일 15:45 */
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

        scoreAiStrategy(scoreMap);
        scoreEarnings(scoreMap);
        scoreSupplyDemand(scoreMap);
        scoreTechnical(scoreMap);
        scoreSectorMomentum(scoreMap);

        List<RecommendationDto> results = scoreMap.values().stream()
                .filter(s -> countValidCategories(s) >= 2)
                .sorted(Comparator.comparingInt(StockScore::getNormalizedTotal).reversed()
                        .thenComparing(s -> s.changeRate != null ? s.changeRate.doubleValue() : 0.0,
                                Comparator.reverseOrder()))
                .limit(5)
                .map(this::toDto)
                .toList();

        log.info("[종합추천] TOP {} 계산 완료 (scoreMap={}종목)", results.size(), scoreMap.size());
        return results;
    }

    // ==================== ① AI전략 신호 (/20) ====================

    private void scoreAiStrategy(Map<String, StockScore> scoreMap) {
        try {
            var response = aiStrategyService.getAllLatestSnapshots();
            if (response == null || response.getStrategies() == null) return;

            for (Map.Entry<String, List<AiStrategySnapshotDto>> entry : response.getStrategies().entrySet()) {
                List<AiStrategySnapshotDto> stocks = entry.getValue();
                if (stocks == null) continue;

                for (int i = 0; i < Math.min(3, stocks.size()); i++) {
                    AiStrategySnapshotDto snap = stocks.get(i);
                    if (snap.getStockCode() == null) continue;

                    StockScore score = scoreMap.computeIfAbsent(snap.getStockCode(),
                            k -> new StockScore(k, snap.getStockName()));

                    int rankPoints = (i == 0) ? 6 : (i == 1) ? 4 : 2;
                    int aiBonus = 0;
                    if (snap.getAiScore() != null && snap.getAiScore() > 0) {
                        aiBonus = Math.min(8, snap.getAiScore() / 12);
                    }
                    int multiBonus = (score.aiStrategy > 0) ? 3 : 0;

                    score.aiStrategy = Math.min(20, score.aiStrategy + rankPoints + aiBonus + multiBonus);

                    String[] labels = {"AI전략1위", "AI전략2위", "AI전략3위"};
                    score.tags.add(labels[i]);
                    if (snap.getChangeRate() != null) score.changeRate = snap.getChangeRate();
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] AI 전략 스코어 실패: {}", e.getMessage());
        }
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
    // FIX: minDays를 2로 낮추고, 단일 투자자 데이터도 활용

    private void scoreSupplyDemand(Map<String, StockScore> scoreMap) {
        int foreignCount = 0, instCount = 0;

        try {
            // 외국인 연속매수 (최소 2일로 완화 — 기존 3일이면 데이터 없는 경우 多)
            List<ConsecutiveBuyDto> foreign = investorTradeService.getConsecutiveBuyStocks("FOREIGN", 2);
            if (foreign != null && !foreign.isEmpty()) {
                foreignCount = foreign.size();
                for (ConsecutiveBuyDto cb : foreign) {
                    if (cb.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(cb.getStockCode(),
                            k -> new StockScore(k, cb.getStockName()));

                    int days = cb.getConsecutiveDays() != null ? cb.getConsecutiveDays() : 2;
                    double avgAmount = safeDouble(cb.getAvgDailyAmount());

                    // 일수: 2일 4점, 3일 6점, 4일 8점, 5일+ 10점
                    int dayPoints = (days >= 5) ? 10 : (days >= 4) ? 8 : (days >= 3) ? 6 : 4;
                    // 금액 보너스: 50억+ 4점, 20억+ 2점, 5억+ 1점
                    int amountBonus = (avgAmount >= 50) ? 4 : (avgAmount >= 20) ? 2 : (avgAmount >= 5) ? 1 : 0;

                    score.supplyDemand = Math.min(20, score.supplyDemand + dayPoints + amountBonus);

                    String amountStr = avgAmount >= 1 ? String.format("(일%.0f억)", avgAmount) : "";
                    score.tags.add("외국인" + days + "일연속" + amountStr);
                    if (cb.getChangeRate() != null) score.changeRate = cb.getChangeRate();
                }
            }

            // 기관 연속매수
            List<ConsecutiveBuyDto> inst = investorTradeService.getConsecutiveBuyStocks("INSTITUTION", 2);
            if (inst != null && !inst.isEmpty()) {
                instCount = inst.size();
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
                }
            }

            log.debug("[종합추천] 수급 데이터: 외국인 {}건, 기관 {}건", foreignCount, instCount);
        } catch (Exception e) {
            log.warn("[종합추천] 수급 스코어 실패: {}", e.getMessage());
        }
    }

    // ==================== ④ 기술적 위치 (/20) ====================
    // FIX: buySignalStrength 선형 스케일링 (정수 나눗셈 → 비례 변환)

    private void scoreTechnical(Map<String, StockScore> scoreMap) {
        int calculated = 0, skipped = 0;

        for (StockScore stock : scoreMap.values()) {
            try {
                List<StockPriceHistory> history = priceHistoryRepository
                        .findByStockCodeOrderByTradeDateDesc(stock.stockCode, PageRequest.of(0, 120));
                if (history == null || history.size() < 20) {
                    skipped++;
                    continue;
                }

                List<BigDecimal> prices = history.stream()
                        .sorted(Comparator.comparing(StockPriceHistory::getTradeDate))
                        .map(StockPriceHistory::getClosePrice)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                if (prices.size() < 20) { skipped++; continue; }

                TechnicalIndicatorsDto indicators = technicalIndicatorService.calculate(prices);
                if (indicators == null) { skipped++; continue; }

                int techScore = 0;

                // FIX: 선형 비례 스케일링 (0~100 → 0~12점)
                // 기존: bss / 7 (정수 나눗셈, 대부분 8~10 고정)
                // 변경: bss * 12 / 100 (0=0, 50=6, 75=9, 100=12)
                Integer bss = indicators.getBuySignalStrength();
                if (bss != null) {
                    techScore += Math.min(12, bss * 12 / 100);
                }

                // RSI 차등 (기존보다 세분화)
                BigDecimal rsi = indicators.getRsi14();
                if (rsi != null) {
                    double rsiVal = rsi.doubleValue();
                    if (rsiVal >= 45 && rsiVal <= 55) techScore += 3;       // 이상적 중립
                    else if (rsiVal >= 40 && rsiVal <= 60) techScore += 2;  // 안정 영역
                    else if (rsiVal >= 30 && rsiVal < 40) techScore += 2;   // 과매도 근접 = 기회
                    else if (rsiVal < 30) techScore += 1;                    // 과매도 = 리스크
                    // 60~70: 0점 (과열 진입), 70+: 0점 (과열)
                }

                // 정배열/골든크로스 — 둘 다 있으면 최대 +5
                if (Boolean.TRUE.equals(indicators.getIsGoldenCross())) techScore += 3;
                else if (Boolean.TRUE.equals(indicators.getIsArrangedUp())) techScore += 2;

                stock.technical = Math.min(20, techScore);

                // 태그
                if (Boolean.TRUE.equals(indicators.getIsGoldenCross())) stock.tags.add("골든크로스");
                else if (Boolean.TRUE.equals(indicators.getIsArrangedUp())) stock.tags.add("정배열");
                if (rsi != null) {
                    int rsiInt = rsi.intValue();
                    if (rsiInt < 35) stock.tags.add("RSI" + rsiInt);
                    else if (rsiInt > 70) stock.tags.add("RSI과열" + rsiInt);
                }
                if (bss != null) stock.tags.add("기술" + bss + "점");

                calculated++;
            } catch (Exception e) {
                skipped++;
                log.debug("[종합추천] 기술적 스코어 실패 {}: {}", stock.stockCode, e.getMessage());
            }
        }
        log.debug("[종합추천] 기술적: 계산 {}건, 스킵 {}건", calculated, skipped);
    }

    // ==================== ⑤ 섹터 모멘텀 (/20) ====================
    // FIX: 섹터 매칭 로직 개선 + AI테마만으로도 점수 부여

    private void scoreSectorMomentum(Map<String, StockScore> scoreMap) {
        // 섹터 로테이션 데이터
        List<SectorRotationDto> topSectors = new ArrayList<>();
        try {
            List<SectorRotationDto> rotations = sectorTradingService.getSectorRotation();
            if (rotations != null && !rotations.isEmpty()) {
                // 상위 섹터만 필터 (등락률 > 0 OR 자금유입)
                topSectors = rotations.stream()
                        .filter(r -> safeDouble(r.getAvgChangeRate()) > 0
                                || "INFLOW".equals(r.getFlowDirection()))
                        .sorted(Comparator.comparing(
                                r -> safeDouble(r.getAvgChangeRate()),
                                Comparator.reverseOrder()))
                        .limit(10)
                        .collect(Collectors.toList());
                log.debug("[종합추천] 섹터 로테이션 로드: {}개 섹터 (상위 {}개)", rotations.size(), topSectors.size());
            }
        } catch (Exception e) {
            log.debug("[종합추천] 섹터 로테이션 로드 실패: {}", e.getMessage());
        }

        // 전체 시장 분위기 점수 (상위 섹터 평균 등락률 기반)
        int marketMoodBonus = 0;
        if (!topSectors.isEmpty()) {
            double avgTopChange = topSectors.stream()
                    .mapToDouble(r -> safeDouble(r.getAvgChangeRate()))
                    .average().orElse(0);
            if (avgTopChange > 2.0) marketMoodBonus = 6;
            else if (avgTopChange > 1.0) marketMoodBonus = 4;
            else if (avgTopChange > 0.3) marketMoodBonus = 2;
        }

        try {
            var response = aiStrategyService.getAllLatestSnapshots();
            if (response == null || response.getStrategies() == null) return;

            for (List<AiStrategySnapshotDto> stocks : response.getStrategies().values()) {
                if (stocks == null) continue;
                for (AiStrategySnapshotDto snap : stocks) {
                    if (snap.getStockCode() == null) continue;
                    StockScore score = scoreMap.get(snap.getStockCode());
                    if (score == null) continue;

                    int sectorScore = 0;

                    // AI 테마 태그 기반 (0~10점) — 테마 있으면 무조건 최소 점수 부여
                    String themes = snap.getAiThemes();
                    if (themes != null && !themes.isBlank()) {
                        int tagCount = themes.split(",").length;
                        sectorScore += Math.min(10, 4 + tagCount * 2);
                    }

                    // 시장 분위기 보너스 (전체 시장 기반, 0~6)
                    sectorScore += marketMoodBonus;

                    // 종목 자체 등락률 보너스 (0~4)
                    if (snap.getChangeRate() != null) {
                        double cr = snap.getChangeRate().doubleValue();
                        if (cr > 3.0) sectorScore += 4;
                        else if (cr > 1.5) sectorScore += 3;
                        else if (cr > 0.5) sectorScore += 2;
                        else if (cr > 0) sectorScore += 1;
                    }

                    score.sectorMomentum = Math.min(20, Math.max(score.sectorMomentum, sectorScore));
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 섹터 모멘텀 스코어 실패: {}", e.getMessage());
        }
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

            // 캐시에도 저장
            List<RecommendationDto> result = snapshots.stream().map(s -> RecommendationDto.builder()
                    .stockCode(s.getStockCode()).stockName(s.getStockName())
                    .totalScore(s.getTotalScore())
                    .aiStrategy(s.getAiStrategy()).earnings(s.getEarnings())
                    .supplyDemand(s.getSupplyDemand()).technical(s.getTechnical())
                    .sectorMomentum(s.getSectorMomentum())
                    .tags(s.getTags() != null && !s.getTags().isBlank()
                            ? Arrays.asList(s.getTags().split(",")) : Collections.emptyList())
                    .changeRate(s.getChangeRate()).build()).toList();

            // 인메모리 캐시도 갱신
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
        try {
            List<RecommendationSnapshot> snapshots = snapshotRepository.findLatestSnapshot();
            if (!snapshots.isEmpty()) {
                return snapshots.get(0).getSnapshotAt().format(TIME_FMT) + " 기준 (종가)";
            }
        } catch (Exception e) {
            log.debug("[종합추천] 스냅샷 시점 조회 실패");
        }
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
        int normalizedTotal = (validCount >= 5) ? rawTotal
                : (validCount > 0) ? Math.min(100, rawTotal * 5 / validCount) : 0;

        return RecommendationDto.builder()
                .stockCode(s.stockCode)
                .stockName(s.stockName)
                .totalScore(normalizedTotal)
                .aiStrategy(s.aiStrategy)
                .earnings(s.earnings > 0 ? s.earnings : NA)
                .supplyDemand(s.supplyDemand > 0 ? s.supplyDemand : NA)
                .technical(s.technical > 0 ? s.technical : NA)
                .sectorMomentum(s.sectorMomentum > 0 ? s.sectorMomentum : NA)
                .tags(new ArrayList<>(s.tags))
                .changeRate(s.changeRate)
                .build();
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

        StockScore(String stockCode, String stockName) {
            this.stockCode = stockCode;
            this.stockName = stockName;
        }

        int getNormalizedTotal() {
            int valid = 0, sum = 0;
            if (aiStrategy > 0) { valid++; sum += aiStrategy; }
            if (earnings > 0) { valid++; sum += earnings; }
            if (supplyDemand > 0) { valid++; sum += supplyDemand; }
            if (technical > 0) { valid++; sum += technical; }
            if (sectorMomentum > 0) { valid++; sum += sectorMomentum; }
            if (valid >= 5) return sum;
            return valid > 0 ? Math.min(100, sum * 5 / valid) : 0;
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
        private List<String> tags;
        private BigDecimal changeRate;
    }

    @Getter @AllArgsConstructor
    public static class Top5Response {
        private final List<RecommendationDto> items;
        private final String dataTime;
        private final boolean realtime;
    }
}
