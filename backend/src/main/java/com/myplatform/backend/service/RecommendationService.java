package com.myplatform.backend.service;

import com.myplatform.backend.dto.AiStrategySnapshotDto;
import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.EarningSurpriseDto;
import com.myplatform.backend.entity.AiStrategySnapshot.StrategyType;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 종합 추천 TOP 5
 * 5가지 항목별 20점 만점, 합계 100점
 * - AI전략 신호 (/20)
 * - 실적 개선세 (/20)
 * - 기관/외국인 수급 (/20)
 * - 기술적 위치 (/20)
 * - 섹터 모멘텀 (/20)
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

    // 캐시 (30분)
    private volatile List<RecommendationDto> cachedTop5 = null;
    private volatile LocalDateTime cacheTime = null;
    private static final long CACHE_MINUTES = 30;

    public List<RecommendationDto> getTop5() {
        if (cachedTop5 != null && cacheTime != null
                && cacheTime.isAfter(LocalDateTime.now().minusMinutes(CACHE_MINUTES))) {
            return cachedTop5;
        }

        try {
            List<RecommendationDto> result = calculate();
            cachedTop5 = result;
            cacheTime = LocalDateTime.now();
            return result;
        } catch (Exception e) {
            log.error("[종합추천] 계산 실패: {}", e.getMessage());
            return cachedTop5 != null ? cachedTop5 : Collections.emptyList();
        }
    }

    private List<RecommendationDto> calculate() {
        Map<String, StockScore> scoreMap = new HashMap<>();

        // ① AI전략 신호 (/20)
        scoreAiStrategy(scoreMap);

        // ② 실적 개선세 (/20)
        scoreEarnings(scoreMap);

        // ③ 기관/외국인 수급 (/20)
        scoreSupplyDemand(scoreMap);

        // ④ 기술적 위치 (/20) — 선점 레이더 기반
        scoreTechnical(scoreMap);

        // ⑤ 섹터 모멘텀 (/20) — AI 테마 기반
        scoreSectorMomentum(scoreMap);

        // 결과 정렬: 총점 내림차순 → 동점 시 등락률 높은 순
        List<RecommendationDto> results = scoreMap.values().stream()
                .filter(s -> s.getTotalScore() >= 20 && !s.tags.isEmpty())
                .sorted(Comparator.comparingInt(StockScore::getTotalScore).reversed()
                        .thenComparing(s -> s.changeRate != null ? s.changeRate.doubleValue() : 0.0,
                                Comparator.reverseOrder()))
                .limit(5)
                .map(s -> RecommendationDto.builder()
                        .stockCode(s.stockCode)
                        .stockName(s.stockName)
                        .totalScore(s.getTotalScore())
                        .aiStrategy(s.aiStrategy)
                        .earnings(s.earnings)
                        .supplyDemand(s.supplyDemand)
                        .technical(s.technical)
                        .sectorMomentum(s.sectorMomentum)
                        .tags(new ArrayList<>(s.tags))
                        .changeRate(s.changeRate)
                        .build())
                .toList();

        log.info("[종합추천] TOP {} 계산 완료", results.size());
        return results;
    }

    /** ① AI전략 신호 (/20): 전략별 순위에 따른 점수 */
    private void scoreAiStrategy(Map<String, StockScore> scoreMap) {
        try {
            var response = aiStrategyService.getAllLatestSnapshots();
            if (response == null || response.getStrategies() == null) return;

            // 전략별 1~3위 종목에 가산점
            for (Map.Entry<String, List<AiStrategySnapshotDto>> entry : response.getStrategies().entrySet()) {
                List<AiStrategySnapshotDto> stocks = entry.getValue();
                if (stocks == null) continue;

                for (int i = 0; i < Math.min(3, stocks.size()); i++) {
                    AiStrategySnapshotDto snap = stocks.get(i);
                    if (snap.getStockCode() == null) continue;

                    StockScore score = scoreMap.computeIfAbsent(snap.getStockCode(),
                            k -> new StockScore(k, snap.getStockName()));

                    // 1위 +8, 2위 +5, 3위 +3 (여러 전략에서 중복 가산, 최대 20)
                    int points = (i == 0) ? 8 : (i == 1) ? 5 : 3;
                    score.aiStrategy = Math.min(20, score.aiStrategy + points);

                    String[] labels = {"AI전략1위", "AI전략2위", "AI전략3위"};
                    score.tags.add(labels[i]);

                    if (snap.getChangeRate() != null) {
                        score.changeRate = snap.getChangeRate();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] AI 전략 스코어 실패: {}", e.getMessage());
        }
    }

    /** ② 실적 개선세 (/20): 어닝 서프라이즈 기반 */
    private void scoreEarnings(Map<String, StockScore> scoreMap) {
        try {
            var surprises = earningSurpriseService.detectEarningSurprises();
            if (surprises == null) return;

            for (EarningSurpriseDto s : surprises) {
                if (s.getStockCode() == null) continue;
                String type = s.getSurpriseType() != null ? s.getSurpriseType().toString() : "";
                if ("POSITIVE".equals(type) || "TURNAROUND".equals(type)) {
                    StockScore score = scoreMap.computeIfAbsent(s.getStockCode(),
                            k -> new StockScore(k, s.getStockName()));
                    // 흑자전환 20점, 실적개선 16점
                    score.earnings = "TURNAROUND".equals(type) ? 20 : 16;
                    score.tags.add("TURNAROUND".equals(type) ? "흑자전환" : "실적개선");
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 실적 스코어 실패: {}", e.getMessage());
        }
    }

    /** ③ 기관/외국인 수급 (/20): 연속매수일수 기반 */
    private void scoreSupplyDemand(Map<String, StockScore> scoreMap) {
        try {
            // 외국인 연속매수
            List<ConsecutiveBuyDto> foreign = investorTradeService.getConsecutiveBuyStocks("FOREIGN", 3);
            if (foreign != null) {
                for (ConsecutiveBuyDto cb : foreign) {
                    if (cb.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(cb.getStockCode(),
                            k -> new StockScore(k, cb.getStockName()));
                    int days = cb.getConsecutiveDays() != null ? cb.getConsecutiveDays() : 3;
                    // 5일+ → 14점, 3~4일 → 10점
                    int points = (days >= 5) ? 14 : 10;
                    score.supplyDemand = Math.min(20, score.supplyDemand + points);
                    score.tags.add("외국인" + days + "일연속");
                    if (cb.getChangeRate() != null) score.changeRate = cb.getChangeRate();
                }
            }

            // 기관 연속매수
            List<ConsecutiveBuyDto> inst = investorTradeService.getConsecutiveBuyStocks("INSTITUTION", 3);
            if (inst != null) {
                for (ConsecutiveBuyDto cb : inst) {
                    if (cb.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(cb.getStockCode(),
                            k -> new StockScore(k, cb.getStockName()));
                    score.supplyDemand = Math.min(20, score.supplyDemand + 8);
                    score.tags.add("기관연속매수");
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 수급 스코어 실패: {}", e.getMessage());
        }
    }

    /** ④ 기술적 위치 (/20): 신고가 직전, 대량취득 공시 */
    private void scoreTechnical(Map<String, StockScore> scoreMap) {
        try {
            // 신고가 직전
            var nearHigh = radarService.detectNearHighStocks();
            if (nearHigh != null) {
                for (var nh : nearHigh) {
                    if (nh.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(nh.getStockCode(),
                            k -> new StockScore(k, nh.getStockName()));
                    score.technical = Math.min(20, score.technical + 18);
                    score.tags.add("신고가직전");
                    if (nh.getChangeRate() != null) score.changeRate = nh.getChangeRate();
                }
            }

            // 대량 취득 공시
            var holdings = radarService.detectLargeHoldings();
            if (holdings != null) {
                for (var h : holdings) {
                    if (h.getStockCode() == null || h.getStockCode().isEmpty()) continue;
                    StockScore score = scoreMap.computeIfAbsent(h.getStockCode(),
                            k -> new StockScore(k, h.getCorpName()));
                    score.technical = Math.min(20, score.technical + 14);
                    score.tags.add("대량취득공시");
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 기술적 스코어 실패: {}", e.getMessage());
        }
    }

    /** ⑤ 섹터 모멘텀 (/20): AI 테마 태그 기반 */
    private void scoreSectorMomentum(Map<String, StockScore> scoreMap) {
        try {
            var response = aiStrategyService.getAllLatestSnapshots();
            if (response == null || response.getStrategies() == null) return;

            // AI 테마 태그가 있는 종목에 모멘텀 점수 부여
            for (List<AiStrategySnapshotDto> stocks : response.getStrategies().values()) {
                if (stocks == null) continue;
                for (AiStrategySnapshotDto snap : stocks) {
                    if (snap.getStockCode() == null) continue;
                    StockScore score = scoreMap.get(snap.getStockCode());
                    if (score == null) continue; // 다른 항목에서 이미 등장한 종목만

                    String themes = snap.getAiThemes();
                    if (themes != null && !themes.isBlank()) {
                        // 테마 태그 개수에 비례 (최소 10, 태그당 +4, 최대 20)
                        int tagCount = themes.split(",").length;
                        score.sectorMomentum = Math.min(20, Math.max(score.sectorMomentum, 10 + tagCount * 4));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 섹터 모멘텀 스코어 실패: {}", e.getMessage());
        }
    }

    // ==================== Inner Classes ====================

    private static class StockScore {
        String stockCode;
        String stockName;
        int aiStrategy = 0;      // /20
        int earnings = 0;        // /20
        int supplyDemand = 0;    // /20
        int technical = 0;       // /20
        int sectorMomentum = 0;  // /20
        Set<String> tags = new LinkedHashSet<>();
        BigDecimal changeRate;

        StockScore(String stockCode, String stockName) {
            this.stockCode = stockCode;
            this.stockName = stockName;
        }

        int getTotalScore() {
            return aiStrategy + earnings + supplyDemand + technical + sectorMomentum;
        }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecommendationDto {
        private String stockCode;
        private String stockName;
        private int totalScore;
        // 세부 항목별 점수 (각 /20)
        private int aiStrategy;
        private int earnings;
        private int supplyDemand;
        private int technical;
        private int sectorMomentum;
        private List<String> tags;
        private BigDecimal changeRate;
    }
}
