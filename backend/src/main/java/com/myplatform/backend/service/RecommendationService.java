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
import java.math.RoundingMode;
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
 * - AI전략 신호: 전략별 순위 + aiScore 반영
 * - 실적 개선세: 영업이익 증가율 크기에 따라 차등
 * - 기관/외국인 수급: 연속일수 + 순매수 금액 강도
 * - 기술적 위치: RSI + 이평선 + buySignalStrength 조합
 * - 섹터 모멘텀: 섹터 등락률 + 자금유입 방향
 *
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
    private static final int NA = -1; // 데이터 없음 표시

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    // ==================== Public API ====================

    public Top5Response getTop5() {
        LocalDateTime now = LocalDateTime.now();
        boolean trading = isTradingHours(now);

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

        if (cachedTop5 != null && !cachedTop5.isEmpty()) {
            String label = cacheTime != null ? cacheTime.format(TIME_FMT) + " 기준" : "캐시 데이터";
            return new Top5Response(cachedTop5, label, !trading);
        }

        List<RecommendationDto> fromDb = loadFromDb();
        if (!fromDb.isEmpty()) {
            cachedTop5 = fromDb;
            List<RecommendationSnapshot> snapshots = snapshotRepository.findLatestSnapshot();
            LocalDateTime snapTime = snapshots.isEmpty() ? now : snapshots.get(0).getSnapshotAt();
            cacheTime = snapTime;
            return new Top5Response(fromDb, snapTime.format(TIME_FMT) + " 기준 (종가)", false);
        }

        return new Top5Response(Collections.emptyList(), "", false);
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

        // 1단계: 후보 종목 수집 (AI 전략에서 시드)
        scoreAiStrategy(scoreMap);

        // 2단계: 나머지 항목 채점 (후보 + 추가 종목)
        scoreEarnings(scoreMap);
        scoreSupplyDemand(scoreMap);

        // 3단계: 기술적/섹터 — 후보 종목에 대해서만 계산 (API 호출 최소화)
        scoreTechnical(scoreMap);
        scoreSectorMomentum(scoreMap);

        // 4단계: N/A 보정 후 정렬
        List<RecommendationDto> results = scoreMap.values().stream()
                .filter(s -> countValidCategories(s) >= 2) // 최소 2개 항목 데이터 필요
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
    // 데이터 소스: AiStrategySnapshotService (Gemini AI 분석 결과)
    // 차등 기준: 전략별 순위 가산 + aiScore 반영

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

                    // 순위 기본점: 1위 +6, 2위 +4, 3위 +2
                    int rankPoints = (i == 0) ? 6 : (i == 1) ? 4 : 2;

                    // aiScore 보너스 (0~100 → 0~8점)
                    int aiBonus = 0;
                    if (snap.getAiScore() != null && snap.getAiScore() > 0) {
                        aiBonus = Math.min(8, snap.getAiScore() / 12); // 96점→8, 60점→5, 36점→3
                    }

                    // 복수 전략 등장 보너스: 이미 점수가 있으면 +3
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
    // 데이터 소스: EarningSurpriseService (영업이익 QoQ 변동률)
    // 차등 기준: 증가율 크기 → 8~20점 스케일

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
                    // 흑자전환 = 무조건 최고점
                    score.earnings = 20;
                    score.tags.add("흑자전환");
                } else {
                    // POSITIVE: 영업이익 증가율에 따라 차등
                    double changeRate = safeDouble(s.getOperatingProfitChangeRate());
                    if (changeRate >= 100) {
                        score.earnings = 20; // 100%↑ 급증
                        score.tags.add("실적급증+" + (int) changeRate + "%");
                    } else if (changeRate >= 50) {
                        score.earnings = 16; // 50~99%
                        score.tags.add("실적개선+" + (int) changeRate + "%");
                    } else if (changeRate >= 30) {
                        score.earnings = 12; // 30~49%
                        score.tags.add("실적개선+" + (int) changeRate + "%");
                    } else {
                        score.earnings = 8; // 20~29%
                        score.tags.add("실적소폭↑");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 실적 스코어 실패: {}", e.getMessage());
        }
    }

    // ==================== ③ 기관/외국인 수급 (/20) ====================
    // 데이터 소스: InvestorTradeService (KIS API 투자자 매매)
    // 차등 기준: 연속일수 + 순매수 금액 강도

    private void scoreSupplyDemand(Map<String, StockScore> scoreMap) {
        try {
            // 외국인
            List<ConsecutiveBuyDto> foreign = investorTradeService.getConsecutiveBuyStocks("FOREIGN", 3);
            if (foreign != null) {
                for (ConsecutiveBuyDto cb : foreign) {
                    if (cb.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(cb.getStockCode(),
                            k -> new StockScore(k, cb.getStockName()));

                    int days = cb.getConsecutiveDays() != null ? cb.getConsecutiveDays() : 3;
                    double avgAmount = safeDouble(cb.getAvgDailyAmount()); // 억원

                    // 일수 기본: 3일 6점, 4일 8점, 5일+ 10점
                    int dayPoints = (days >= 5) ? 10 : (days >= 4) ? 8 : 6;

                    // 금액 보너스: 일평균 50억↑ +4, 20억↑ +2
                    int amountBonus = (avgAmount >= 50) ? 4 : (avgAmount >= 20) ? 2 : 0;

                    score.supplyDemand = Math.min(20, score.supplyDemand + dayPoints + amountBonus);
                    String amountStr = avgAmount >= 1 ? String.format("(일%.0f억)", avgAmount) : "";
                    score.tags.add("외국인" + days + "일연속" + amountStr);
                    if (cb.getChangeRate() != null) score.changeRate = cb.getChangeRate();
                }
            }

            // 기관
            List<ConsecutiveBuyDto> inst = investorTradeService.getConsecutiveBuyStocks("INSTITUTION", 3);
            if (inst != null) {
                for (ConsecutiveBuyDto cb : inst) {
                    if (cb.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(cb.getStockCode(),
                            k -> new StockScore(k, cb.getStockName()));

                    int days = cb.getConsecutiveDays() != null ? cb.getConsecutiveDays() : 3;
                    double avgAmount = safeDouble(cb.getAvgDailyAmount());

                    int dayPoints = (days >= 5) ? 8 : (days >= 4) ? 6 : 4;
                    int amountBonus = (avgAmount >= 50) ? 3 : (avgAmount >= 20) ? 1 : 0;

                    score.supplyDemand = Math.min(20, score.supplyDemand + dayPoints + amountBonus);
                    score.tags.add("기관" + days + "일연속");
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 수급 스코어 실패: {}", e.getMessage());
        }
    }

    // ==================== ④ 기술적 위치 (/20) ====================
    // 데이터 소스: TechnicalIndicatorService (RSI, 이평선, buySignalStrength)
    //             + StockPriceHistoryRepository (일봉 120일)
    // 차등 기준: buySignalStrength(0~100) → 0~14점 + 개별 보너스

    private void scoreTechnical(Map<String, StockScore> scoreMap) {
        for (StockScore stock : scoreMap.values()) {
            try {
                // 가격 히스토리 조회 (기술적 지표 계산용)
                List<StockPriceHistory> history = priceHistoryRepository
                        .findByStockCodeOrderByTradeDateDesc(stock.stockCode, PageRequest.of(0, 120));
                if (history == null || history.size() < 20) continue; // 데이터 부족

                // 오래된 순서로 변환
                List<BigDecimal> prices = history.stream()
                        .sorted(Comparator.comparing(StockPriceHistory::getTradeDate))
                        .map(StockPriceHistory::getClosePrice)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                if (prices.size() < 20) continue;

                TechnicalIndicatorsDto indicators = technicalIndicatorService.calculate(prices);
                if (indicators == null) continue;

                int techScore = 0;

                // buySignalStrength (0~100) → 0~14점
                Integer bss = indicators.getBuySignalStrength();
                if (bss != null) {
                    techScore += Math.min(14, bss / 7); // 98→14, 70→10, 42→6, 14→2
                }

                // RSI 보너스 (매수 영역이면 가산)
                BigDecimal rsi = indicators.getRsi14();
                if (rsi != null) {
                    double rsiVal = rsi.doubleValue();
                    if (rsiVal >= 40 && rsiVal <= 60) techScore += 2;      // 중립 안정
                    else if (rsiVal >= 30 && rsiVal < 40) techScore += 3;  // 과매도 근접 = 기회
                    else if (rsiVal < 30) techScore += 1;                   // 과매도 = 리스크도 있음
                }

                // 이평선 보너스
                if (Boolean.TRUE.equals(indicators.getIsArrangedUp())) techScore += 2; // 정배열
                if (Boolean.TRUE.equals(indicators.getIsGoldenCross())) techScore += 2; // 골든크로스

                stock.technical = Math.min(20, techScore);

                // 태그 생성
                if (Boolean.TRUE.equals(indicators.getIsGoldenCross())) stock.tags.add("골든크로스");
                if (Boolean.TRUE.equals(indicators.getIsArrangedUp())) stock.tags.add("정배열");
                if (rsi != null && rsi.doubleValue() < 35) stock.tags.add("RSI" + rsi.intValue());

            } catch (Exception e) {
                log.debug("[종합추천] 기술적 스코어 실패 {}: {}", stock.stockCode, e.getMessage());
                // 개별 종목 실패 시 continue (다른 종목은 정상 처리)
            }
        }
    }

    // ==================== ⑤ 섹터 모멘텀 (/20) ====================
    // 데이터 소스: SectorTradingService (섹터별 등락률 + 자금흐름)
    //             + AiStrategySnapshotDto.aiThemes (AI 테마 태그)
    // 차등 기준: 섹터 등락률 순위 + 자금 유입 방향

    private void scoreSectorMomentum(Map<String, StockScore> scoreMap) {
        // 섹터 로테이션 데이터 로드
        Map<String, SectorRotationDto> sectorMap = new HashMap<>();
        try {
            List<SectorRotationDto> rotations = sectorTradingService.getSectorRotation();
            if (rotations != null) {
                // 등락률 내림차순 정렬 → 순위 부여
                rotations.sort(Comparator.comparing(
                        r -> r.getAvgChangeRate() != null ? r.getAvgChangeRate() : BigDecimal.ZERO,
                        Comparator.reverseOrder()));
                for (SectorRotationDto r : rotations) {
                    if (r.getSectorName() != null) {
                        sectorMap.put(r.getSectorName(), r);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 섹터 로테이션 로드 실패: {}", e.getMessage());
        }

        // AI 테마 + 섹터 데이터 조합
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

                    // AI 테마 태그 기반 (0~10점)
                    String themes = snap.getAiThemes();
                    if (themes != null && !themes.isBlank()) {
                        int tagCount = themes.split(",").length;
                        sectorScore += Math.min(10, 4 + tagCount * 2); // 1태그=6, 2태그=8, 3태그=10
                    }

                    // 섹터 등락률 기반 (0~10점) — 섹터 매칭 시도
                    if (!sectorMap.isEmpty()) {
                        // 테마 키워드로 섹터 매칭
                        for (SectorRotationDto sector : sectorMap.values()) {
                            double avgChange = safeDouble(sector.getAvgChangeRate());
                            boolean isInflow = "INFLOW".equals(sector.getFlowDirection());

                            if (avgChange > 2.0 && isInflow) {
                                sectorScore += 8; // 강한 상승 + 자금유입
                            } else if (avgChange > 1.0) {
                                sectorScore += 5; // 보통 상승
                            } else if (avgChange > 0 && isInflow) {
                                sectorScore += 3; // 약한 상승 + 자금유입
                            }
                            break; // 첫 매칭만 사용
                        }
                    }

                    score.sectorMomentum = Math.min(20, Math.max(score.sectorMomentum, sectorScore));
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 섹터 모멘텀 스코어 실패: {}", e.getMessage());
        }
    }

    // ==================== N/A 보정 & 점수 정규화 ====================

    /** 유효한 (N/A가 아닌) 항목 수 */
    private int countValidCategories(StockScore s) {
        int count = 0;
        if (s.aiStrategy > 0) count++;
        if (s.earnings > 0) count++;
        if (s.supplyDemand > 0) count++;
        if (s.technical > 0) count++;
        if (s.sectorMomentum > 0) count++;
        return count;
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

    private List<RecommendationDto> loadFromDb() {
        try {
            List<RecommendationSnapshot> snapshots = snapshotRepository.findLatestSnapshot();
            if (snapshots.isEmpty()) return Collections.emptyList();
            return snapshots.stream().map(s -> RecommendationDto.builder()
                    .stockCode(s.getStockCode()).stockName(s.getStockName())
                    .totalScore(s.getTotalScore())
                    .aiStrategy(s.getAiStrategy()).earnings(s.getEarnings())
                    .supplyDemand(s.getSupplyDemand()).technical(s.getTechnical())
                    .sectorMomentum(s.getSectorMomentum())
                    .tags(s.getTags() != null && !s.getTags().isBlank()
                            ? Arrays.asList(s.getTags().split(",")) : Collections.emptyList())
                    .changeRate(s.getChangeRate()).build()).toList();
        } catch (Exception e) {
            log.error("[종합추천] DB 스냅샷 로드 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private RecommendationDto toDto(StockScore s) {
        int validCount = countValidCategories(s);
        // N/A 보정: 유효 항목만으로 100점 기준 환산
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

        int getRawTotal() {
            return Math.max(0, aiStrategy) + Math.max(0, earnings)
                    + Math.max(0, supplyDemand) + Math.max(0, technical)
                    + Math.max(0, sectorMomentum);
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
        private int aiStrategy;      // /20 or -1 (N/A)
        private int earnings;        // /20 or -1 (N/A)
        private int supplyDemand;    // /20 or -1 (N/A)
        private int technical;       // /20 or -1 (N/A)
        private int sectorMomentum;  // /20 or -1 (N/A)
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
