package com.myplatform.backend.service;

import com.myplatform.backend.dto.*;
import com.myplatform.backend.entity.RecommendationSnapshot;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.UserRepository;
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
    private final StockFinancialDataRepository financialDataRepository;
    private final RiskManagementService riskManagementService;
    private final TelegramNotificationService telegramService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    private static final int STRONG_BUY_THRESHOLD = 75;
    private volatile java.time.LocalDate lastAlertDate = null;

    // 가격 도달 알림 — 임계점 (오를 때 / 내릴 때)
    private static final double[] PRICE_UP_THRESHOLDS = {5.0, 10.0};
    private static final double[] PRICE_DOWN_THRESHOLDS = {-3.0, -5.0};
    // 종목별로 오늘 어떤 임계점을 발송했는지 (재발송 방지)
    private final java.util.concurrent.ConcurrentMap<String, java.util.Set<Double>> priceAlertedToday
            = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.time.LocalDate priceAlertedDate = null;

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

    /** 장중 스냅샷 (11:30, 14:00, 17:00) — 장중 흐름 변화 히스토리 */
    @Scheduled(cron = "0 30 11 * * MON-FRI", zone = "Asia/Seoul")
    @Scheduled(cron = "0 0 14 * * MON-FRI", zone = "Asia/Seoul")
    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void saveIntradaySnapshot() {
        log.info("[종합추천] 장중 스냅샷 저장");
        saveSnapshotInternal();
    }

    /** 마감 스냅샷 (20:05 — 애프터마켓 종료 후) */
    @Scheduled(cron = "0 5 20 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void saveClosingSnapshot() {
        log.info("[종합추천] 마감 스냅샷 저장 시작");
        saveSnapshotInternal();
        snapshotRepository.deleteOlderThan(LocalDateTime.now().minusDays(7));
    }

    /**
     * 매수 후보(TOP5)의 가격 도달 알림 (장중 5분 간격)
     * - 추천 종목이 +5%/+10% 도달 시 → 익절/모멘텀 신호
     * - 추천 종목이 -3%/-5% 도달 시 → 손절/진입 재검토 신호
     * - 종목별로 임계점별 일 1회만 발송 (재발송 방지)
     */
    @Scheduled(cron = "0 0/5 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void checkRecommendationPriceTargets() {
        java.time.LocalDate today = java.time.LocalDate.now();
        if (!today.equals(priceAlertedDate)) {
            priceAlertedToday.clear();
            priceAlertedDate = today;
        }

        try {
            List<RecommendationDto> top5;
            try {
                top5 = getTop5().getItems();
            } catch (Exception e) {
                log.debug("[가격알림] TOP5 조회 실패: {}", e.getMessage());
                return;
            }
            if (top5 == null || top5.isEmpty()) return;

            // 실시간 시세 — 추천 캐시는 stale할 수 있으니 별도 조회
            List<String> codes = top5.stream().map(RecommendationDto::getStockCode).collect(Collectors.toList());
            Map<String, StockPriceDto> priceMap;
            try {
                priceMap = stockPriceService.getStockPrices(codes);
            } catch (Exception e) {
                log.debug("[가격알림] 실시간 시세 조회 실패: {}", e.getMessage());
                return;
            }

            for (RecommendationDto rec : top5) {
                StockPriceDto live = priceMap.get(rec.getStockCode());
                if (live == null || live.getChangeRate() == null) continue;
                double rate = live.getChangeRate().doubleValue();

                java.util.Set<Double> alerted = priceAlertedToday
                        .computeIfAbsent(rec.getStockCode(), k -> java.util.concurrent.ConcurrentHashMap.newKeySet());

                // 상승 임계점 — 가장 높은 도달 임계점만 1회 (5%, 10% 두번 발송 방지)
                Double highestUp = null;
                for (double th : PRICE_UP_THRESHOLDS) {
                    if (rate >= th && !alerted.contains(th)) highestUp = th;
                }
                if (highestUp != null) {
                    alerted.add(highestUp);
                    sendPriceAlert(rec, live, highestUp, true);
                }

                // 하락 임계점 — 가장 낮은 도달 임계점만 1회
                Double lowestDown = null;
                for (double th : PRICE_DOWN_THRESHOLDS) {
                    if (rate <= th && !alerted.contains(th)) lowestDown = th;
                }
                if (lowestDown != null) {
                    alerted.add(lowestDown);
                    sendPriceAlert(rec, live, lowestDown, false);
                }
            }
        } catch (Exception e) {
            log.error("[가격알림] 처리 실패: {}", e.getMessage(), e);
        }
    }

    private void sendPriceAlert(RecommendationDto rec, StockPriceDto live, double threshold, boolean isUp) {
        String emoji = isUp ? (threshold >= 10 ? "🚀" : "📈") : (threshold <= -5 ? "🔻" : "⚠️");
        String label = isUp ? "수익" : "손실";
        String thLabel = (threshold > 0 ? "+" : "") + (int) threshold + "%";
        String currentRate = live.getChangeRate() != null
                ? (live.getChangeRate().doubleValue() >= 0 ? "+" : "") + String.format("%.2f%%", live.getChangeRate().doubleValue())
                : "-";
        String currentPrice = live.getCurrentPrice() != null
                ? String.format("%,d원", live.getCurrentPrice().intValue()) : "-";

        String tgMsg = String.format(
                "%s <b>매수후보 %s 도달 (%s)</b>\n\n• %s (%s)\n• 현재가: %s · 등락률: %s\n• 추천점수: %d점",
                emoji, label, thLabel,
                rec.getStockName(), rec.getStockCode(),
                currentPrice, currentRate, rec.getTotalScore()
        );
        try { telegramService.sendSignal(tgMsg); } catch (Exception ignore) {}

        // 앱 알림
        try {
            List<User> admins = userRepository.findByRole("ADMIN");
            String title = String.format("%s %s %s 도달", emoji, rec.getStockName(), thLabel);
            String body = String.format("현재가 %s (%s) · 추천 %d점",
                    currentPrice, currentRate, rec.getTotalScore());
            String link = "/stock/" + rec.getStockCode();
            String notifType = isUp ? "SUCCESS" : "WARNING";
            for (User u : admins) {
                notificationService.createNotificationForUser(u.getId(), notifType, title, body, link);
            }
        } catch (Exception e) {
            log.debug("[가격알림] 앱 알림 실패: {}", e.getMessage());
        }
        log.info("[가격알림] {} {} {}% 도달 (점수 {})",
                rec.getStockName(), rec.getStockCode(), threshold, rec.getTotalScore());
    }

    /**
     * 신규 강력매수(75+) 등장 알림 (평일 09:00 — 장 시작 직후)
     * - 어제 마감 스냅샷에 없고 오늘 75+ 진입한 종목만 텔레그램·앱 알림
     * - 일 1회만 (lastAlertDate 체크)
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void detectAndAlertNewStrongBuys() {
        java.time.LocalDate today = java.time.LocalDate.now();
        if (today.equals(lastAlertDate)) {
            log.debug("[강력매수알림] 오늘({}) 이미 발송됨", today);
            return;
        }

        try {
            // 오늘 신규 계산
            List<RecommendationDto> todayList = calculate();
            if (todayList.isEmpty()) {
                log.info("[강력매수알림] 오늘 추천 데이터 없음 — 스킵");
                return;
            }

            // 어제 마감 스냅샷 (오늘 0시 이전)
            LocalDateTime todayStart = today.atStartOfDay();
            List<RecommendationSnapshot> yesterday = snapshotRepository.findPreviousSnapshot(todayStart);
            Set<String> yesterdayStrongCodes = yesterday.stream()
                    .filter(s -> s.getTotalScore() >= STRONG_BUY_THRESHOLD)
                    .map(RecommendationSnapshot::getStockCode)
                    .collect(Collectors.toSet());

            // 오늘 75+ 진입 - 어제 75+ 명단에 없던 종목
            List<RecommendationDto> newStrongBuys = todayList.stream()
                    .filter(d -> d.getTotalScore() >= STRONG_BUY_THRESHOLD)
                    .filter(d -> d.getValidCount() >= 3)  // 데이터 부족 종목 제외
                    .filter(d -> !yesterdayStrongCodes.contains(d.getStockCode()))
                    .collect(Collectors.toList());

            if (newStrongBuys.isEmpty()) {
                log.info("[강력매수알림] 신규 진입 종목 없음 (어제 75+: {}종목)", yesterdayStrongCodes.size());
                lastAlertDate = today;
                return;
            }

            // 메시지 빌드
            StringBuilder msg = new StringBuilder("🚀 <b>오늘 새로 강력매수 등장</b>\n\n");
            for (RecommendationDto d : newStrongBuys) {
                msg.append(String.format("• %s (%s) — %d점",
                        d.getStockName(), d.getStockCode(), d.getTotalScore()));
                if (d.getTags() != null && !d.getTags().isEmpty()) {
                    msg.append(" · ").append(String.join("/", d.getTags().subList(0, Math.min(3, d.getTags().size()))));
                }
                msg.append("\n");
            }
            msg.append(String.format("\n📊 어제 강력매수: %d종목 → 오늘 신규: %d종목",
                    yesterdayStrongCodes.size(), newStrongBuys.size()));

            // 텔레그램 시그널 채널
            try {
                telegramService.sendSignal(msg.toString());
            } catch (Exception e) {
                log.warn("[강력매수알림] 텔레그램 발송 실패: {}", e.getMessage());
            }

            // 앱 알림 (관리자 사용자에게)
            try {
                List<User> admins = userRepository.findByRole("ADMIN");
                String title = String.format("새 강력매수 %d종목 등장", newStrongBuys.size());
                String body = newStrongBuys.stream()
                        .limit(3)
                        .map(d -> String.format("%s(%d)", d.getStockName(), d.getTotalScore()))
                        .collect(Collectors.joining(", "));
                String link = "/stock-dashboard?tab=premarket";
                for (User u : admins) {
                    notificationService.createNotificationForUser(u.getId(), "SUCCESS", title, body, link);
                }
                log.info("[강력매수알림] 발송 완료 — {}종목 / 관리자 {}명",
                        newStrongBuys.size(), admins.size());
            } catch (Exception e) {
                log.warn("[강력매수알림] 앱 알림 실패: {}", e.getMessage());
            }

            lastAlertDate = today;
        } catch (Exception e) {
            log.error("[강력매수알림] 처리 실패: {}", e.getMessage(), e);
        }
    }

    private void saveSnapshotInternal() {
        try {
            List<RecommendationDto> result = calculate();
            if (result.isEmpty() && cachedTop5 != null && !cachedTop5.isEmpty()) result = cachedTop5;
            if (result.isEmpty()) { log.warn("[종합추천] 스냅샷 — 데이터 없음"); return; }

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
                entity.setValueStability(dto.getValueStability());
                entity.setTags(dto.getTags() != null ? String.join(",", dto.getTags()) : "");
                entity.setChangeRate(dto.getChangeRate());
                entity.setRankOrder(i + 1);
                entity.setSnapshotAt(snapTime);
                snapshotRepository.save(entity);
            }
            cachedTop5 = result;
            cacheTime = snapTime;
            log.info("[종합추천] 스냅샷 {}건 저장 ({})", result.size(), snapTime.format(TIME_FMT));
        } catch (Exception e) {
            log.error("[종합추천] 스냅샷 실패: {}", e.getMessage());
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
        // 가치/안정성: PBR·ROE·부채비율·리스크 페널티 (모멘텀 편향 완화)
        scoreValueStability(scoreMap);
        // 실시간 교차검증: MA20 하회/수급 괴리 감지 → 점수 보정
        applyRealtimeChecks(scoreMap);

        // 디버그 로그
        for (StockScore s : scoreMap.values()) {
            log.debug("[종합추천] {} — AI:{} 실적:{} 수급:{} 기술:{} 섹터:{} 가치:{} (유효 {}개)",
                    s.stockName, s.aiStrategy, s.earnings, s.supplyDemand, s.technical, s.sectorMomentum,
                    s.valueStability, countValidCategories(s));
        }
        log.info("[종합추천] scoreMap {}종목 (AI시드 {}개)", scoreMap.size(), aiCount);

        List<RecommendationDto> results = scoreMap.values().stream()
                .filter(s -> countValidCategories(s) >= 4)
                .filter(s -> normalizeScore(
                        s.aiStrategy + s.earnings + s.supplyDemand + s.technical + s.sectorMomentum + s.valueStability,
                        countValidCategories(s)) >= 60) // 관망(59↓) 종목 제외
                .sorted(Comparator.comparingInt(StockScore::getNormalizedTotal).reversed()
                        .thenComparing(s -> s.changeRate != null ? s.changeRate.doubleValue() : 0.0,
                                Comparator.reverseOrder()))
                .limit(5)
                .map(this::toDto)
                .toList();

        for (RecommendationDto r : results) {
            log.info("[종합추천] #{} {} — 총{}점 (AI:{} 실적:{} 수급:{} 기술:{} 섹터:{} 가치:{}) 유효{}개",
                    results.indexOf(r) + 1, r.getStockName(), r.getTotalScore(),
                    r.getAiStrategy(), r.getEarnings(), r.getSupplyDemand(),
                    r.getTechnical(), r.getSectorMomentum(), r.getValueStability(), r.getValidCount());
        }
        log.info("[종합추천] TOP {} 계산 완료", results.size());
        return results;
    }

    // ==================== ⑥ 가치/안정성 (/20) ====================
    // PBR·ROE·부채비율·흑자 + 대주주 리스크 페널티
    // 모멘텀 카테고리 비중 다이루션해 저평가 우량주 부각
    private void scoreValueStability(Map<String, StockScore> scoreMap) {
        int calc = 0, miss = 0;
        for (StockScore stock : scoreMap.values()) {
            try {
                Optional<StockFinancialData> opt = financialDataRepository
                        .findTopByStockCodeOrderByReportDateDesc(stock.stockCode);
                if (opt.isEmpty()) { miss++; continue; }
                StockFinancialData fin = opt.get();
                int score = 0;
                List<String> tags = new ArrayList<>();

                // 1) PBR (8점) — 저평가 핵심
                BigDecimal pbr = fin.getPbr();
                if (pbr != null && pbr.signum() > 0) {
                    double v = pbr.doubleValue();
                    if (v <= 0.7) { score += 8; tags.add("PBR저평가"); }
                    else if (v <= 1.0) { score += 6; tags.add("PBR<1"); }
                    else if (v <= 1.5) score += 4;
                    else if (v <= 2.0) score += 2;
                }

                // 2) ROE×(1/PBR) 결합 (5점) — 자본효율+저평가 (마법공식 변형)
                BigDecimal roe = fin.getRoe();
                if (roe != null && pbr != null && pbr.signum() > 0) {
                    double combined = roe.doubleValue() / pbr.doubleValue();
                    if (combined >= 15) { score += 5; tags.add("우량+저평가"); }
                    else if (combined >= 10) score += 4;
                    else if (combined >= 7) score += 3;
                    else if (combined >= 4) score += 1;
                }

                // 3) 부채비율 (4점) — 재무 안정성
                BigDecimal debtRatio = fin.getDebtRatio();
                if (debtRatio != null && debtRatio.signum() >= 0) {
                    double v = debtRatio.doubleValue();
                    if (v <= 50) { score += 4; tags.add("저부채"); }
                    else if (v <= 100) score += 3;
                    else if (v <= 200) score += 1;
                }

                // 4) 영업이익+자본총계 양수 (3점) — 흑자 + 자본잠식 없음
                BigDecimal opProfit = fin.getOperatingProfit();
                BigDecimal equity = fin.getTotalEquity();
                if (opProfit != null && opProfit.signum() > 0
                        && equity != null && equity.signum() > 0) {
                    score += 3;
                }

                // 5) 대주주 리스크 페널티 (-5) — 위험 공시 빠른 체크
                try {
                    if (riskManagementService.quickDangerCheck(stock.stockName)) {
                        score = Math.max(0, score - 5);
                        tags.add("⚠리스크공시");
                    }
                } catch (Exception ignore) { /* 리스크 조회 실패 시 페널티 안 줌 */ }

                stock.valueStability = Math.min(20, score);
                if (stock.valueStability > 0) {
                    stock.tags.addAll(tags);
                    calc++;
                }
            } catch (Exception e) {
                log.debug("[종합추천] 가치/안정성 계산 실패 {}: {}", stock.stockCode, e.getMessage());
                miss++;
            }
        }
        log.debug("[종합추천] 가치: {}건 계산, {}건 데이터부족", calc, miss);
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

    // ==================== ⑥ 실시간 교차검증 (상세페이지 정합성) ====================

    /**
     * 랭킹 점수와 상세페이지 점수의 괴리 방지
     * - 실시간 시세로 MA20 대비 확인 (priceHistory 비동기 수집 문제 해결)
     * - 기술지표 재검증: 골든크로스 등 오래된 태그 제거
     * - 수급 괴리 (주가↑ + 기관/외인 매도): 수급 페널티 + 경고 태그
     */
    private void applyRealtimeChecks(Map<String, StockScore> scoreMap) {
        int ma20Penalty = 0, divergencePenalty = 0, tagFixed = 0;

        // 상위 후보만 선별 (전체 종목 시세 조회 시 타임아웃 방지)
        List<StockScore> topCandidates = scoreMap.values().stream()
                .filter(s -> countValidCategories(s) >= 4)
                .sorted(Comparator.comparingInt(StockScore::getNormalizedTotal).reversed())
                .limit(10)
                .collect(Collectors.toList());

        if (topCandidates.isEmpty()) return;

        // 상위 10개만 실시간 시세 조회
        List<String> codes = topCandidates.stream().map(s -> s.stockCode).collect(Collectors.toList());
        Map<String, StockPriceDto> priceMap;
        try {
            priceMap = stockPriceService.getStockPrices(codes);
        } catch (Exception e) {
            log.warn("[종합추천] 실시간 시세 조회 실패 (교차검증 스킵): {}", e.getMessage());
            return; // 시세 조회 실패 시 교차검증 자체를 스킵 (기존 점수 유지)
        }

        for (StockScore stock : topCandidates) {
            try {
                StockPriceDto livePrice = priceMap.get(stock.stockCode);

                // 1. MA20 하회 체크 — 실시간 현재가 vs priceHistory MA20
                List<StockPriceHistory> history = priceHistoryRepository
                        .findByStockCodeOrderByTradeDateDesc(stock.stockCode, PageRequest.of(0, 25));
                if (history != null && history.size() >= 20) {
                    List<BigDecimal> prices = history.stream()
                            .sorted(Comparator.comparing(StockPriceHistory::getTradeDate))
                            .map(StockPriceHistory::getClosePrice)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    if (prices.size() >= 20) {
                        BigDecimal ma20Sum = BigDecimal.ZERO;
                        for (int i = prices.size() - 20; i < prices.size(); i++) {
                            ma20Sum = ma20Sum.add(prices.get(i));
                        }
                        BigDecimal ma20 = ma20Sum.divide(BigDecimal.valueOf(20), 0, java.math.RoundingMode.HALF_UP);

                        // 실시간 현재가 우선, 없으면 최근 종가 사용
                        BigDecimal checkPrice = (livePrice != null && livePrice.getCurrentPrice() != null)
                                ? livePrice.getCurrentPrice()
                                : prices.get(prices.size() - 1);

                        if (checkPrice.compareTo(ma20) < 0) {
                            int penalty = Math.min(stock.technical, 8);
                            stock.technical = Math.max(0, stock.technical - penalty);
                            stock.tags.add("⚠MA20하회");
                            ma20Penalty++;
                            log.debug("[종합추천] MA20 페널티: {} (현재가:{} < MA20:{}, 기술 -{}점)",
                                    stock.stockName, checkPrice, ma20, penalty);
                        }

                        // 2. 기술지표 재검증 — 골든크로스/정배열 태그가 실제와 맞는지 확인
                        TechnicalIndicatorsDto freshIndicators = technicalIndicatorService.calculate(prices);
                        if (freshIndicators != null) {
                            boolean gcNow = Boolean.TRUE.equals(freshIndicators.getIsGoldenCross());
                            boolean auNow = Boolean.TRUE.equals(freshIndicators.getIsArrangedUp());

                            // 골든크로스 태그 있는데 실제로는 아닌 경우 → 태그 제거 + 페널티
                            if (stock.tags.contains("골든크로스") && !gcNow) {
                                stock.tags.remove("골든크로스");
                                stock.technical = Math.max(0, stock.technical - 3);
                                tagFixed++;
                                log.debug("[종합추천] 골든크로스 태그 제거: {} (실제 GC=false)", stock.stockName);
                            }
                            if (stock.tags.contains("정배열") && !auNow) {
                                stock.tags.remove("정배열");
                                stock.technical = Math.max(0, stock.technical - 2);
                                tagFixed++;
                            }
                        }
                    }
                }

                // 3. 수급-가격 괴리: 주가 상승 + 수급 점수 없음 → 개인 주도 의심
                BigDecimal cr = (livePrice != null && livePrice.getChangeRate() != null)
                        ? livePrice.getChangeRate() : stock.changeRate;
                if (cr != null && cr.doubleValue() > 1.0 && stock.supplyDemand <= 0) {
                    stock.technical = Math.max(0, stock.technical - 3);
                    stock.tags.add("⚠수급괴리");
                    divergencePenalty++;
                    log.debug("[종합추천] 수급 괴리: {} (등락률:+{}% but 수급 점수:{})",
                            stock.stockName, cr, stock.supplyDemand);
                }
            } catch (Exception e) {
                log.debug("[종합추천] 실시간 체크 실패: {} - {}", stock.stockName, e.getMessage());
            }
        }

        if (ma20Penalty > 0 || divergencePenalty > 0 || tagFixed > 0) {
            log.info("[종합추천] 실시간 교차검증: MA20페널티 {}건, 수급괴리 {}건, 태그보정 {}건",
                    ma20Penalty, divergencePenalty, tagFixed);
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
        if (s.valueStability > 0) c++;
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
                if (s.getValueStability() > 0) vc++;
                return RecommendationDto.builder()
                    .stockCode(s.getStockCode()).stockName(s.getStockName())
                    .totalScore(s.getTotalScore())
                    .aiStrategy(s.getAiStrategy()).earnings(s.getEarnings())
                    .supplyDemand(s.getSupplyDemand()).technical(s.getTechnical())
                    .sectorMomentum(s.getSectorMomentum())
                    .valueStability(s.getValueStability())
                    .validCount(vc)
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
        return time.isAfter(LocalTime.of(8, 0)) && time.isBefore(LocalTime.of(20, 5));
    }

    /** 유효 항목 수별 상한: 6개=100, 5개=92, 4개=80, 3개=65, 2개=50 */
    private static int normalizeScore(int raw, int validCount) {
        if (validCount >= 6) return Math.min(100, raw * 100 / 120);
        if (validCount <= 0) return 0;
        // raw는 valid * 20점 만점 → 100점 만점으로 환산 후 cap
        int scaled = raw * 100 / (validCount * 20);
        int cap = switch (validCount) {
            case 5 -> 92;
            case 4 -> 80;
            case 3 -> 65;
            case 2 -> 50;
            default -> 50;
        };
        return Math.min(cap, scaled);
    }

    private RecommendationDto toDto(StockScore s) {
        int vc = countValidCategories(s);
        int raw = s.aiStrategy + s.earnings + s.supplyDemand + s.technical + s.sectorMomentum + s.valueStability;
        int total = normalizeScore(raw, vc);

        return RecommendationDto.builder()
                .stockCode(s.stockCode).stockName(s.stockName)
                .totalScore(total)
                .aiStrategy(s.aiStrategy > 0 ? s.aiStrategy : NA)
                .earnings(s.earnings > 0 ? s.earnings : NA)
                .supplyDemand(s.supplyDemand > 0 ? s.supplyDemand : NA)
                .technical(s.technical > 0 ? s.technical : NA)
                .sectorMomentum(s.sectorMomentum > 0 ? s.sectorMomentum : NA)
                .valueStability(s.valueStability > 0 ? s.valueStability : NA)
                .validCount(vc)
                .tags(new ArrayList<>(s.tags))
                .changeRate(s.changeRate).build();
    }

    // ==================== Inner Classes ====================

    private static class StockScore {
        String stockCode, stockName;
        int aiStrategy = 0, earnings = 0, supplyDemand = 0, technical = 0, sectorMomentum = 0, valueStability = 0;
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
            if (valueStability > 0) { v++; sum += valueStability; }
            return normalizeScore(sum, v);
        }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecommendationDto {
        private String stockCode, stockName;
        private int totalScore, aiStrategy, earnings, supplyDemand, technical, sectorMomentum, valueStability, validCount;
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
