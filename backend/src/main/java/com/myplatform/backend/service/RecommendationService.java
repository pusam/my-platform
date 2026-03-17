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
 * 5가지 소스를 종합하여 종목별 점수를 산출
 * - AI 전략 점수 (30점)
 * - 외국인/기관 수급 (20점)
 * - 선점 레이더 (20점)
 * - 퀀트 스크리너 (15점)
 * - 기술적 신호 (15점)
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

        // ① AI 전략 점수 (+30점)
        scoreAiStrategy(scoreMap);

        // ② 외국인/기관 수급 (+20점)
        scoreSupplyDemand(scoreMap);

        // ③ 선점 레이더 (+20점)
        scoreRadar(scoreMap);

        // ④ 퀀트 스크리너 (+15점)
        scoreScreener(scoreMap);

        // 결과 정렬 (총점 내림차순)
        List<RecommendationDto> results = scoreMap.values().stream()
                .filter(s -> s.totalScore >= 30) // 최소 30점 이상만
                .sorted(Comparator.comparingInt(StockScore::getTotalScore).reversed())
                .limit(5)
                .map(s -> RecommendationDto.builder()
                        .stockCode(s.stockCode)
                        .stockName(s.stockName)
                        .totalScore(s.totalScore)
                        .tags(new ArrayList<>(s.tags))
                        .changeRate(s.changeRate)
                        .build())
                .toList();

        log.info("[종합추천] TOP {} 계산 완료", results.size());
        return results;
    }

    private void scoreAiStrategy(Map<String, StockScore> scoreMap) {
        try {
            var response = aiStrategyService.getAllLatestSnapshots();
            if (response == null || response.getStrategies() == null) return;

            String[] labels = {"AI전략1위", "AI전략2위", "AI전략3위"};
            for (Map.Entry<String, List<AiStrategySnapshotDto>> entry : response.getStrategies().entrySet()) {
                List<AiStrategySnapshotDto> stocks = entry.getValue();
                if (stocks == null) continue;

                for (int i = 0; i < Math.min(3, stocks.size()); i++) {
                    AiStrategySnapshotDto snap = stocks.get(i);
                    if (snap.getStockCode() == null) continue;

                    StockScore score = scoreMap.computeIfAbsent(snap.getStockCode(),
                            k -> new StockScore(k, snap.getStockName()));

                    int points = (i == 0) ? 30 : (i == 1) ? 20 : 10;
                    score.totalScore += points;
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

    private void scoreSupplyDemand(Map<String, StockScore> scoreMap) {
        try {
            // 외국인 연속매수
            List<ConsecutiveBuyDto> foreign = investorTradeService.getConsecutiveBuyStocks("FOREIGN", 3);
            if (foreign != null) {
                for (ConsecutiveBuyDto cb : foreign) {
                    if (cb.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(cb.getStockCode(),
                            k -> new StockScore(k, cb.getStockName()));
                    score.totalScore += 15;
                    score.tags.add("외국인" + cb.getConsecutiveDays() + "일연속");
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
                    score.totalScore += 10;
                    score.tags.add("기관연속매수");
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 수급 스코어 실패: {}", e.getMessage());
        }
    }

    private void scoreRadar(Map<String, StockScore> scoreMap) {
        try {
            // 신고가 직전
            var nearHigh = radarService.detectNearHighStocks();
            if (nearHigh != null) {
                for (var nh : nearHigh) {
                    if (nh.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(nh.getStockCode(),
                            k -> new StockScore(k, nh.getStockName()));
                    score.totalScore += 15;
                    score.tags.add("신고가직전");
                    if (nh.getChangeRate() != null) score.changeRate = nh.getChangeRate();
                }
            }

            // 대량 취득
            var holdings = radarService.detectLargeHoldings();
            if (holdings != null) {
                for (var h : holdings) {
                    if (h.getStockCode() == null || h.getStockCode().isEmpty()) continue;
                    StockScore score = scoreMap.computeIfAbsent(h.getStockCode(),
                            k -> new StockScore(k, h.getCorpName()));
                    score.totalScore += 20;
                    score.tags.add("대량취득공시");
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 레이더 스코어 실패: {}", e.getMessage());
        }
    }

    private void scoreScreener(Map<String, StockScore> scoreMap) {
        try {
            // 어닝 서프라이즈 (영업이익 개선)
            var surprises = earningSurpriseService.detectEarningSurprises();
            if (surprises != null) {
                for (EarningSurpriseDto s : surprises) {
                    if (s.getStockCode() == null) continue;
                    String type = s.getSurpriseType() != null ? s.getSurpriseType().toString() : "";
                    if ("POSITIVE".equals(type) || "TURNAROUND".equals(type)) {
                        StockScore score = scoreMap.computeIfAbsent(s.getStockCode(),
                                k -> new StockScore(k, s.getStockName()));
                        score.totalScore += 15;
                        score.tags.add("TURNAROUND".equals(type) ? "흑자전환" : "실적개선");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[종합추천] 스크리너 스코어 실패: {}", e.getMessage());
        }
    }

    // ==================== Inner Classes ====================

    private static class StockScore {
        String stockCode;
        String stockName;
        int totalScore = 0;
        Set<String> tags = new LinkedHashSet<>();
        BigDecimal changeRate;

        StockScore(String stockCode, String stockName) {
            this.stockCode = stockCode;
            this.stockName = stockName;
        }

        int getTotalScore() { return totalScore; }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecommendationDto {
        private String stockCode;
        private String stockName;
        private int totalScore;
        private List<String> tags;
        private BigDecimal changeRate;
    }
}
