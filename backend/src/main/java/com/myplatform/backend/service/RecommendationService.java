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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
 * 종합 추천 TOP 10 — v7
 *
 * v7 핵심 수정:
 * - AI전략 카테고리를 totalScore 산식에서 제외 (5카테고리: 실적·수급·기술·섹터·가치)
 *   * AI전략은 후보 발굴/태그 용도로만 유지하여 추천 다양성은 보존
 * - TOP 5 → TOP 10 — 사용자 선택지 확대
 * - normalizeScore cap 테이블을 5카테고리 기준으로 재조정
 *
 * v6 (이전):
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
    private final MarketCalendarService marketCalendar;
    // 시그널 적중률 추적 — phase 12 통합. ObjectProvider 로 안전 주입 (순환/누락 방어).
    private final org.springframework.beans.factory.ObjectProvider<SignalOutcomeService> signalOutcomeProvider;

    private static final int STRONG_BUY_THRESHOLD = 75;
    // phase 34 — STRONG_BUY 종목 중 가치 점수 ≥ STRONG_VALUE_THRESHOLD 이면 정규화 점수 +
    // STRONG_VALUE_BONUS. v7 분리 철학은 유지(가치를 산식에 일반 포함 X) 하면서 "강한 모멘텀
    // + 강한 가치" 의 희소한 교집합만 추가 가산해 우대.
    private static final int STRONG_VALUE_THRESHOLD = 12;
    private static final int STRONG_VALUE_BONUS = 2;
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
    // 종합 판단 보드 union(P2-14 Phase2) — 최근 calculate() scoreMap(seed=AI/실적/수급 종목의 4카테고리)
    // 을 보존해 발굴 union 종목의 4-cat lookup 에 재사용(재점수 없이). calculate() 미실행(장외 DB 경로)이면 stale/null.
    private volatile Map<String, StockScore> cachedScoreMap = null;
    private static final long CACHE_MINUTES = 30;
    private static final int NA = -1;

    // 저평가 TOP 10 별도 캐시 — 가치 점수는 분기 단위로 거의 안 변하므로 30분 캐시 충분.
    private volatile List<RecommendationDto> cachedValueTop10 = null;
    private volatile LocalDateTime valueCacheTime = null;
    private volatile List<RecommendationDto> cachedGrowthTop10 = null;
    private volatile LocalDateTime growthCacheTime = null;
    private volatile List<RecommendationDto> cachedOversoldTop10 = null;
    private volatile LocalDateTime oversoldCacheTime = null;
    private volatile List<RecommendationDto> cachedEarningsTop10 = null;
    private volatile LocalDateTime earningsCacheTime = null;
    private volatile List<RecommendationDto> cachedSmartMoneyTop10 = null;
    private volatile LocalDateTime smartMoneyCacheTime = null;

    // 백그라운드 calculate 중복 방지
    private final java.util.concurrent.atomic.AtomicBoolean calculating
            = new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    // phase 34 — 시장 국면 enum. scoreSectorMomentum 의 전체 섹터 평균 등락률로 판정.
    // 가중치 multiplier 표는 applyMarketRegimeWeighting 참고.
    enum MarketRegime { BULL, BEAR, SIDEWAYS } // package-private: P1-5 regime 가중 테스트

    // phase 35 — hysteresis. 직전 calculate() 사이클의 regime 기억.
    // dead band 0.5 적용으로 임계 근처 흔들림(예: avg=-1.1 한 번 찍었다고 BULL→BEAR 즉시 전환)
    // 방지. 강한 시장 반전(avg±1.0 통과 + dead band 도 통과)은 한 번에 전환 가능.
    private volatile MarketRegime lastRegime = MarketRegime.SIDEWAYS;

    // ==================== Public API ====================

    public Top5Response getTop5() {
        LocalDateTime now = LocalDateTime.now();
        boolean trading = isTradingHours(now);

        // 1) in-memory 캐시 hit → 즉시
        if (cachedTop5 != null && cacheTime != null
                && cacheTime.isAfter(now.minusMinutes(CACHE_MINUTES))) {
            return buildResponse(cachedTop5, cacheTime.format(TIME_FMT) + " 기준", trading);
        }

        // 2) 캐시 콜드 — DB 스냅샷 즉시 반환 (장중·장외 모두).
        //    장중이면 백그라운드에서 fresh calculate 트리거 → 다음 호출은 새 캐시 hit.
        //    [기존 로직 문제] 장중 + 캐시 콜드 시 calculate() 동기 실행이 30초+ 걸려
        //    프론트 axios(timeout=30s) 가 끊고 cachedTop5 저장 전이라 매 호출이 다시 calculate
        //    → 영원히 빈 응답 루프. 그래서 axios 가 항상 timeout(nginx 499).
        List<RecommendationDto> fromDb = loadFromDb();
        if (!fromDb.isEmpty()) {
            if (trading) triggerBackgroundCalculate();
            return buildResponse(fromDb, getSnapshotTimeLabel(), false);
        }

        // 3) DB 도 비어있으면(콜드 스타트) 동기 calculate — 어쩔 수 없이 대기
        if (trading) {
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

        return new Top5Response(Collections.emptyList(), "", false, Collections.emptyMap());
    }

    /**
     * 종합 판단 보드 union(P2-14) — 최근 calculate() scoreMap 의 4카테고리 스냅샷(코드→StockScore).
     * seed=AI/실적/수급 종목이라 순수 저평가/성장주는 대부분 미포함(보드에서 "—"로 정직 표시).
     * calculate() 미실행 시 빈 맵. <b>4-cat lookup 전용(산식 무변경, 재점수 없음).</b>
     */
    public Map<String, StockScore> categoryScoreSnapshot() {
        Map<String, StockScore> m = this.cachedScoreMap;
        return m != null ? m : Collections.emptyMap();
    }

    /**
     * 백그라운드에서 fresh 계산 후 cachedTop5 갱신.
     * - AtomicBoolean 으로 중복 호출 차단(N개 동시 요청 들어와도 한 번만 계산)
     * - 결과는 다음 getTop5() 호출에서 캐시 hit
     */
    private void triggerBackgroundCalculate() {
        if (!calculating.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                List<RecommendationDto> result = calculate();
                if (!result.isEmpty()) {
                    cachedTop5 = result;
                    cacheTime = LocalDateTime.now();
                    log.info("[종합추천] 백그라운드 계산 완료 - {}건", result.size());
                }
            } catch (Exception e) {
                log.error("[종합추천] 백그라운드 계산 실패: {}", e.getMessage(), e);
            } finally {
                calculating.set(false);
            }
        }, "rec-calc").start();
    }

    /**
     * TOP5 종목의 changeRate/currentPrice를 캐시된 시세로 갱신.
     * <p>buildResponse 가 매 호출마다 부르는 hot path 라 KIS 직접 호출은 위험 — 메모리(5분)/DB(15분)
     * 캐시만 사용해 응답 시간 영향 0. 신선도는 stockPriceService 의 cleanup·캐시 정책에 위임.
     */
    private void refreshPrices(List<RecommendationDto> items) {
        try {
            List<String> codes = items.stream().map(RecommendationDto::getStockCode).toList();
            Map<String, com.myplatform.backend.dto.StockPriceDto> prices =
                    stockPriceService.getStockPricesFromCacheOnly(codes);
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
    @Scheduled(scheduler = "batchScheduler", cron = "0 30 11 * * MON-FRI", zone = "Asia/Seoul")
    @Scheduled(scheduler = "batchScheduler", cron = "0 0 14 * * MON-FRI", zone = "Asia/Seoul")
    @Scheduled(scheduler = "batchScheduler", cron = "0 0 17 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void saveIntradaySnapshot() {
        log.info("[종합추천] 장중 스냅샷 저장");
        saveSnapshotInternal();
    }

    /** 마감 스냅샷 (20:05 — 애프터마켓 종료 후) */
    @Scheduled(scheduler = "batchScheduler", cron = "0 5 20 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void saveClosingSnapshot() {
        log.info("[종합추천] 마감 스냅샷 저장 시작");
        saveSnapshotInternal();
        snapshotRepository.deleteOlderThan(LocalDateTime.now().minusDays(7));
    }

    /**
     * 매일 08:00 장전 캐시 무효화
     * - 어제 장후 30분 캐시(메모리) 또는 stale DB 스냅샷이 8시 이후에도
     *   sectorMomentum=0(장외 시간 보너스 0)으로 굳어 N/A 무더기로 노출되던 문제 해결.
     * - 다음 getTop5() 호출에서 fresh calculate (장중) 또는 최신 DB 스냅샷 (장전) 사용.
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")
    public void invalidateMorningCache() {
        log.info("[종합추천] 08:00 장전 캐시 무효화 — 다음 호출에서 fresh 계산");
        cachedTop5 = null;
        cacheTime = null;
    }

    /**
     * 컨테이너 시작 시점이 08:00 이후라 그날 invalidateMorningCache 가 영구 미스되는
     * 케이스 catch-up. 평일 + 08:00~12:00 사이 시작이면 한 번 호출.
     * (메모리 캐시는 컨테이너 새 시작이라 자동으로 비어있지만, 명시적 호출로 의도 일치 + 향후
     *  L2 캐시 추가 시 자동으로 catch-up 범위에 포함되게.)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpMorningTaskOnStartup() {
        if (marketCalendar.shouldCatchUpMorningTask()) {
            log.info("[종합추천] 시작 시 08:00 catch-up — 컨테이너가 아침 cron 이후 시작됨");
            invalidateMorningCache();
        }
    }

    /**
     * 매수 후보(TOP5)의 가격 도달 알림 (장중 5분 간격)
     * - 추천 종목이 +5%/+10% 도달 시 → 익절/모멘텀 신호
     * - 추천 종목이 -3%/-5% 도달 시 → 손절/진입 재검토 신호
     * - 종목별로 임계점별 일 1회만 발송 (재발송 방지)
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 0/5 9-19 * * MON-FRI", zone = "Asia/Seoul")
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

        // 앱 알림 — N명 관리자 → 1회 배치 INSERT (saveAll)
        try {
            List<User> admins = userRepository.findByRole("ADMIN");
            String title = String.format("%s %s %s 도달", emoji, rec.getStockName(), thLabel);
            String body = String.format("현재가 %s (%s) · 추천 %d점",
                    currentPrice, currentRate, rec.getTotalScore());
            String link = "/stock/" + rec.getStockCode();
            String notifType = isUp ? "SUCCESS" : "WARNING";
            List<Long> adminIds = admins.stream().map(User::getId).collect(Collectors.toList());
            notificationService.createNotificationsForUsers(adminIds, notifType, title, body, link);
        } catch (Exception e) {
            log.debug("[가격알림] 앱 알림 실패: {}", e.getMessage());
        }
        log.info("[가격알림] {} {} {}% 도달 (점수 {})",
                rec.getStockName(), rec.getStockCode(), threshold, rec.getTotalScore());
    }

    /**
     * 상승 가속 종목 알림 (평일 09:00 — 장 시작 직후)
     * <p>phase31 P1: "어제 75+ 없던 신규 75+ 진입" 기존 로직은 본질적으로 꼭지에서 알림 보내는
     * 구조였음 — 어제 늦게 점수 오른 종목 = 이미 한참 오른 후 마지막 신호.
     * <p>변경: <b>delta(오늘 − 어제) ≥ +10 이고 오늘 totalScore ≥ 65</b> 종목만 알림.
     * 상승 가속 중 + 아직 STRONG_BUY(75) 도달 전 = 진짜 진입 기회.
     * <p>안전장치: prev 스냅샷 비어있으면(콜드스타트/DB 클린업 직후) 알림 스킵 (모든 종목이
     * delta = todayScore 로 보여 스팸 방지).
     * <p>일 1회만 (lastAlertDate 체크).
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 0 9 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void detectAndAlertNewStrongBuys() {
        java.time.LocalDate today = java.time.LocalDate.now();
        if (today.equals(lastAlertDate)) {
            log.debug("[상승가속알림] 오늘({}) 이미 발송됨", today);
            return;
        }

        try {
            // 오늘 신규 계산
            List<RecommendationDto> todayList = calculate();
            if (todayList.isEmpty()) {
                log.info("[상승가속알림] 오늘 추천 데이터 없음 — 스킵");
                return;
            }

            // 어제 마감 스냅샷 (오늘 0시 이전) — code → totalScore 매핑.
            LocalDateTime todayStart = today.atStartOfDay();
            List<RecommendationSnapshot> yesterday = snapshotRepository.findPreviousSnapshot(todayStart);
            if (yesterday.isEmpty()) {
                log.info("[상승가속알림] 어제 스냅샷 비어있음 — 알림 스킵 (delta 계산 불가)");
                lastAlertDate = today;
                return;
            }
            Map<String, Integer> prevScores = yesterday.stream()
                    .collect(Collectors.toMap(
                            RecommendationSnapshot::getStockCode,
                            RecommendationSnapshot::getTotalScore,
                            (a, b) -> a));

            // 상승 가속 조건: delta ≥ +10, 오늘 ≥ 65, 어제 스냅샷 존재(신규 종목은 제외 — delta 의미 없음)
            final int DELTA_THRESHOLD = 10;
            final int TODAY_THRESHOLD = 65;
            List<RecommendationDto> newStrongBuys = todayList.stream()
                    .filter(d -> d.getValidCount() >= 3)
                    .filter(d -> prevScores.containsKey(d.getStockCode()))
                    .filter(d -> d.getTotalScore() >= TODAY_THRESHOLD)
                    .filter(d -> d.getTotalScore() - prevScores.get(d.getStockCode()) >= DELTA_THRESHOLD)
                    .sorted(Comparator.comparingInt((RecommendationDto d) ->
                            d.getTotalScore() - prevScores.get(d.getStockCode())).reversed())
                    .collect(Collectors.toList());

            if (newStrongBuys.isEmpty()) {
                log.info("[상승가속알림] 가속 종목 없음 (어제 스냅샷 {}건, 컷: Δ≥+{} & 오늘≥{})",
                        yesterday.size(), DELTA_THRESHOLD, TODAY_THRESHOLD);
                lastAlertDate = today;
                return;
            }

            // 메시지 빌드 — 점수 + delta 같이 노출
            StringBuilder msg = new StringBuilder("🚀 <b>오늘 상승 가속 종목</b>\n\n");
            for (RecommendationDto d : newStrongBuys) {
                int delta = d.getTotalScore() - prevScores.get(d.getStockCode());
                msg.append(String.format("• %s (%s) — %d점 (Δ+%d)",
                        d.getStockName(), d.getStockCode(), d.getTotalScore(), delta));
                if (d.getTags() != null && !d.getTags().isEmpty()) {
                    msg.append(" · ").append(String.join("/", d.getTags().subList(0, Math.min(3, d.getTags().size()))));
                }
                msg.append("\n");
            }
            msg.append(String.format("\n📊 조건: Δ≥+%d & 오늘≥%d — %d종목 매칭",
                    DELTA_THRESHOLD, TODAY_THRESHOLD, newStrongBuys.size()));

            // 텔레그램 시그널 채널
            try {
                telegramService.sendSignal(msg.toString());
            } catch (Exception e) {
                log.warn("[상승가속알림] 텔레그램 발송 실패: {}", e.getMessage());
            }

            // 앱 알림 (관리자 사용자에게) — delta 같이 노출.
            try {
                List<User> admins = userRepository.findByRole("ADMIN");
                String title = String.format("상승 가속 %d종목 포착", newStrongBuys.size());
                String body = newStrongBuys.stream()
                        .limit(3)
                        .map(d -> {
                            int delta = d.getTotalScore() - prevScores.get(d.getStockCode());
                            return String.format("%s(%d, Δ+%d)", d.getStockName(), d.getTotalScore(), delta);
                        })
                        .collect(Collectors.joining(", "));
                String link = "/stock-dashboard?tab=premarket";
                for (User u : admins) {
                    notificationService.createNotificationForUser(u.getId(), "SUCCESS", title, body, link);
                }
                log.info("[상승가속알림] 발송 완료 — {}종목 / 관리자 {}명",
                        newStrongBuys.size(), admins.size());
            } catch (Exception e) {
                log.warn("[상승가속알림] 앱 알림 실패: {}", e.getMessage());
            }

            lastAlertDate = today;
        } catch (Exception e) {
            log.error("[상승가속알림] 처리 실패: {}", e.getMessage(), e);
        }
    }

    private void saveSnapshotInternal() {
        try {
            List<RecommendationDto> result = calculate();
            if (result.isEmpty() && cachedTop5 != null && !cachedTop5.isEmpty()) result = cachedTop5;
            if (result.isEmpty()) { log.warn("[종합추천] 스냅샷 — 데이터 없음"); return; }

            // phase 38 fix — 가격 채우기. saveSnapshotInternal 경로는 buildResponse 안 거치므로
            // dto.currentPrice 가 null 인 상태. line 506 의 record() 진입 조건이 항상 fail 해
            // STRONG_BUY/BUY 시그널이 signal_outcome 에 0 건 record 되던 잠재 버그(phase 12부터).
            // refreshPrices 가 메모리/DB 캐시만 사용해 응답 시간 영향 0.
            refreshPrices(result);

            LocalDateTime snapTime = LocalDateTime.now();
            List<RecommendationSnapshot> entities = new ArrayList<>(result.size());
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
                entity.setGrowth(dto.getGrowth());
                entity.setTags(dto.getTags() != null ? String.join(",", dto.getTags()) : "");
                entity.setChangeRate(dto.getChangeRate());
                entity.setRankOrder(i + 1);
                entity.setSnapshotAt(snapTime);
                entities.add(entity);
            }
            snapshotRepository.saveAll(entities);
            cachedTop5 = result;
            cacheTime = snapTime;

            // 시그널 적중률 추적 — STRONG_BUY (75+) / BUY (55~74) 종목 발생 기록.
            // 3일 후 batch 가 평가. record() 실패해도 스냅샷 저장은 영향 없음 (try/catch 격리).
            SignalOutcomeService outcomeService = signalOutcomeProvider.getIfAvailable();
            if (outcomeService != null) {
                for (RecommendationDto dto : result) {
                    if (dto.getCurrentPrice() == null || dto.getCurrentPrice().signum() <= 0) continue;
                    String signalType;
                    if (dto.getTotalScore() >= STRONG_BUY_THRESHOLD) {
                        signalType = "STRONG_BUY";
                    } else if (dto.getTotalScore() >= 55) {
                        signalType = "BUY";
                    } else {
                        continue;
                    }
                    try {
                        // V30 — 카테고리 점수 스냅샷 동봉: 카테고리 조건부 적중률 집계용.
                        outcomeService.record(signalType, dto.getStockCode(), dto.getStockName(),
                                dto.getTotalScore(), dto.getCurrentPrice(),
                                dto.getEarnings(), dto.getSupplyDemand(),
                                dto.getTechnical(), dto.getSectorMomentum());
                    } catch (Exception ignore) { /* 적중률 추적은 best-effort */ }
                }
            }

            log.info("[종합추천] 스냅샷 {}건 저장 ({})", result.size(), snapTime.format(TIME_FMT));
        } catch (Exception e) {
            log.error("[종합추천] 스냅샷 실패: {}", e.getMessage());
        }
    }

    // ==================== 캐시 진단 (phase 36b) ====================

    /**
     * 메모리 캐시(cachedTop5) 의 현재 상태 — phase 36b.
     *
     * <p>cron 사이 (14:00 / 17:00 등) 기다리지 않고 phase 변경의 즉시 효과를 확인하기 위한 진단.
     * 점수 분포, STRONG_BUY/BUY 카운트, regime 태그, top3 종목 반환.
     *
     * <p>스냅샷 DB 와 다른 점: cron 마다만 INSERT 되는 DB 와 달리 메모리 캐시는 calculate() 호출
     * 후 즉시 갱신됨. {@link #warmCache} 로 강제 트리거 가능.
     */
    public Map<String, Object> getCacheDiagnostics() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        List<RecommendationDto> cache = this.cachedTop5;
        LocalDateTime cTime = this.cacheTime;
        result.put("cacheTime", cTime);
        result.put("cacheSize", cache == null ? 0 : cache.size());

        if (cache == null || cache.isEmpty()) {
            result.put("scoreDistribution", null);
            result.put("strongBuyCount", 0);
            result.put("buyPlusCount", 0);
            result.put("regime", null);
            result.put("top3", java.util.Collections.emptyList());
            return result;
        }

        int min = 100, max = 0, sum = 0;
        int strongBuyCount = 0, buyPlusCount = 0;
        String regimeTag = null;
        for (RecommendationDto dto : cache) {
            int score = dto.getTotalScore();
            if (score < min) min = score;
            if (score > max) max = score;
            sum += score;
            if (score >= STRONG_BUY_THRESHOLD) strongBuyCount++;
            if (score >= 55) buyPlusCount++;
            if (regimeTag == null && dto.getTags() != null) {
                for (String tag : dto.getTags()) {
                    if (tag != null && tag.startsWith("regime:")) {
                        regimeTag = tag.substring("regime:".length());
                        break;
                    }
                }
            }
        }

        Map<String, Object> dist = new java.util.LinkedHashMap<>();
        dist.put("min", min);
        dist.put("max", max);
        dist.put("avg", Math.round(sum * 100.0 / cache.size()) / 100.0);
        result.put("scoreDistribution", dist);
        result.put("strongBuyCount", strongBuyCount);
        result.put("buyPlusCount", buyPlusCount);
        // SIDEWAYS 는 태그 X — null 이면 SIDEWAYS 또는 cache 비어있음
        result.put("regime", regimeTag != null ? regimeTag : "SIDEWAYS_OR_UNKNOWN");

        List<Map<String, Object>> top3 = new ArrayList<>();
        for (int i = 0; i < Math.min(3, cache.size()); i++) {
            RecommendationDto dto = cache.get(i);
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("stockCode", dto.getStockCode());
            item.put("stockName", dto.getStockName());
            item.put("totalScore", dto.getTotalScore());
            item.put("earnings", dto.getEarnings());
            item.put("supplyDemand", dto.getSupplyDemand());
            item.put("technical", dto.getTechnical());
            item.put("sectorMomentum", dto.getSectorMomentum());
            item.put("valueStability", dto.getValueStability());
            item.put("tags", dto.getTags());
            top3.add(item);
        }
        result.put("top3", top3);
        return result;
    }

    /**
     * 캐시 강제 트리거 — phase 36b. 진단 API 의 refresh=true 가 호출.
     * <p>{@link #getTop5} 와 동일 로직: 캐시 hit 이면 그대로, miss 면 백그라운드 calculate.
     * 즉시 fresh 캐시를 만들고 싶으면 cachedTop5 를 명시적으로 null 로 set 후 호출. 다만
     * 진단 용도라 단순 트리거만 — 1~2분 후 다시 /data 호출하면 fresh 캐시 확인 가능.
     */
    public void warmCache() {
        // cachedTop5 비우면 다음 호출에서 DB 폴백 + 백그라운드 calculate 트리거
        this.cachedTop5 = null;
        this.cacheTime = null;
        getTop5();
    }

    // ==================== STRONG+VALUE 빈도 조회 (phase 35) ====================

    /**
     * STRONG_BUY (≥75) AND value (≥12) 동시 충족 종목 빈도 — phase 35.
     * <p>phase 34 의 STRONG+VALUE +2 보너스가 실제로 작동하는지 운영 데이터 확인용.
     * 외부 평가에서 "이 조건 만족 종목이 거의 없으면 dead code, 많으면 v7 분리 철학을 슬쩍 깬 것"
     * 이라는 우려가 있어 빠르게 검증할 API 제공.
     * <p>리턴 구조:
     * <pre>
     * {
     *   daysWindow: 30, scoreThreshold: 75, valueThreshold: 12,
     *   totalOccurrences: int (중복 포함 모든 스냅샷),
     *   uniqueStocks: int (distinct 종목 수),
     *   dailyCounts: [ {date, count, stocks: [name1, name2, ...]} ]
     * }
     * </pre>
     */
    public Map<String, Object> getStrongValueFrequency(int days) {
        int d = days < 1 ? 30 : days;
        LocalDateTime since = LocalDateTime.now().minusDays(d);
        List<RecommendationSnapshot> rows = snapshotRepository.findStrongValueSince(
                since, STRONG_BUY_THRESHOLD, STRONG_VALUE_THRESHOLD);

        // 일자별 집계 — 같은 종목이 같은 날 여러 스냅샷에 나와도 1로 카운트
        Map<java.time.LocalDate, Map<String, String>> perDay = new java.util.TreeMap<>(
                Comparator.reverseOrder());
        java.util.Set<String> distinctStocks = new java.util.HashSet<>();
        for (RecommendationSnapshot s : rows) {
            java.time.LocalDate date = s.getSnapshotAt().toLocalDate();
            perDay.computeIfAbsent(date, k -> new java.util.LinkedHashMap<>())
                    .putIfAbsent(s.getStockCode(), s.getStockName());
            distinctStocks.add(s.getStockCode());
        }

        List<Map<String, Object>> daily = new ArrayList<>();
        for (var entry : perDay.entrySet()) {
            Map<String, String> stockMap = entry.getValue();
            Map<String, Object> dayInfo = new java.util.LinkedHashMap<>();
            dayInfo.put("date", entry.getKey().toString());
            dayInfo.put("count", stockMap.size());
            dayInfo.put("stocks", new ArrayList<>(stockMap.values()));
            daily.add(dayInfo);
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("daysWindow", d);
        result.put("scoreThreshold", STRONG_BUY_THRESHOLD);
        result.put("valueThreshold", STRONG_VALUE_THRESHOLD);
        result.put("totalOccurrences", rows.size());
        result.put("uniqueStocks", distinctStocks.size());
        result.put("dailyCounts", daily);
        return result;
    }

    // ==================== 저평가 TOP 10 (별도 트랙) ====================

    /**
     * 저평가 TOP 10 — PBR·ROE·부채비율·흑자 기반 가치주 점수 만으로 산정.
     * 종합 추천(매수 신호) 과 별도 트랙 — 모멘텀/실적/수급/기술 무시.
     *
     * 캐시 30분 (가치 데이터는 분기 단위로 거의 안 변함).
     */
    public Top5Response getValueTop10() {
        LocalDateTime now = LocalDateTime.now();
        boolean trading = isTradingHours(now);
        if (cachedValueTop10 != null && valueCacheTime != null
                && valueCacheTime.isAfter(now.minusMinutes(CACHE_MINUTES))) {
            return buildValueResponse(cachedValueTop10, valueCacheTime.format(TIME_FMT) + " 기준", trading);
        }
        List<RecommendationDto> result;
        try {
            result = calculateValueTop10();
            if (!result.isEmpty()) {
                cachedValueTop10 = result;
                valueCacheTime = now;
            }
        } catch (Exception e) {
            log.error("[저평가TOP10] 계산 실패: {}", e.getMessage(), e);
            result = cachedValueTop10 != null ? cachedValueTop10 : Collections.emptyList();
        }
        return buildValueResponse(result, now.format(TIME_FMT) + " 기준", trading);
    }

    private Top5Response buildValueResponse(List<RecommendationDto> items, String dataTime, boolean realtime) {
        refreshPrices(items);  // 가격은 실시간 — 가치 점수는 캐시
        return new Top5Response(items, dataTime, realtime, Collections.emptyMap());
    }

    private List<RecommendationDto> calculateValueTop10() {
        long t0 = System.currentTimeMillis();
        List<StockFinancialData> all = financialDataRepository.findLatestPerStock();
        log.info("[저평가TOP10] financial_data {}종목 평가", all.size());

        // 점수 산정 + 0점 초과만 필터
        List<ValueScoredStock> scored = new ArrayList<>();
        for (StockFinancialData fin : all) {
            if (fin.getStockCode() == null || fin.getStockName() == null) continue;
            int[] parts = computeValueScoreParts(fin);
            int score = Math.min(20, parts[0] + parts[1] + parts[2] + parts[3]);
            if (score <= 0) continue;
            ValueScoredStock vs = new ValueScoredStock();
            vs.stockCode = fin.getStockCode();
            vs.stockName = fin.getStockName();
            vs.score = score;
            vs.pbrScore = parts[0];
            vs.roeCombinedScore = parts[1];
            vs.debtScore = parts[2];
            vs.profitEquityScore = parts[3];
            vs.tags = computeValueTags(fin);
            scored.add(vs);
        }

        // 점수 desc 정렬 후 상위 30 만 리스크 검사 (DART 호출 부담 차단)
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        List<ValueScoredStock> shortlist = scored.stream().limit(30).collect(Collectors.toList());
        for (ValueScoredStock vs : shortlist) {
            try {
                if (riskManagementService.quickDangerCheck(vs.stockCode, vs.stockName)) {
                    vs.score = Math.max(0, vs.score - 5);
                    vs.tags.add("⚠리스크공시");
                }
            } catch (Exception ignore) { /* 페널티 안 줌 */ }
        }
        // 페널티 후 재정렬 + top 10
        shortlist.sort((a, b) -> Integer.compare(b.score, a.score));
        List<ValueScoredStock> top = shortlist.stream().limit(10).collect(Collectors.toList());

        log.info("[저평가TOP10] 계산 완료 — {}건 후보 → 상위 30 리스크검사 → top10 ({}ms)",
                scored.size(), System.currentTimeMillis() - t0);

        return top.stream().map(vs -> RecommendationDto.builder()
                .stockCode(vs.stockCode)
                .stockName(vs.stockName)
                .totalScore(Math.min(100, vs.score * 5))   // 20점 만점 → 100점 환산
                .aiStrategy(NA)
                .earnings(NA)
                .supplyDemand(NA)
                .technical(NA)
                .sectorMomentum(NA)
                .valueStability(vs.score)
                .validCount(1)
                .tags(new ArrayList<>(vs.tags))
                // 4 항목 분해 점수 — UI 막대 표시용
                .valuePbrScore(vs.pbrScore)
                .valueRoeCombinedScore(vs.roeCombinedScore)
                .valueDebtScore(vs.debtScore)
                .valueProfitEquityScore(vs.profitEquityScore)
                .build()).toList();
    }

    /** 점수 합만 계산 — 후보 1차 정렬용 (분해 점수 필요 없음). */
    private int computeValueScore(StockFinancialData fin) {
        int[] parts = computeValueScoreParts(fin);
        return Math.min(20, parts[0] + parts[1] + parts[2] + parts[3]);
    }

    /** 점수 분해 — UI 막대 표시용. {pbr, roeCombined, debt, profitEquity}. */
    private int[] computeValueScoreParts(StockFinancialData fin) {
        int pbrScore = 0, roeCombinedScore = 0, debtScore = 0, profitEquityScore = 0;
        BigDecimal pbr = fin.getPbr();
        if (pbr != null && pbr.signum() > 0) {
            double v = pbr.doubleValue();
            if (v <= 0.7) pbrScore = 8;
            else if (v <= 1.0) pbrScore = 6;
            else if (v <= 1.5) pbrScore = 4;
            else if (v <= 2.0) pbrScore = 2;
        }
        BigDecimal roe = fin.getRoe();
        if (roe != null && pbr != null && pbr.signum() > 0) {
            double combined = roe.doubleValue() / pbr.doubleValue();
            if (combined >= 15) roeCombinedScore = 5;
            else if (combined >= 10) roeCombinedScore = 4;
            else if (combined >= 7) roeCombinedScore = 3;
            else if (combined >= 4) roeCombinedScore = 1;
        }
        BigDecimal debtRatio = fin.getDebtRatio();
        if (debtRatio != null && debtRatio.signum() > 0) {
            double v = debtRatio.doubleValue();
            if (v <= 50) debtScore = 4;
            else if (v <= 100) debtScore = 3;
            else if (v <= 200) debtScore = 1;
        }
        if (fin.getOperatingProfit() != null && fin.getOperatingProfit().signum() > 0
                && fin.getTotalEquity() != null && fin.getTotalEquity().signum() > 0) {
            profitEquityScore = 3;
        }
        return new int[]{pbrScore, roeCombinedScore, debtScore, profitEquityScore};
    }

    private List<String> computeValueTags(StockFinancialData fin) {
        List<String> tags = new ArrayList<>();
        BigDecimal pbr = fin.getPbr();
        if (pbr != null && pbr.signum() > 0) {
            double v = pbr.doubleValue();
            if (v <= 0.7) tags.add("PBR저평가");
            else if (v <= 1.0) tags.add("PBR<1");
        }
        BigDecimal roe = fin.getRoe();
        if (roe != null && pbr != null && pbr.signum() > 0) {
            double combined = roe.doubleValue() / pbr.doubleValue();
            if (combined >= 15) tags.add("우량+저평가");
        }
        BigDecimal debtRatio = fin.getDebtRatio();
        if (debtRatio != null && debtRatio.signum() > 0 && debtRatio.doubleValue() <= 50) {
            tags.add("저부채");
        }
        if (fin.getOperatingProfit() != null && fin.getOperatingProfit().signum() > 0
                && fin.getTotalEquity() != null && fin.getTotalEquity().signum() > 0) {
            tags.add("흑자+자본정상");
        }
        return tags;
    }

    private static class ValueScoredStock {
        String stockCode;
        String stockName;
        int score;
        List<String> tags;
        // 항목별 점수 — UI 막대 그래프 분해 표시용
        int pbrScore;             // 0~8
        int roeCombinedScore;     // 0~5
        int debtScore;            // 0~4
        int profitEquityScore;    // 0~3
    }

    // ==================== 성장주 TOP 10 (별도 트랙) ====================

    /**
     * 성장주 TOP 10 — 매출·이익 성장률 + PEG 기반 성장 점수만으로 산정.
     * 저평가(싸다)와 짝 — "빠르게 크는" 종목. 종합추천(매수 신호)·저평가와 별도 트랙. 캐시 30분(분기 데이터).
     */
    public Top5Response getGrowthTop10() {
        LocalDateTime now = LocalDateTime.now();
        boolean trading = isTradingHours(now);
        if (cachedGrowthTop10 != null && growthCacheTime != null
                && growthCacheTime.isAfter(now.minusMinutes(CACHE_MINUTES))) {
            return buildValueResponse(cachedGrowthTop10, growthCacheTime.format(TIME_FMT) + " 기준", trading);
        }
        List<RecommendationDto> result;
        try {
            result = calculateGrowthTop10();
            if (!result.isEmpty()) {
                cachedGrowthTop10 = result;
                growthCacheTime = now;
            }
        } catch (Exception e) {
            log.error("[성장주TOP10] 계산 실패: {}", e.getMessage(), e);
            result = cachedGrowthTop10 != null ? cachedGrowthTop10 : Collections.emptyList();
        }
        return buildValueResponse(result, now.format(TIME_FMT) + " 기준", trading);
    }

    private List<RecommendationDto> calculateGrowthTop10() {
        long t0 = System.currentTimeMillis();
        List<StockFinancialData> all = financialDataRepository.findLatestPerStock();

        List<GrowthScoredStock> scored = new ArrayList<>();
        for (StockFinancialData fin : all) {
            if (fin.getStockCode() == null || fin.getStockName() == null) continue;
            int[] parts = computeGrowthScoreParts(fin.getRevenueGrowth(), fin.getProfitGrowth(), fin.getPeg());
            int score = Math.min(20, parts[0] + parts[1] + parts[2]);
            if (score <= 0) continue;
            GrowthScoredStock gs = new GrowthScoredStock();
            gs.stockCode = fin.getStockCode();
            gs.stockName = fin.getStockName();
            gs.score = score;
            gs.revScore = parts[0];
            gs.profitScore = parts[1];
            gs.pegScore = parts[2];
            gs.tags = growthTags(fin.getRevenueGrowth(), fin.getProfitGrowth(), fin.getPeg());
            scored.add(gs);
        }

        // 점수 desc → 상위 30 만 리스크 검사(DART) → 페널티 후 재정렬 → top10 (저평가와 동일 골격)
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        List<GrowthScoredStock> shortlist = scored.stream().limit(30).collect(Collectors.toList());
        for (GrowthScoredStock gs : shortlist) {
            try {
                if (riskManagementService.quickDangerCheck(gs.stockCode, gs.stockName)) {
                    gs.score = Math.max(0, gs.score - 5);
                    gs.tags.add("⚠리스크공시");
                }
            } catch (Exception ignore) { /* 페널티 안 줌 */ }
        }
        shortlist.sort((a, b) -> Integer.compare(b.score, a.score));
        List<GrowthScoredStock> top = shortlist.stream().limit(10).collect(Collectors.toList());

        log.info("[성장주TOP10] financial_data {}종목 → {}건 후보 → top10 ({}ms)",
                all.size(), scored.size(), System.currentTimeMillis() - t0);

        return top.stream().map(gs -> RecommendationDto.builder()
                .stockCode(gs.stockCode)
                .stockName(gs.stockName)
                .totalScore(Math.min(100, gs.score * 5))   // 20점 만점 → 100점 환산
                .aiStrategy(NA)
                .earnings(NA)
                .supplyDemand(NA)
                .technical(NA)
                .sectorMomentum(NA)
                .valueStability(NA)
                .growth(gs.score)
                .validCount(1)
                .tags(new ArrayList<>(gs.tags))
                .growthRevScore(gs.revScore)
                .growthProfitScore(gs.profitScore)
                .growthPegScore(gs.pegScore)
                .build()).toList();
    }

    private static class GrowthScoredStock {
        String stockCode;
        String stockName;
        int score;
        List<String> tags;
        int revScore;       // 0~7
        int profitScore;    // 0~8
        int pegScore;       // 0~5
    }

    // ==================== 낙폭과대 반등 TOP 10 (별도 트랙) ====================

    private static final int OVERSOLD_MIN_HISTORY = 25;   // MA20 + RSI 안정성 (QuantTa 와 동일)
    private static final int OVERSOLD_LOAD_DAYS = 130;    // 로드 창 ≈ 영업일 6개월

    /**
     * 낙폭과대 반등 점수 분해 — {RSI과매도(0~8), 낙폭/이격도(0~7), 반등조짐(0~5)}. 순수 함수(테스트 대상).
     * <p>게이트: RSI ≤ 40 <b>AND</b> MA20 대비 −5% 이하(낙폭) 이어야 후보 — 둘 중 하나라도 미달이면 {0,0,0}.
     * 추격(이미 오른 종목)의 정반대 — "많이 빠졌고 과매도인데 돌아설 조짐" 종목을 발굴.
     *
     * @param rsi RSI14, disparityPct (종가−MA20)/MA20×100 (음수=MA20 아래), volRatio 최근/20일평균 거래량, changeRate 당일%
     */
    static int[] computeOversoldScoreParts(Double rsi, Double disparityPct, Double volRatio, Double changeRate) {
        if (rsi == null || rsi > 40.0) return new int[]{0, 0, 0};
        if (disparityPct == null || disparityPct > -5.0) return new int[]{0, 0, 0};
        int rsiScore = rsi <= 25 ? 8 : rsi <= 30 ? 6 : rsi <= 35 ? 4 : 2;
        double d = disparityPct;
        int dropScore = d <= -25 ? 7 : d <= -18 ? 5 : d <= -12 ? 3 : 1;
        int reboundScore = 0;
        if (changeRate != null && changeRate > 0) reboundScore += 3;   // 당일 양봉 = 반등 시작
        if (volRatio != null && volRatio >= 1.5) reboundScore += 2;    // 거래량 동반
        return new int[]{rsiScore, dropScore, reboundScore};
    }

    static List<String> oversoldTags(Double rsi, Double disparityPct, Double volRatio, Double changeRate) {
        List<String> tags = new ArrayList<>();
        if (rsi != null && rsi <= 30) tags.add("RSI" + (int) Math.round(rsi) + "과매도");
        if (disparityPct != null && disparityPct <= -12) tags.add("이격도" + (int) Math.round(disparityPct) + "%");
        if (changeRate != null && changeRate > 0) tags.add("반등시작");
        if (volRatio != null && volRatio >= 1.5) tags.add("거래량급증");
        return tags;
    }

    /** 최근 거래량 / 직전 window 일 평균 (rowsDesc: tradeDate DESC, 0=최신). */
    private static Double computeVolRatio(List<StockPriceHistory> rowsDesc, int window) {
        if (rowsDesc.size() < window + 1) return null;
        BigDecimal latest = rowsDesc.get(0).getVolume();
        if (latest == null || latest.signum() <= 0) return null;
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (int i = 1; i <= window && i < rowsDesc.size(); i++) {
            BigDecimal v = rowsDesc.get(i).getVolume();
            if (v != null && v.signum() > 0) { sum = sum.add(v); n++; }
        }
        if (n == 0) return null;
        BigDecimal avg = sum.divide(BigDecimal.valueOf(n), 4, java.math.RoundingMode.HALF_UP);
        if (avg.signum() <= 0) return null;
        return latest.doubleValue() / avg.doubleValue();
    }

    /**
     * 낙폭과대 반등 TOP 10 — 가격 히스토리로 RSI 과매도 + MA20 낙폭 + 반등 조짐 스캔.
     * 저평가·성장과 별도 트랙, 추격의 정반대(많이 빠진 종목). 캐시 30분.
     */
    public Top5Response getOversoldTop10() {
        LocalDateTime now = LocalDateTime.now();
        boolean trading = isTradingHours(now);
        if (cachedOversoldTop10 != null && oversoldCacheTime != null
                && oversoldCacheTime.isAfter(now.minusMinutes(CACHE_MINUTES))) {
            return buildValueResponse(cachedOversoldTop10, oversoldCacheTime.format(TIME_FMT) + " 기준", trading);
        }
        List<RecommendationDto> result;
        try {
            result = calculateOversoldTop10();
            if (!result.isEmpty()) {
                cachedOversoldTop10 = result;
                oversoldCacheTime = now;
            }
        } catch (Exception e) {
            log.error("[낙폭과대TOP10] 계산 실패: {}", e.getMessage(), e);
            result = cachedOversoldTop10 != null ? cachedOversoldTop10 : Collections.emptyList();
        }
        return buildValueResponse(result, now.format(TIME_FMT) + " 기준", trading);
    }

    private List<RecommendationDto> calculateOversoldTop10() {
        long t0 = System.currentTimeMillis();
        List<String> universe = priceHistoryRepository.findStockCodesWithMinHistory(OVERSOLD_MIN_HISTORY);
        if (universe.isEmpty()) return Collections.emptyList();
        List<StockPriceHistory> all = priceHistoryRepository.findByStockCodesSince(
                universe, java.time.LocalDate.now().minusDays(OVERSOLD_LOAD_DAYS));
        Map<String, List<StockPriceHistory>> byCode = all.stream()
                .collect(Collectors.groupingBy(StockPriceHistory::getStockCode));

        List<OversoldScoredStock> scored = new ArrayList<>();
        for (Map.Entry<String, List<StockPriceHistory>> e : byCode.entrySet()) {
            List<StockPriceHistory> rows = e.getValue();   // tradeDate DESC (findByStockCodesSince 보장)
            if (rows.size() < OVERSOLD_MIN_HISTORY) continue;
            List<BigDecimal> prices = rows.stream().map(StockPriceHistory::getClosePrice)
                    .filter(Objects::nonNull).collect(Collectors.toList());
            if (prices.size() < OVERSOLD_MIN_HISTORY) continue;
            StockPriceHistory latest = rows.get(0);
            BigDecimal price = latest.getClosePrice();
            if (price == null || price.signum() <= 0) continue;
            TechnicalIndicatorsDto ind = technicalIndicatorService.calculate(prices);
            if (ind == null || ind.getRsi14() == null || ind.getMa20() == null || ind.getMa20().signum() <= 0) continue;

            double rsi = ind.getRsi14().doubleValue();
            double disparity = price.subtract(ind.getMa20()).doubleValue() / ind.getMa20().doubleValue() * 100.0;
            Double volRatio = computeVolRatio(rows, 20);
            Double changeRate = latest.getChangeRate() != null ? latest.getChangeRate().doubleValue() : null;

            int[] parts = computeOversoldScoreParts(rsi, disparity, volRatio, changeRate);
            int score = Math.min(20, parts[0] + parts[1] + parts[2]);
            if (score <= 0) continue;

            OversoldScoredStock os = new OversoldScoredStock();
            os.stockCode = e.getKey();
            os.stockName = latest.getStockName();
            os.score = score;
            os.rsiScore = parts[0];
            os.dropScore = parts[1];
            os.reboundScore = parts[2];
            os.tags = oversoldTags(rsi, disparity, volRatio, changeRate);
            scored.add(os);
        }

        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        List<OversoldScoredStock> shortlist = scored.stream().limit(30).collect(Collectors.toList());
        for (OversoldScoredStock os : shortlist) {
            try {
                if (riskManagementService.quickDangerCheck(os.stockCode, os.stockName)) {
                    os.score = Math.max(0, os.score - 5);
                    os.tags.add("⚠리스크공시");
                }
            } catch (Exception ignore) { /* 페널티 안 줌 */ }
        }
        shortlist.sort((a, b) -> Integer.compare(b.score, a.score));
        List<OversoldScoredStock> top = shortlist.stream().limit(10).collect(Collectors.toList());

        log.info("[낙폭과대TOP10] universe {}종목 → {}건 후보 → top10 ({}ms)",
                universe.size(), scored.size(), System.currentTimeMillis() - t0);

        return top.stream().map(os -> RecommendationDto.builder()
                .stockCode(os.stockCode)
                .stockName(os.stockName)
                .totalScore(Math.min(100, os.score * 5))
                .aiStrategy(NA).earnings(NA).supplyDemand(NA).technical(NA).sectorMomentum(NA)
                .valueStability(NA).growth(NA)
                .validCount(1)
                .tags(new ArrayList<>(os.tags))
                .oversoldRsiScore(os.rsiScore)
                .oversoldDropScore(os.dropScore)
                .oversoldReboundScore(os.reboundScore)
                .build()).toList();
    }

    private static class OversoldScoredStock {
        String stockCode;
        String stockName;
        int score;
        List<String> tags;
        int rsiScore;       // 0~8
        int dropScore;      // 0~7
        int reboundScore;   // 0~5
    }

    // ==================== 실적 서프라이즈 / 스마트머니(수급) TOP 10 (별도 트랙) ====================
    // 둘 다 기존 카테고리 산식(scoreEarnings/scoreSupplyDemand)을 빈 map 에 그대로 적용해 랭킹만 —
    // 산식 단일 출처(종합추천과 동일). 데이터 소스가 candidate 제한 없이 broad 라 그대로 전체 스캔이 됨.

    /** 실적 서프라이즈 TOP 10 — 흑자전환/영업이익 급증. 캐시 30분. */
    public Top5Response getEarningsTop10() {
        LocalDateTime now = LocalDateTime.now();
        boolean trading = isTradingHours(now);
        if (cachedEarningsTop10 != null && earningsCacheTime != null
                && earningsCacheTime.isAfter(now.minusMinutes(CACHE_MINUTES))) {
            return buildValueResponse(cachedEarningsTop10, earningsCacheTime.format(TIME_FMT) + " 기준", trading);
        }
        List<RecommendationDto> result;
        try {
            result = calculateCategoryTop10(true);
            if (!result.isEmpty()) { cachedEarningsTop10 = result; earningsCacheTime = now; }
        } catch (Exception e) {
            log.error("[실적TOP10] 계산 실패: {}", e.getMessage(), e);
            result = cachedEarningsTop10 != null ? cachedEarningsTop10 : Collections.emptyList();
        }
        return buildValueResponse(result, now.format(TIME_FMT) + " 기준", trading);
    }

    /** 스마트머니(수급) TOP 10 — 외국인·기관 연속/대량 순매수. 캐시 30분. */
    public Top5Response getSmartMoneyTop10() {
        LocalDateTime now = LocalDateTime.now();
        boolean trading = isTradingHours(now);
        if (cachedSmartMoneyTop10 != null && smartMoneyCacheTime != null
                && smartMoneyCacheTime.isAfter(now.minusMinutes(CACHE_MINUTES))) {
            return buildValueResponse(cachedSmartMoneyTop10, smartMoneyCacheTime.format(TIME_FMT) + " 기준", trading);
        }
        List<RecommendationDto> result;
        try {
            result = calculateCategoryTop10(false);
            if (!result.isEmpty()) { cachedSmartMoneyTop10 = result; smartMoneyCacheTime = now; }
        } catch (Exception e) {
            log.error("[스마트머니TOP10] 계산 실패: {}", e.getMessage(), e);
            result = cachedSmartMoneyTop10 != null ? cachedSmartMoneyTop10 : Collections.emptyList();
        }
        return buildValueResponse(result, now.format(TIME_FMT) + " 기준", trading);
    }

    /**
     * 실적/수급 단일 카테고리 TOP10 공통 — 빈 map 에 기존 산식 적용 후 해당 카테고리 점수로 랭킹.
     * @param earnings true=실적(scoreEarnings), false=수급(scoreSupplyDemand)
     */
    private List<RecommendationDto> calculateCategoryTop10(boolean earnings) {
        Map<String, StockScore> map = new java.util.HashMap<>();
        if (earnings) scoreEarnings(map); else scoreSupplyDemand(map);

        java.util.function.ToIntFunction<StockScore> pick = earnings ? (s -> s.earnings) : (s -> s.supplyDemand);
        List<StockScore> scored = map.values().stream()
                .filter(s -> pick.applyAsInt(s) > 0)
                .sorted((a, b) -> Integer.compare(pick.applyAsInt(b), pick.applyAsInt(a)))
                .collect(Collectors.toList());

        List<StockScore> shortlist = scored.stream().limit(30).collect(Collectors.toList());
        for (StockScore s : shortlist) {
            try {
                if (riskManagementService.quickDangerCheck(s.stockCode, s.stockName)) {
                    if (earnings) s.earnings = Math.max(0, s.earnings - 5);
                    else s.supplyDemand = Math.max(0, s.supplyDemand - 5);
                    s.tags.add("⚠리스크공시");
                }
            } catch (Exception ignore) { /* 페널티 안 줌 */ }
        }
        shortlist.sort((a, b) -> Integer.compare(pick.applyAsInt(b), pick.applyAsInt(a)));
        List<StockScore> top = shortlist.stream().limit(10).collect(Collectors.toList());

        log.info("[{}TOP10] {}건 후보 → top10", earnings ? "실적" : "스마트머니", scored.size());

        return top.stream().map(s -> RecommendationDto.builder()
                .stockCode(s.stockCode)
                .stockName(s.stockName)
                .totalScore(Math.min(100, pick.applyAsInt(s) * 5))
                .aiStrategy(NA)
                .earnings(earnings ? s.earnings : NA)
                .supplyDemand(earnings ? NA : s.supplyDemand)
                .technical(NA).sectorMomentum(NA).valueStability(NA).growth(NA)
                .validCount(1)
                .tags(new ArrayList<>(s.tags))
                .build()).toList();
    }

    // ==================== Core Calculation ====================

    private List<RecommendationDto> calculate() {
        Map<String, StockScore> scoreMap = new HashMap<>();

        // 정렬 tie-break 용 — 어제(또는 20시간 전) 스냅샷 점수. delta = 오늘 - 어제 desc 로
        // "막 올라온 종목"이 "이미 다 올라간 종목" 보다 우선. prevScoreMap 비어있으면 0 으로
        // 폴백 → 최후 tie-break 인 changeRate desc 로 위임.
        final Map<String, Integer> prevScoreMap = new HashMap<>();
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(20);
            List<RecommendationSnapshot> prev = snapshotRepository.findPreviousSnapshot(cutoff);
            for (RecommendationSnapshot s : prev) {
                prevScoreMap.put(s.getStockCode(), s.getTotalScore());
            }
        } catch (Exception e) {
            log.debug("[종합추천] prev 스냅샷 조회 실패 (delta 0 으로 fallback): {}", e.getMessage());
        }

        // 단계별 소요 시간 측정 — 백그라운드 calculate 가 30초+ 걸리던 병목 식별용.
        // (calculate 자체가 백그라운드라 사용자 응답엔 영향 없지만 KIS rate limiter 큐 점유로
        //  다른 워머/요청에 영향. 어디가 느린지 보이면 다음 라운드에서 핀포인트 fix 가능.)
        long t0 = System.currentTimeMillis();
        int aiCount = scoreAiStrategy(scoreMap);
        long aiMs = System.currentTimeMillis() - t0; t0 = System.currentTimeMillis();
        scoreEarnings(scoreMap);
        long erMs = System.currentTimeMillis() - t0; t0 = System.currentTimeMillis();
        scoreSupplyDemand(scoreMap);
        long sdMs = System.currentTimeMillis() - t0; t0 = System.currentTimeMillis();
        // 섹터: AI 스냅샷 + 시장분위기 (모든 scoreMap 종목에 부여) + phase 34 regime 판정
        MarketRegime regime = scoreSectorMomentum(scoreMap);
        long scMs = System.currentTimeMillis() - t0; t0 = System.currentTimeMillis();
        // 기술: 마지막 (모든 종목 수집 후)
        scoreTechnical(scoreMap);
        long tcMs = System.currentTimeMillis() - t0; t0 = System.currentTimeMillis();
        // 가치/안정성: PBR·ROE·부채비율 (DB 만 — 빠름)
        scoreValueStability(scoreMap);
        // 성장성: 매출/이익 성장률 + PEG (DB 만 — 빠름). 가치와 분리된 별도 LONG 트랙.
        scoreGrowth(scoreMap);
        long vsMs = System.currentTimeMillis() - t0; t0 = System.currentTimeMillis();
        // 리스크 공시 페널티: DART API 호출 — 종목당 2~3초 소요 → 상위 후보(30개) 만 검사.
        applyRiskPenalty(scoreMap);
        long rkMs = System.currentTimeMillis() - t0; t0 = System.currentTimeMillis();
        // 실시간 교차검증: MA20 하회/수급 괴리 감지 → 점수 보정
        applyRealtimeChecks(scoreMap);
        long rtMs = System.currentTimeMillis() - t0;
        // P2: 신규 진입 + 5일 가속 종목 감점 (어제 스냅샷 밖에서 갑자기 등장 + 이미 많이 오른 패턴)
        // phase 36: BULL 강세장에서는 5일 +15% 가 정상 추세 종목에서도 흔하게 발생 → 무차별
        // 페널티 부작용 차단 위해 regime 인자 추가, BULL 이면 스킵.
        applyNewEntryPenalty(scoreMap, prevScoreMap, regime);
        // phase 34: 시장 국면 적응형 가중치 — BULL/BEAR/SIDEWAYS 별 카테고리 multiplier
        applyMarketRegimeWeighting(scoreMap, regime);
        log.info("[종합추천] 단계별 소요 - AI:{}ms 실적:{}ms 수급:{}ms 섹터:{}ms 기술:{}ms 가치:{}ms 리스크:{}ms 실시간:{}ms (합 {}ms)",
                aiMs, erMs, sdMs, scMs, tcMs, vsMs, rkMs, rtMs,
                aiMs + erMs + sdMs + scMs + tcMs + vsMs + rkMs + rtMs);

        // 디버그 로그
        for (StockScore s : scoreMap.values()) {
            log.debug("[종합추천] {} — AI:{} 실적:{} 수급:{} 기술:{} 섹터:{} 가치:{} 성장:{} (유효 {}개)",
                    s.stockName, s.aiStrategy, s.earnings, s.supplyDemand, s.technical, s.sectorMomentum,
                    s.valueStability, s.growth, countValidCategories(s));
        }
        log.info("[종합추천] scoreMap {}종목 (AI시드 {}개)", scoreMap.size(), aiCount);

        List<RecommendationDto> results = scoreMap.values().stream()
                .filter(s -> countValidCategories(s) >= 3)  // 4카테고리 중 최소 3개 valid (75% 커버리지)
                .filter(s -> normalizeScore(
                        // AI전략·가치는 totalScore 산식에서 제외 — 후보 발굴/태그 용도.
                        // phase31c 후속: 필터 raw 합산에서도 valueStability 제거 — 기존엔 필터에만
                        // 포함되고 toDto/getNormalizedTotal 에선 빠져서 "55점 컷 통과 후 표시 점수는
                        // 50점" 같은 일관성 깨짐 발생. v7 (5→4 카테고리) 전환 시 누락된 부분.
                        s.earnings + s.supplyDemand + s.technical + s.sectorMomentum,
                        countValidCategories(s)) >= 55) // 관망 컷 — 60→55 완화 (TOP10 자리 채우기, 데이터 부족시 5건만 노출되던 문제)
                .sorted(recommendationComparator(prevScoreMap))
                .limit(10)
                .map(this::toDto)
                .toList();

        this.cachedScoreMap = scoreMap;   // union 보드 4-cat lookup용 보존(재점수 방지)

        for (RecommendationDto r : results) {
            log.info("[종합추천] #{} {} — 총{}점 (AI:{} 실적:{} 수급:{} 기술:{} 섹터:{} 가치:{}) 유효{}개",
                    results.indexOf(r) + 1, r.getStockName(), r.getTotalScore(),
                    r.getAiStrategy(), r.getEarnings(), r.getSupplyDemand(),
                    r.getTechnical(), r.getSectorMomentum(), r.getValueStability(), r.getValidCount());
        }
        log.info("[종합추천] TOP 10 계산 완료 ({}건)", results.size());
        return results;
    }

    // ==================== ⑥ 가치/안정성 (/20) ====================
    // PBR·ROE·부채비율·흑자 + 대주주 리스크 페널티
    // 모멘텀 카테고리 비중 다이루션해 저평가 우량주 부각
    //
    // [수정 이력] 같은 종목인데 row 마다 일부 컬럼만 채워지는 데이터 패턴 발견:
    //   예) 005930 (삼성전자) — 미래 일자 12-31 annual row 엔 영업이익만, 5-07 row 엔 PBR/ROE 만,
    //       5-06 row 엔 PBR + (placeholder 0의) ROE/debt_ratio.
    // 단일 row 픽하면 NULL/0 무더기로 0점→NA 노출. 최신 10건에서 first non-null 로 합성하고,
    // 0 placeholder 의심값(특히 debt_ratio) 은 점수 가산에서 배제.
    private void scoreValueStability(Map<String, StockScore> scoreMap) {
        int calc = 0, miss = 0;
        for (StockScore stock : scoreMap.values()) {
            try {
                List<StockFinancialData> recent = financialDataRepository
                        .findTop10ByStockCodeOrderByReportDateDesc(stock.stockCode);
                if (recent.isEmpty()) { miss++; continue; }

                // 각 필드별 합성 — 단일 row 의 결손 컬럼을 다른 최근 row 로 보완.
                // [버그 fix] 기존 firstNonNull 은 최신 row 의 0.00 placeholder 를 "값 있음"으로
                //   잡고 멈춰서, 뒤쪽 row 의 진짜 값에 도달하지 못했음 (예: 005930 debt_ratio=0.00
                //   placeholder → 부채 4점 + 흑자 3점 누락 → 18점이어야 할 삼성전자가 5점).
                //   → 0/음수가 비현실적인 필드(pbr·debt·equity)는 firstPositive 로 placeholder 를
                //     건너뛰고, 0 이 placeholder 인 필드(roe·영업이익, 단 음수=적자는 의미 보존)는
                //     firstNonZero 로 0 만 건너뛴다.
                BigDecimal pbr = firstPositive(recent, StockFinancialData::getPbr);
                BigDecimal roe = firstNonZero(recent, StockFinancialData::getRoe);
                BigDecimal debtRatio = firstPositive(recent, StockFinancialData::getDebtRatio);
                BigDecimal opProfit = firstNonZero(recent, StockFinancialData::getOperatingProfit);
                BigDecimal equity = firstPositive(recent, StockFinancialData::getTotalEquity);

                int score = 0;
                List<String> tags = new ArrayList<>();

                // 1) PBR (8점) — 저평가 핵심
                if (pbr != null && pbr.signum() > 0) {
                    double v = pbr.doubleValue();
                    if (v <= 0.7) { score += 8; tags.add("PBR저평가"); }
                    else if (v <= 1.0) { score += 6; tags.add("PBR<1"); }
                    else if (v <= 1.5) score += 4;
                    else if (v <= 2.0) score += 2;
                }

                // 2) ROE×(1/PBR) 결합 (5점) — 자본효율+저평가 (마법공식 변형)
                if (roe != null && pbr != null && pbr.signum() > 0) {
                    double combined = roe.doubleValue() / pbr.doubleValue();
                    if (combined >= 15) { score += 5; tags.add("우량+저평가"); }
                    else if (combined >= 10) score += 4;
                    else if (combined >= 7) score += 3;
                    else if (combined >= 4) score += 1;
                }

                // 3) 부채비율 (4점) — 재무 안정성
                // signum()>=0 → >0 변경: 0.00 placeholder 가 "저부채 +4점" 으로 오인되던 버그 차단.
                // 실제 0% 무차입 회사는 드물고, 데이터 신뢰성 우선.
                if (debtRatio != null && debtRatio.signum() > 0) {
                    double v = debtRatio.doubleValue();
                    if (v <= 50) { score += 4; tags.add("저부채"); }
                    else if (v <= 100) score += 3;
                    else if (v <= 200) score += 1;
                }

                // 4) 영업이익+자본총계 양수 (3점) — 흑자 + 자본잠식 없음
                if (opProfit != null && opProfit.signum() > 0
                        && equity != null && equity.signum() > 0) {
                    score += 3;
                }

                // 5) 대주주 리스크 페널티 — applyRiskPenalty() 로 분리.
                //    quickDangerCheck 가 DART API 호출이라 종목당 2~3초 소요 →
                //    261종목 × 2.5초 ≈ 10분 bottleneck 이었음. 상위 후보(~30개) 만 검사.

                // 데이터 row 가 존재하면 valueStability=0 이상으로 확정 (NA(-1) 와 구분).
                // 0 점은 "데이터는 있으나 가치주 기준(저PBR) 미달" 의미 — UI 에서 "0/20" 으로 표기.
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

    // ==================== ⑥ 성장성 (/20) — 별도 LONG 트랙 ====================
    // 가치(valueStability)는 "지금 싼가"만 본다. 시클리컬/성장주(예: 반도체)는 사이클 바닥에서
    // ROE 급락·PBR 상승으로 밸류 점수가 오히려 낮게 찍히는 구조적 한계가 있음.
    // → 매출/이익 성장률 + PEG 로 "성장성"을 별도 축으로 분리해 보완한다.
    //   valueStability 와 동일하게 totalScore 산식엔 미포함 (후보 발굴/표시용 LONG factor).
    // placeholder 0 처리: revenueGrowth/profitGrowth 는 음수(역성장)가 의미 있으므로 firstNonZero,
    //   PEG 는 0 이하가 무의미(EPS 역성장)하므로 firstPositive.
    /**
     * 성장 점수 분해 — {매출(0~7), 이익(0~8), PEG(0~5)}. scoreGrowth(종합추천 성장factor)와
     * 성장주 TOP10 의 <b>단일 산식 출처</b> — 둘 중 한 곳만 바꾸지 말 것. 순수 함수(테스트 대상).
     */
    static int[] computeGrowthScoreParts(BigDecimal revGrowth, BigDecimal profitGrowth, BigDecimal peg) {
        int rev = 0, profit = 0, pegScore = 0;
        if (revGrowth != null) {
            double v = revGrowth.doubleValue();   // 음수(역성장) → 0
            if (v >= 30) rev = 7; else if (v >= 20) rev = 5; else if (v >= 10) rev = 3; else if (v >= 0) rev = 1;
        }
        if (profitGrowth != null) {
            double v = profitGrowth.doubleValue();
            if (v >= 50) profit = 8; else if (v >= 30) profit = 6; else if (v >= 15) profit = 4; else if (v >= 0) profit = 2;
        }
        if (peg != null) {
            double v = peg.doubleValue();         // PEG = PER / EPS성장률, 낮을수록 매력
            if (v <= 0.7) pegScore = 5; else if (v <= 1.0) pegScore = 4; else if (v <= 1.5) pegScore = 2; else if (v <= 2.0) pegScore = 1;
        }
        return new int[]{rev, profit, pegScore};
    }

    /** 성장 태그 — computeGrowthScoreParts 와 동일 임계 기반. 단일 출처. */
    static List<String> growthTags(BigDecimal revGrowth, BigDecimal profitGrowth, BigDecimal peg) {
        List<String> tags = new ArrayList<>();
        if (revGrowth != null && revGrowth.doubleValue() >= 30) tags.add("매출고성장");
        if (profitGrowth != null && profitGrowth.doubleValue() >= 50) tags.add("이익급증");
        if (peg != null) {
            double v = peg.doubleValue();
            if (v <= 0.7) tags.add("저평가성장(PEG<1)");
            else if (v <= 1.0) tags.add("PEG<1");
        }
        return tags;
    }

    private void scoreGrowth(Map<String, StockScore> scoreMap) {
        int calc = 0, miss = 0;
        for (StockScore stock : scoreMap.values()) {
            try {
                List<StockFinancialData> recent = financialDataRepository
                        .findTop10ByStockCodeOrderByReportDateDesc(stock.stockCode);
                if (recent.isEmpty()) { miss++; continue; }

                BigDecimal revGrowth = firstNonZero(recent, StockFinancialData::getRevenueGrowth);
                BigDecimal profitGrowth = firstNonZero(recent, StockFinancialData::getProfitGrowth);
                BigDecimal peg = firstPositive(recent, StockFinancialData::getPeg);

                // 셋 다 데이터 없으면 NA(-1) 로 두고 표시에서 제외 (valueStability 와 동일).
                if (revGrowth == null && profitGrowth == null && peg == null) { miss++; continue; }

                // 산식은 computeGrowthScoreParts/growthTags 단일 출처 (성장주 TOP10 과 공용).
                int[] parts = computeGrowthScoreParts(revGrowth, profitGrowth, peg);
                stock.growth = Math.min(20, parts[0] + parts[1] + parts[2]);
                if (stock.growth > 0) {
                    stock.tags.addAll(growthTags(revGrowth, profitGrowth, peg));
                    calc++;
                }
            } catch (Exception e) {
                log.debug("[종합추천] 성장성 계산 실패 {}: {}", stock.stockCode, e.getMessage());
                miss++;
            }
        }
        log.debug("[종합추천] 성장성: {}건 계산, {}건 데이터부족", calc, miss);
    }

    private static BigDecimal firstNonNull(List<StockFinancialData> rows,
                                            java.util.function.Function<StockFinancialData, BigDecimal> getter) {
        for (StockFinancialData r : rows) {
            BigDecimal v = getter.apply(r);
            if (v != null) return v;
        }
        return null;
    }

    /** 첫 번째 "양수" 값 — 0/음수 placeholder 를 건너뛰고 진짜 값을 찾는다.
     *  0 이나 음수가 비현실적인 필드(PBR·부채비율·자본총계)용. */
    private static BigDecimal firstPositive(List<StockFinancialData> rows,
                                            java.util.function.Function<StockFinancialData, BigDecimal> getter) {
        for (StockFinancialData r : rows) {
            BigDecimal v = getter.apply(r);
            if (v != null && v.signum() > 0) return v;
        }
        return null;
    }

    /** 첫 번째 "0이 아닌" 값 — 0.00 placeholder 만 건너뛰고 음수(적자 등)는 보존한다.
     *  ROE·영업이익처럼 음수가 의미를 갖는 필드용. */
    private static BigDecimal firstNonZero(List<StockFinancialData> rows,
                                           java.util.function.Function<StockFinancialData, BigDecimal> getter) {
        for (StockFinancialData r : rows) {
            BigDecimal v = getter.apply(r);
            if (v != null && v.signum() != 0) return v;
        }
        return null;
    }

    // ==================== ⑦ 리스크 공시 페널티 (-5) ====================
    // riskManagementService.quickDangerCheck 가 DART API 호출이라 종목당 2~3초 소요.
    // 261종목 전체 검사 시 ~10분 소요로 calculate 전체 시간을 사실상 결정.
    // → 추천 후보(상위 30개)만 검사: 30 × 2.5s ≈ 75초로 단축 + TOP10 정확도 유지.
    private void applyRiskPenalty(Map<String, StockScore> scoreMap) {
        List<StockScore> top = scoreMap.values().stream()
                .filter(s -> countValidCategories(s) >= 3)
                .sorted(Comparator.comparingInt(StockScore::getNormalizedTotal).reversed())
                .limit(30)
                .collect(Collectors.toList());
        int hit = 0;
        for (StockScore stock : top) {
            try {
                // stockCode 우선 매핑 — DartService corpCode 캐시 hit 으로 정확한 공시 조회.
                if (riskManagementService.quickDangerCheck(stock.stockCode, stock.stockName)) {
                    stock.valueStability = Math.max(0, stock.valueStability - 5);
                    stock.tags.add("⚠리스크공시");
                    hit++;
                }
            } catch (Exception ignore) { /* 리스크 조회 실패 시 페널티 안 줌 */ }
        }
        log.info("[종합추천] 리스크 공시 검사: {}건 후보 중 {}건 히트", top.size(), hit);
    }

    // ==================== ① AI전략 (/20) — 후보 발굴 트랙 ====================
    //
    // 위상 명시 (phase 32):
    //   * 본 산식 (toDto / getNormalizedTotal / countValidCategories) 에 aiStrategy 는 포함 안 됨.
    //     v7 (5→4 카테고리) 전환의 핵심 결정 — AI 전략 1·2·3위 시드 +8/+5/+3 은 최종 정렬에
    //     0 영향. delta tie-break (phase 31) 도 4 카테고리 정규화 점수만 본다.
    //   * 그렇다면 왜 scoreMap 에 entry 를 만드나? → "후보 풀 확장기" 로만 동작.
    //     AI 가 발굴한 종목이 scoreMap 에 등록되면, 후속 단계 (실적/수급/기술/섹터/가치) 에서
    //     점수를 부여받을 기회가 생긴다. 다른 카테고리 점수가 0 인 채로 끝나는 종목은
    //     countValidCategories(s) >= 3 필터에서 자연 탈락 → 최종 TOP10 에 노출 안 됨.
    //   * 결과: AI 시드 단독으로는 추천에 오를 수 없고, 발굴된 종목 중 펀더멘털/모멘텀이
    //     동시에 받쳐주는 것만 추천 풀에 진입. "AI가 주관하지 않고 발굴만 한다" 가 의도.
    //   * 만약 향후 AI 점수를 산식에 정식 포함하고 싶으면 TOTAL_CATEGORIES (line 근처) 를
    //     5 로 올리고 countValidCategories / toDto raw 합산에 aiStrategy 추가하면 cap 표 자동
    //     재조정.

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
                    // 수급 곡선 뒤집기 — 2~3일 정점, 5일+ 는 후반(이미 다 산 후) 으로 가점 ↓.
                    // 5일 연속매수는 외국인이 매물 받아갈 카운터파티 만든 후일 가능성이 높음.
                    int dp = (days >= 5) ? 4 : (days >= 4) ? 6 : (days >= 3) ? 10 : 8;
                    int ab = (avg >= 50) ? 4 : (avg >= 20) ? 2 : (avg >= 5) ? 1 : 0;
                    score.supplyDemand = Math.min(20, score.supplyDemand + dp + ab);
                    String amtStr = avg >= 1 ? String.format("(일%.0f억)", avg) : "";
                    String phase = days >= 5 ? "후반" : (days >= 3 ? "초기" : "시작");
                    score.tags.add("외국인" + days + "일연속" + phase + amtStr);
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
                    // 외국인과 동일 — 2~3일 정점, 5일+ 후반 가점 축소.
                    int dp = (days >= 5) ? 3 : (days >= 4) ? 5 : (days >= 3) ? 8 : 6;
                    int ab = (avg >= 50) ? 3 : (avg >= 20) ? 1 : 0;
                    score.supplyDemand = Math.min(20, score.supplyDemand + dp + ab);
                    String phase = days >= 5 ? "후반" : (days >= 3 ? "초기" : "시작");
                    score.tags.add("기관" + days + "일연속" + phase);
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
    // v6: AI 스냅샷 의존 분리.
    // phase31 P1: 시장분위기 보너스 일괄 부여 제거 — 모든 종목에 +2~6 동일 offset 깔면 점수가
    // 아니라 상수 가산이라 변별력 깎임("강세장에 추천 다 떠있다 다음날 다 조정"의 구조적 원인).
    // marketMoodBonus 는 운영 로그에만 노출 (시장 메타 정보).

    private MarketRegime scoreSectorMomentum(Map<String, StockScore> scoreMap) {
        // 1. 섹터 로테이션 — 시장 분위기 메타 (점수엔 부여 안 함, 로그용) + phase 34 regime 판정.
        int marketMoodBonus = 0;
        MarketRegime regime = MarketRegime.SIDEWAYS;
        try {
            List<SectorRotationDto> rotations = sectorTradingService.getSectorRotation();
            if (rotations != null && !rotations.isEmpty()) {
                // phase 34: 전체 섹터 평균 등락률로 시장 국면 판정. 양봉/INFLOW 필터링 없는 raw 평균
                // 이라야 약세장도 음수로 잡힘.
                double overallAvg = rotations.stream()
                        .mapToDouble(r -> safeDouble(r.getAvgChangeRate()))
                        .average().orElse(0.0);
                MarketRegime fresh;
                if (overallAvg > 1.0) fresh = MarketRegime.BULL;
                else if (overallAvg < -1.0) fresh = MarketRegime.BEAR;
                else fresh = MarketRegime.SIDEWAYS;

                // phase 35: hysteresis dead band 0.5 — 임계 근처 흔들림(예: -1.1% 한 번 찍었다고
                // BULL→BEAR 즉시 전환) 방지. 강한 반전(avg ±1.0 통과 + dead band 위반)은 한 번에 전환.
                //  · 직전 BULL AND avg > -0.5  → BULL 유지
                //  · 직전 BEAR AND avg < +0.5  → BEAR 유지
                //  · 그 외                      → fresh 판정 채택
                MarketRegime previous = lastRegime;
                if (previous == MarketRegime.BULL && overallAvg > -0.5) regime = MarketRegime.BULL;
                else if (previous == MarketRegime.BEAR && overallAvg < 0.5) regime = MarketRegime.BEAR;
                else regime = fresh;
                lastRegime = regime;

                log.info("[종합추천] 시장 국면: {} (전체 섹터 평균 {}%, hysteresis prev={}, fresh={})",
                        regime, String.format("%.2f", overallAvg), previous, fresh);

                // 기존 marketMoodBonus (로그용) — 양봉/INFLOW 만 본 강세 강도
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

        // 3. 종목별 섹터 점수 — AI 테마 + 종목 등락률만 (시장분위기 일괄 가산 제거, P1).
        int scored = 0;
        for (StockScore stock : scoreMap.values()) {
            int ss = 0;
            // AI 테마 점수 (있으면)
            Integer ts = themeScores.get(stock.stockCode);
            if (ts != null) ss += ts;
            // 종목 자체 등락률 보너스
            BigDecimal cr = snapChangeRates.getOrDefault(stock.stockCode, stock.changeRate);
            if (cr != null) {
                double v = cr.doubleValue();
                if (v > 3.0) ss += 4;
                else if (v > 1.5) ss += 3;
                else if (v > 0.5) ss += 2;
                else if (v > 0) ss += 1;
            }
            // phase 37: BULL regime 일 때 모든 종목에 sector +4 일괄 부여 (phase 31b 부분 복원).
            // BULL 검증된 강세장에서는 시장이 종목 전반에 우호적이라는 시그널 자체가 점수에 들어가야
            // 추천 풀이 형성됨. 변별력 일부 손실 vs 추천 풀 회복 trade-off — 운영 데이터(LG디스플레이
            // sector 4점, 75 미달) 기반 조정. BEAR/SIDEWAYS 에서는 일괄 가산 없음(phase 31b 유지).
            // ⚠ phase 38: 이 +4 floor 위에 applyRegimeWeights 가 ×1.20 까지 곱해 섹터가 이중 가산되던
            //   문제로 BULL 섹터 승수는 1.0 으로 내림. floor(여기) 만 유지. 둘 다 키우지 말 것.
            if (regime == MarketRegime.BULL) {
                ss += 4;
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
        return regime;
    }

    // ==================== ⑤ 기술적 (/20) ====================

    private void scoreTechnical(Map<String, StockScore> scoreMap) {
        int calc = 0, fallback = 0, skip = 0;
        // 부족 종목은 모아뒀다가 비동기로 수집 (API 응답 블로킹 방지)
        List<String> needsCollection = new ArrayList<>();

        // N+1 제거: 종목별 fetch → 1쿼리 배치 (100 종목 → 100쿼리에서 1쿼리로)
        // findByStockCodesSince 는 ORDER BY stockCode ASC, tradeDate DESC 보장.
        // 60 거래일 ≈ 약 90 캘린더일이지만 안전 마진으로 120일.
        List<String> allCodes = scoreMap.values().stream()
                .map(s -> s.stockCode)
                .collect(Collectors.toList());
        Map<String, List<StockPriceHistory>> historyMap;
        try {
            List<StockPriceHistory> all = priceHistoryRepository.findByStockCodesSince(
                    allCodes, java.time.LocalDate.now().minusDays(120));
            historyMap = all.stream().collect(Collectors.groupingBy(StockPriceHistory::getStockCode));
        } catch (Exception e) {
            log.warn("[종합추천] priceHistory 일괄 조회 실패: {}", e.getMessage());
            historyMap = java.util.Collections.emptyMap();
        }

        for (StockScore stock : new ArrayList<>(scoreMap.values())) {
            try {
                List<StockPriceHistory> history = historyMap.getOrDefault(stock.stockCode, java.util.Collections.emptyList());
                // 60건 초과는 컷 — 기존 PageRequest.of(0, 60) 동등 의미 (이미 tradeDate DESC 정렬됨)
                if (history.size() > 60) history = history.subList(0, 60);

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

                // ⚠ TechnicalIndicatorService 는 "index 0이 최신" (tradeDate DESC) 가정.
                // ASC 로 넘기면 MA/RSI/골든크로스 가 가장 오래된 N일치로 계산되는 critical 버그.
                List<BigDecimal> prices = history.stream()
                        .sorted(Comparator.comparing(StockPriceHistory::getTradeDate).reversed())
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

                // 5일 누적 등락률 — 과열 판정 + P2 신규 진입 감점에서 사용
                Double fiveDayPct = null;
                if (prices.size() >= 6) {
                    BigDecimal fiveAgo = prices.get(5);
                    if (fiveAgo != null && fiveAgo.signum() > 0) {
                        double pct = prices.get(0).subtract(fiveAgo).doubleValue()
                                / fiveAgo.doubleValue() * 100.0;
                        stock.fiveDayReturn = pct;
                        fiveDayPct = pct;
                    }
                }
                boolean breakout = Boolean.TRUE.equals(ind.getIsBreakout());
                Double rsiVal = rsi != null ? rsi.doubleValue() : null;

                // 과열 페널티 — 추격매수 방지. 산식은 overheatPenalty() 단일 출처(테스트 대상).
                // ts 가 음수로 떨어지면 technical=0 → validCount 에서 빠져 추천 탈락(의도).
                ts -= overheatPenalty(rsiVal, breakout, fiveDayPct);
                if (rsiVal != null && rsiVal >= OVERHEAT_RSI_MIN) {
                    stock.tags.add("⚠RSI" + rsi.intValue() + "과열");
                }
                if (breakout) {
                    stock.tags.add("⚠볼린저상단돌파");
                }
                if (fiveDayPct != null && fiveDayPct >= OVERHEAT_5D_MIN) {
                    stock.tags.add("⚠5일+" + fiveDayPct.intValue() + "%과열");
                }

                stock.technical = Math.min(20, Math.max(0, ts));
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

        // 상위 후보 — TOP10 노출 + 후보군 11~20위까지 검증해 다음 회차 승격 시 정확도 ↑
        List<StockScore> topCandidates = scoreMap.values().stream()
                .filter(s -> countValidCategories(s) >= 4)
                .sorted(Comparator.comparingInt(StockScore::getNormalizedTotal).reversed())
                .limit(20)
                .collect(Collectors.toList());

        if (topCandidates.isEmpty()) return;

        // 상위 10개 시세 — 캐시(메모리+DB)만 사용. MA20(20일 평균) 비교는 5분 캐시로도 충분 정확.
        // KIS 직접 호출하던 기존 방식은 calculate 가 30초+ 걸리는 주범(rate limit 큐잉)이었음.
        List<String> codes = topCandidates.stream().map(s -> s.stockCode).collect(Collectors.toList());
        Map<String, StockPriceDto> priceMap;
        try {
            priceMap = stockPriceService.getStockPricesFromCacheOnly(codes);
        } catch (Exception e) {
            log.warn("[종합추천] 시세 조회 실패 (교차검증 스킵): {}", e.getMessage());
            return; // 시세 조회 실패 시 교차검증 자체를 스킵 (기존 점수 유지)
        }

        // N+1 제거: 종목별 priceHistory fetch → 1쿼리 배치 (10 종목 → 10쿼리에서 1쿼리로)
        Map<String, List<StockPriceHistory>> historyByCode;
        try {
            List<StockPriceHistory> all = priceHistoryRepository.findByStockCodesSince(
                    codes, java.time.LocalDate.now().minusDays(50));
            historyByCode = all.stream().collect(Collectors.groupingBy(StockPriceHistory::getStockCode));
        } catch (Exception e) {
            log.warn("[종합추천] priceHistory 일괄 조회 실패 (교차검증 스킵): {}", e.getMessage());
            return;
        }

        for (StockScore stock : topCandidates) {
            try {
                StockPriceDto livePrice = priceMap.get(stock.stockCode);

                // 1. MA20 하회 체크 — 실시간 현재가 vs priceHistory MA20
                List<StockPriceHistory> history = historyByCode.getOrDefault(stock.stockCode, java.util.Collections.emptyList());
                if (history.size() > 25) history = history.subList(0, 25);
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
                        // ⚠ TechnicalIndicatorService 는 "index 0이 최신" (DESC) 가정. 위 prices 는 ASC 라
                        //   reverse 새 리스트로 넘겨야 골든크로스/RSI/MA가 가장 최근 N일 기준으로 계산됨.
                        List<BigDecimal> pricesDesc = new ArrayList<>(prices);
                        java.util.Collections.reverse(pricesDesc);
                        TechnicalIndicatorsDto freshIndicators = technicalIndicatorService.calculate(pricesDesc);
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

    // ==================== ⑦ 신규 진입 감점 (phase31 P2) ====================

    /**
     * 어제 스냅샷 밖에서 갑자기 등장 + 5일 누적 +15% 이상 → 추격 패턴으로 보고 감점.
     * <p>의도: tie-break delta desc(P0-3)는 "어제 60 → 오늘 78" 같은 <b>추천 풀 안에서</b>의 가속을
     * 우대하는 반면, P2 는 "어제 추천 풀 밖(점수 부족 or 데이터 없음)에서 오늘 갑자기 진입한 종목이
     * 동시에 5일 +15% 이상 올랐다면" 추격 매수 신호로 간주해 감점.
     * <p>페널티는 technical 카테고리에서 -5 (음수 클램프). technical=0 이 되면 validCount 에서
     * 빠져 자연 탈락. P0-1 의 5일 +20%+ 페널티와는 중첩 가능 (의도 — 신규 + 더 가속이면 더 큰 페널티).
     * <p>prevScoreMap 비어있는 콜드스타트는 스킵 (모든 종목이 "신규" 로 잘못 분류되는 거 방지).
     * <p><b>phase 36</b>: BULL 이면 5일 +15% 가 정상 추세에서도 흔해(운영 데이터 2026-05-14: 46건
     * 무차별 페널티 → STRONG_BUY 0건) 완전 비활성했었음.
     * <p><b>phase 38</b>: 완전 비활성 대신 <b>BULL 임계를 +25%(극단 급등)로 상향</b> — 소수의 진짜 추격성
     * 신규만 감점하고 정상 추세 종목 풀은 보존(완전 비활성 시 BULL 에서 추격 무방비였던 점 보완).
     */
    private void applyNewEntryPenalty(Map<String, StockScore> scoreMap,
                                       Map<String, Integer> prevScoreMap,
                                       MarketRegime regime) {
        if (prevScoreMap.isEmpty()) return;
        final double threshold = newEntryPenaltyThreshold(regime);
        int penalized = 0;
        for (StockScore stock : scoreMap.values()) {
            if (prevScoreMap.containsKey(stock.stockCode)) continue;     // 어제 추천 풀 안
            if (stock.fiveDayReturn < threshold) continue;                // 5일 누적 미달
            if (stock.technical <= 0) continue;                            // 이미 0 이면 의미 없음
            stock.technical = Math.max(0, stock.technical - 5);
            stock.tags.add("⚠신규+5일+" + (int) stock.fiveDayReturn + "%");
            penalized++;
        }
        if (penalized > 0) {
            log.info("[종합추천] 신규 진입 감점: {}건 (어제 스냅샷 밖 + 5일 +{}%↑, regime={})",
                    penalized, (int) threshold, regime);
        }
    }

    /** 신규 진입 감점 5일 누적 임계 — BULL 은 25%(극단만), 그 외 15%. P38 테스트 대상. */
    static double newEntryPenaltyThreshold(MarketRegime regime) {
        return regime == MarketRegime.BULL ? 25.0 : 15.0;
    }

    // ==================== ⑧ 시장 국면 적응형 가중치 (phase 34) ====================

    /**
     * 시장 국면에 따라 카테고리 점수 multiplier 적용.
     *
     * <p>의도: 강세장은 모멘텀 (수급/기술/섹터) 비중을 높이고, 약세장은 펀더멘털 (실적/가치) 우선.
     * SIDEWAYS 는 기본이지만 섹터만 0.90 배 — phase 31b 의 "섹터 1분 스냅샷 → 30분 추천" 시간
     * 척도 불일치를 부분 보정 (외부 피드백 반영).
     *
     * <p><b>phase 36</b>: BULL multiplier 폭 ±0.20 → ±0.10 으로 좁힘. 운영 데이터(2026-05-14
     * BULL 강세장)에서 max 71 / STRONG_BUY 0건 부작용 확인 후 — earnings 0.90→0.95 (강한 실적
     * 종목 깎는 거 완화), sector 0.90→1.00 (BULL 은 시장이 이미 강한 거 검증된 상태라 보정 불필요).
     * BEAR/SIDEWAYS 는 데이터 없어 그대로 유지.
     *
     * <p><b>phase 37</b>: phase 36 후에도 max 67/STRONG_BUY 0건. LG디스플레이 sector=4 가 병목.
     * BULL sectorMomentum 1.00 → 1.20 으로 추가 우대 (scoreSectorMomentum 의 BULL +4 boost 와 시너지).
     *
     * <p>multiplier 표 (phase 37):
     * <pre>
     *   regime       earnings   supplyDemand  technical   sectorMomentum
     *   BULL         × 0.95     × 1.10        × 1.05      × 1.20      ← phase 37 sector 강화
     *   BEAR         × 1.20     × 0.85        × 0.90      × 0.80
     *   SIDEWAYS     × 1.00     × 1.00        × 1.00      × 0.90      ← 섹터만 시간 척도 보정
     * </pre>
     *
     * <p>각 카테고리 점수는 카테고리 만점(20) 안에서 clamp — 만점 80 / 정규화 100 구조 그대로 보존.
     * 결과적으로 multiplier > 1 효과는 만점 종목엔 작고, 중간 점수(10~15) 종목에 가장 크게 나타남.
     *
     * <p>비활성화: multiplier 를 1.0/1.0/1.0/1.0 으로 모두 바꾸면 즉시 disable.
     */
    private void applyMarketRegimeWeighting(Map<String, StockScore> scoreMap, MarketRegime regime) {
        // SIDEWAYS + 섹터만 0.9 라 SIDEWAYS 도 변화 있음. 그래도 BULL/BEAR 만 명시 태그.
        String tag = switch (regime) {
            case BULL -> "regime:BULL";
            case BEAR -> "regime:BEAR";
            default -> null;
        };
        for (StockScore s : scoreMap.values()) {
            int[] w = applyRegimeWeights(s.earnings, s.supplyDemand, s.technical, s.sectorMomentum, regime);
            s.earnings = w[0];
            s.supplyDemand = w[1];
            s.technical = w[2];
            s.sectorMomentum = w[3];
            if (tag != null) s.tags.add(tag);
        }
        log.info("[종합추천] regime weighting 적용: {}", regime);
    }

    private static int clampCategory(int v) {
        return Math.max(0, Math.min(20, v));
    }

    // ==================== N/A & Util ====================

    private int countValidCategories(StockScore s) {
        // 종합 추천은 "현재 매수 신호" 트랙 — 4 카테고리 (실적·수급·기술·섹터).
        // AI전략 / 저평가(가치) 는 별도 트랙으로 분리:
        //   - AI전략: 후보 발굴/태그 용도로만 유지
        //   - 저평가: /api/recommendation/value-top10 별도 endpoint 로 노출
        return validCount(s.earnings, s.supplyDemand, s.technical, s.sectorMomentum);
    }

    /** 유효 카테고리 수(>0인 4개 중 몇 개). P1-5 테스트 대상 — validCount≥3 채택 컷의 분모. */
    static int validCount(int earnings, int supplyDemand, int technical, int sectorMomentum) {
        int c = 0;
        if (earnings > 0) c++;
        if (supplyDemand > 0) c++;
        if (technical > 0) c++;
        if (sectorMomentum > 0) c++;
        return c;
    }

    /**
     * STRONG+VALUE 가산 — total≥75 AND valueStability≥12 이면 +2(상한 100, Math.min). P1-5 테스트 대상.
     * ※ 게이트가 75(이미 STRONG_BUY)라 <b>등급은 절대 안 바뀐다</b> — BUY(55~74)를 STRONG_BUY로 승격시키지 않는다.
     *   목적은 "STRONG_BUY ∩ 강한 가치" 종목을 정렬 상위로 올리고 STRONG+VALUE 태그를 부여하는 것(phase 34).
     */
    static int strongValueBonus(int total, int valueStability) {
        if (total >= STRONG_BUY_THRESHOLD && valueStability >= STRONG_VALUE_THRESHOLD) {
            return Math.min(100, total + STRONG_VALUE_BONUS);
        }
        return total;
    }

    // 과열 페널티 임계 — 태그 표시 임계와 동일(가장 낮은 차감 구간). overheatPenalty() 와 동기.
    static final double OVERHEAT_RSI_MIN = 70.0;
    static final double OVERHEAT_5D_MIN = 15.0;

    /**
     * 과열(추격매수) 페널티 — technical 점수에서 차감할 점수(양수). P1-5 패턴 테스트 대상.
     *
     * <p>RSI·볼린저 상단 돌파·5일 누적 등락률을 단일 출처로 합산. ts 에서 빼며, ts 가 음수면
     * technical=0 → validCount 에서 빠져 추천 탈락(의도). BULL 강세장에서도 적용된다(섹터 가산과 별개).
     *
     * <p><b>phase 38 임계 강화</b>: 기존 RSI≥75 -5 / 5일≥20% -5 단일 임계는 임계 바로 아래
     * (RSI 72·5일 18%) 과열주를 무페널티로 통과시켜 발굴 상위에 노출하는 문제가 있었다.
     * 단계별로 바꿔 임계 바로 아래 구간도 잡고 극과열은 더 크게 차감한다(기존 임계의 차감폭은 유지):
     * RSI 70/75/80 → 3/5/8, 5일 15/20/30% → 3/5/8.
     *
     * @param rsi RSI14 (null 허용)
     * @param isBreakout 볼린저 밴드 상단 돌파 여부
     * @param fiveDayReturn 5거래일 누적 등락률 % (null 허용)
     * @return 차감 점수(0 이상)
     */
    static int overheatPenalty(Double rsi, boolean isBreakout, Double fiveDayReturn) {
        int p = 0;
        if (rsi != null) {
            if (rsi >= 80.0) p += 8;
            else if (rsi >= 75.0) p += 5;
            else if (rsi >= 70.0) p += 3;
        }
        if (isBreakout) p += 3;
        if (fiveDayReturn != null) {
            if (fiveDayReturn >= 30.0) p += 8;
            else if (fiveDayReturn >= 20.0) p += 5;
            else if (fiveDayReturn >= 15.0) p += 3;
        }
        return p;
    }

    /**
     * 발굴 TOP10 정렬 comparator. 테스트 대상.
     *
     * <p>tie-break 우선순위:
     * <ol>
     *   <li>normalized total desc</li>
     *   <li>delta(오늘 - 어제) desc — 추천 풀 안에서 막 가속한 종목 우선</li>
     *   <li>changeRate <b>asc</b> — 점수·delta 동률이면 <b>덜 오른 종목</b> 우선(추격 인상 완화).
     *       기존엔 desc(많이 오른 종목 우선)라 "이미 많이 올랐다"가 상위 노출 요인이었음(phase 38 약화).</li>
     * </ol>
     *
     * <p>⚠ <b>차트 타이밍 승격 시 점검(P2-12, 작업5)</b>: 3차 tie-break(changeRate asc, "덜 오른 종목")는
     * 차트 타이밍 눌림목 신호(정배열 안에서 "이미 빠진 자리" 진입)와 <b>같은 방향</b>이다. 현재는 차트 타이밍이
     * 발굴/매수후보 momentum 랭킹과 <b>완전 분리(unverified 베타)</b>라 무관하지만, P2-12 검증 통과로 타이밍을
     * 매수후보 랭킹에 <b>편입/승격</b>하면 이 comparator 와 <b>이중 작용</b>(덜 오른 종목 과대 가중) 위험이 있다.
     * 승격 PR 에서 반드시 점검할 것 — 여기에 타이밍 점수를 섞지 말 것.
     */
    static Comparator<StockScore> recommendationComparator(Map<String, Integer> prevScoreMap) {
        return Comparator.comparingInt(StockScore::getNormalizedTotal).reversed()
                .thenComparing((StockScore s) -> {
                    Integer prev = prevScoreMap.get(s.stockCode);
                    return s.getNormalizedTotal() - (prev != null ? prev : 0);
                }, Comparator.reverseOrder())
                // changeRate asc — 점수·delta 동률이면 덜 오른 종목 우선(추격 인상 완화, phase 38).
                .thenComparing(s -> s.changeRate != null ? s.changeRate.doubleValue() : 0.0);
    }

    /**
     * 시장 국면별 카테고리 가중 + clamp[0,20]. P1-5 테스트 대상 — BULL/BEAR 승수 반영 확인.
     *
     * <p><b>phase 38</b>: BULL 섹터 승수 1.20 → 1.0. BULL 에서는 scoreSectorMomentum 이 이미
     * 전 종목에 +4 floor 를 더하는데, 그 위에 ×1.20 을 또 곱해 섹터가 <b>이중 가산</b>되던 문제
     * (오른 종목일수록 섹터 점수가 부풀어 발굴 상위 노출)를 해소. floor(+4)는 추천 풀 안정용으로
     * 유지하고, 증폭(×1.20)만 제거 — "둘 중 하나만" 남기는 절충. BEAR/SIDEWAYS 는 불변.
     *
     * @return [earnings, supplyDemand, technical, sectorMomentum] 가중·clamp 결과
     */
    static int[] applyRegimeWeights(int earnings, int supplyDemand, int technical, int sectorMomentum,
                                    MarketRegime regime) {
        double wE, wSD, wTC, wSC;
        switch (regime) {
            case BULL -> { wE = 0.95; wSD = 1.10; wTC = 1.05; wSC = 1.00; }
            case BEAR -> { wE = 1.20; wSD = 0.85; wTC = 0.90; wSC = 0.80; }
            default ->   { wE = 1.00; wSD = 1.00; wTC = 1.00; wSC = 0.90; }
        }
        return new int[] {
                clampCategory((int) Math.round(earnings * wE)),
                clampCategory((int) Math.round(supplyDemand * wSD)),
                clampCategory((int) Math.round(technical * wTC)),
                clampCategory((int) Math.round(sectorMomentum * wSC)),
        };
    }

    private List<RecommendationDto> loadFromDb() {
        try {
            List<RecommendationSnapshot> snapshots = snapshotRepository.findLatestSnapshot();
            if (snapshots.isEmpty()) return Collections.emptyList();
            List<RecommendationDto> result = snapshots.stream().map(s -> {
                // AI전략은 valid 카운트에서 제외 (산식에서 빠짐)
                int vc = 0;
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

    /**
     * 추천 점수 산출에 사용되는 카테고리 수 — 시그널 추가/삭제 시 이 상수만 조정.
     * 현재: earnings + supplyDemand + technical + sectorMomentum (가치/AI전략 분리).
     */
    private static final int TOTAL_CATEGORIES = 4;

    /**
     * 유효 카테고리 수별 점수 정규화 — 0~100 스케일링 + 커버리지 기반 cap.
     *
     * 스케일링: scaled = raw × 100 / (TOTAL_CATEGORIES × 20)
     *   - 4 카테고리 × 카테고리당 최대 20점 = raw 만점 80 → scaled 100
     *   - 이전 버그(phase 14 이전): 4 valid 일 때 raw 그대로 반환하여 만점이 80 → 임계값
     *     75 가 "거의 만점" 의미였음. v7 (5→4 카테고리) 전환 시 임계값 미조정 잔존.
     *   - 수정 후: 4 valid full = 100, 임계값 75/55 가 의도된 의미 회복.
     *
     * cap (validCount < TOTAL_CATEGORIES 만 적용 — 단, 아래 ⚠ 참조):
     *   25 + 75 × (validCount / TOTAL_CATEGORIES) → vc=1:43 / vc=2:62 / vc=3:81
     *
     * ⚠ <b>고정 분모(80) + 카테고리 clamp[0,20]</b> 하에서는 raw ≤ vc×20 → scaled ≤ vc×25 가
     *   항상 cap(=25+18.75×vc)보다 작다(vc&lt;4). 즉 <b>cap 은 현재 산식에선 절대 발동 안 함(죽은 코드)</b>.
     *   실효 최댓값은 고정 분모가 결정: vc=1→25 / vc=2→50 / <b>vc=3→75</b> / vc=4→100.
     *   따라서 결측 1개(vc=3) 종목은 최대 <b>75점(=STRONG_BUY 임계)</b> — 남은 3카테고리가 전부 만점일 때만.
     *   이는 의도된 "커버리지 페널티"(불완전 데이터는 최상위 등급 불가). dynamic 분모로 바꾸지 않고 현행 유지.
     *
     * 시그널 추가/삭제 시 TOTAL_CATEGORIES 만 바꾸면 자동 재계산.
     */
    static int normalizeScore(int raw, int validCount) { // P1-4/5: 테스트 가능하도록 package-private
        if (validCount <= 0) return 0;
        int rawCap = TOTAL_CATEGORIES * 20;
        int scaled = raw * 100 / rawCap;
        if (validCount >= TOTAL_CATEGORIES) return Math.min(100, scaled);
        // 고정 분모(80) 하에서는 항상 scaled<cap 이라 cap 은 미발동(죽은 코드).
        // 향후 dynamic 분모(validCount×20) 전환 가능성 대비해 삭제하지 않고 보존. (Javadoc ⚠ 참조)
        int cap = 25 + (75 * validCount / TOTAL_CATEGORIES);
        return Math.min(cap, scaled);
    }

    private RecommendationDto toDto(StockScore s) {
        int vc = countValidCategories(s);
        // 4 카테고리 합산 — 가치/AI전략 분리.
        int raw = s.earnings + s.supplyDemand + s.technical + s.sectorMomentum;
        int total = normalizeScore(raw, vc);
        // phase 34: STRONG_BUY + 강한 가치 교집합 가산 (정렬용 getNormalizedTotal 과 일관성 유지)
        if (total >= STRONG_BUY_THRESHOLD && s.valueStability >= STRONG_VALUE_THRESHOLD) {
            s.tags.add("STRONG+VALUE");
        }
        total = strongValueBonus(total, s.valueStability);

        return RecommendationDto.builder()
                .stockCode(s.stockCode).stockName(s.stockName)
                .totalScore(total)
                .aiStrategy(s.aiStrategy > 0 ? s.aiStrategy : NA)
                .earnings(s.earnings > 0 ? s.earnings : NA)
                .supplyDemand(s.supplyDemand > 0 ? s.supplyDemand : NA)
                .technical(s.technical > 0 ? s.technical : NA)
                .sectorMomentum(s.sectorMomentum > 0 ? s.sectorMomentum : NA)
                // valueStability 만 -1(데이터 자체 없음) 과 0(데이터 있음·점수 0) 구분.
                // -1 → NA, 0 → "0/20" 표기 (가치주 기준 미달).
                .valueStability(s.valueStability >= 0 ? s.valueStability : NA)
                // growth 도 valueStability 와 동일 NA(-1) 시맨틱.
                .growth(s.growth >= 0 ? s.growth : NA)
                .validCount(vc)
                .tags(new ArrayList<>(s.tags))
                .changeRate(s.changeRate).build();
    }

    // ==================== Inner Classes ====================

    static class StockScore {  // package-private: recommendationComparator 테스트 접근
        String stockCode, stockName;
        int aiStrategy = 0, earnings = 0, supplyDemand = 0, technical = 0, sectorMomentum = 0;
        // valueStability — 다른 카테고리와 달리 "데이터 자체 없음" 과 "데이터 있으나 점수 0" 구분.
        // -1 = financial_data row 없음(NA), 0+ = row 있음 (점수 0이면 가치주 기준 미달, UI에 "0/20" 표기).
        int valueStability = -1;
        // growth(성장성) — valueStability 와 동일한 NA(-1) 시맨틱. 매출·이익 성장률 + PEG 기반.
        // 가치(저평가)와 분리된 별도 LONG 트랙: 시클리컬/성장주가 밸류 지표로 저평가되는 한계 보완.
        // valueStability 처럼 totalScore 산식엔 미포함 (별도 표시 factor).
        int growth = -1;
        Set<String> tags = new LinkedHashSet<>();
        BigDecimal changeRate;
        // phase31 P2 — 5거래일 누적 등락률 (%). scoreTechnical 에서 채움. 신규 진입 감점에 사용.
        double fiveDayReturn = 0.0;
        StockScore(String code, String name) { stockCode = code; stockName = name; }

        int getNormalizedTotal() {
            // 4 카테고리 합산 — AI전략 / 저평가 분리 (별도 트랙).
            int v = 0, sum = 0;
            if (earnings > 0) { v++; sum += earnings; }
            if (supplyDemand > 0) { v++; sum += supplyDemand; }
            if (technical > 0) { v++; sum += technical; }
            if (sectorMomentum > 0) { v++; sum += sectorMomentum; }
            int total = normalizeScore(sum, v);
            // phase 34: STRONG_BUY + 강한 가치 교집합 가산 (toDto 와 일관성)
            if (total >= STRONG_BUY_THRESHOLD && valueStability >= STRONG_VALUE_THRESHOLD) {
                total = Math.min(100, total + STRONG_VALUE_BONUS);
            }
            return total;
        }
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecommendationDto {
        private String stockCode, stockName;
        private int totalScore, aiStrategy, earnings, supplyDemand, technical, sectorMomentum, valueStability, growth, validCount;
        private List<String> tags;
        private BigDecimal changeRate;
        private BigDecimal currentPrice;
        // 저평가 TOP 10 전용 — 4 항목 점수 분해 (UI 막대 그래프용). 종합 추천은 모두 null.
        private Integer valuePbrScore;             // 0~8
        private Integer valueRoeCombinedScore;     // 0~5
        private Integer valueDebtScore;            // 0~4
        private Integer valueProfitEquityScore;    // 0~3
        // 성장주 TOP 10 전용 — 3 항목 점수 분해 (UI 막대 그래프용). 그 외 트랙은 null.
        private Integer growthRevScore;            // 0~7 (매출성장률)
        private Integer growthProfitScore;         // 0~8 (이익성장률)
        private Integer growthPegScore;            // 0~5 (PEG)
        // 낙폭과대 반등 TOP 10 전용 — 3 항목 점수 분해. 그 외 트랙은 null.
        private Integer oversoldRsiScore;          // 0~8 (RSI 과매도)
        private Integer oversoldDropScore;         // 0~7 (MA20 이격도/낙폭)
        private Integer oversoldReboundScore;      // 0~5 (반등 조짐: 양봉+거래량)
    }

    @Getter @AllArgsConstructor
    public static class Top5Response {
        private final List<RecommendationDto> items;
        private final String dataTime;
        private final boolean realtime;
        private final Map<String, Integer> delta;
    }
}
