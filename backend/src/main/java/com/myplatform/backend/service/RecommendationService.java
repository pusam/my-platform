package com.myplatform.backend.service;

import com.myplatform.backend.dto.AiStrategySnapshotDto;
import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.EarningSurpriseDto;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AI 종합 추천 TOP 5
 * 5가지 항목별 20점 만점, 합계 100점
 * - AI전략 신호 (/20)
 * - 실적 개선세 (/20)
 * - 기관/외국인 수급 (/20)
 * - 기술적 위치 (/20)
 * - 섹터 모멘텀 (/20)
 *
 * 장중(09:00~15:30): 실시간 계산 (30분 캐시)
 * 장 외 시간: DB에 저장된 직전 종가 기준 데이터 반환
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
    private final RecommendationSnapshotRepository snapshotRepository;

    // 인메모리 캐시 (장중 30분)
    private volatile List<RecommendationDto> cachedTop5 = null;
    private volatile LocalDateTime cacheTime = null;
    private static final long CACHE_MINUTES = 30;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

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

        // ② 인메모리 캐시가 있으면 반환 (장 외에도 유효)
        if (cachedTop5 != null && !cachedTop5.isEmpty()) {
            String label = cacheTime != null ? cacheTime.format(TIME_FMT) + " 기준" : "캐시 데이터";
            return new Top5Response(cachedTop5, label, !trading);
        }

        // ③ DB 스냅샷에서 복원
        List<RecommendationDto> fromDb = loadFromDb();
        if (!fromDb.isEmpty()) {
            cachedTop5 = fromDb;
            // DB 스냅샷의 시점 추출
            List<RecommendationSnapshot> snapshots = snapshotRepository.findLatestSnapshot();
            LocalDateTime snapTime = snapshots.isEmpty() ? now : snapshots.get(0).getSnapshotAt();
            cacheTime = snapTime;
            return new Top5Response(fromDb, snapTime.format(TIME_FMT) + " 기준 (종가)", false);
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
            if (result.isEmpty()) {
                // 실시간 계산 실패 시 인메모리 캐시 사용
                if (cachedTop5 != null && !cachedTop5.isEmpty()) {
                    result = cachedTop5;
                } else {
                    log.warn("[종합추천] 마감 스냅샷 저장 실패 — 데이터 없음");
                    return;
                }
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

            // 인메모리 캐시도 갱신
            cachedTop5 = result;
            cacheTime = snapTime;

            // 7일 이전 데이터 정리
            snapshotRepository.deleteOlderThan(snapTime.minusDays(7));

            log.info("[종합추천] 마감 스냅샷 {}건 저장 완료", result.size());
        } catch (Exception e) {
            log.error("[종합추천] 마감 스냅샷 저장 실패: {}", e.getMessage());
        }
    }

    // ==================== Private Methods ====================

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

            return snapshots.stream()
                    .map(s -> RecommendationDto.builder()
                            .stockCode(s.getStockCode())
                            .stockName(s.getStockName())
                            .totalScore(s.getTotalScore())
                            .aiStrategy(s.getAiStrategy())
                            .earnings(s.getEarnings())
                            .supplyDemand(s.getSupplyDemand())
                            .technical(s.getTechnical())
                            .sectorMomentum(s.getSectorMomentum())
                            .tags(s.getTags() != null && !s.getTags().isBlank()
                                    ? Arrays.asList(s.getTags().split(",")) : Collections.emptyList())
                            .changeRate(s.getChangeRate())
                            .build())
                    .toList();
        } catch (Exception e) {
            log.error("[종합추천] DB 스냅샷 로드 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<RecommendationDto> calculate() {
        Map<String, StockScore> scoreMap = new HashMap<>();

        scoreAiStrategy(scoreMap);
        scoreEarnings(scoreMap);
        scoreSupplyDemand(scoreMap);
        scoreTechnical(scoreMap);
        scoreSectorMomentum(scoreMap);

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

            for (Map.Entry<String, List<AiStrategySnapshotDto>> entry : response.getStrategies().entrySet()) {
                List<AiStrategySnapshotDto> stocks = entry.getValue();
                if (stocks == null) continue;

                for (int i = 0; i < Math.min(3, stocks.size()); i++) {
                    AiStrategySnapshotDto snap = stocks.get(i);
                    if (snap.getStockCode() == null) continue;

                    StockScore score = scoreMap.computeIfAbsent(snap.getStockCode(),
                            k -> new StockScore(k, snap.getStockName()));

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
            List<ConsecutiveBuyDto> foreign = investorTradeService.getConsecutiveBuyStocks("FOREIGN", 3);
            if (foreign != null) {
                for (ConsecutiveBuyDto cb : foreign) {
                    if (cb.getStockCode() == null) continue;
                    StockScore score = scoreMap.computeIfAbsent(cb.getStockCode(),
                            k -> new StockScore(k, cb.getStockName()));
                    int days = cb.getConsecutiveDays() != null ? cb.getConsecutiveDays() : 3;
                    int points = (days >= 5) ? 14 : 10;
                    score.supplyDemand = Math.min(20, score.supplyDemand + points);
                    score.tags.add("외국인" + days + "일연속");
                    if (cb.getChangeRate() != null) score.changeRate = cb.getChangeRate();
                }
            }

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

            for (List<AiStrategySnapshotDto> stocks : response.getStrategies().values()) {
                if (stocks == null) continue;
                for (AiStrategySnapshotDto snap : stocks) {
                    if (snap.getStockCode() == null) continue;
                    StockScore score = scoreMap.get(snap.getStockCode());
                    if (score == null) continue;

                    String themes = snap.getAiThemes();
                    if (themes != null && !themes.isBlank()) {
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

        int getTotalScore() {
            return aiStrategy + earnings + supplyDemand + technical + sectorMomentum;
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

    /** API 응답 래퍼: 데이터 + 타임스탬프 + 실시간 여부 */
    @Getter @AllArgsConstructor
    public static class Top5Response {
        private final List<RecommendationDto> items;
        private final String dataTime;     // "03/18 15:30 기준" or "03/18 15:30 기준 (종가)"
        private final boolean realtime;    // true=장중 실시간, false=장 외 캐시
    }
}
