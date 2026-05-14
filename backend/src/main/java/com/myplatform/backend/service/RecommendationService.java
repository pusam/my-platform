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

    // 저평가 TOP 10 별도 캐시 — 가치 점수는 분기 단위로 거의 안 변하므로 30분 캐시 충분.
    private volatile List<RecommendationDto> cachedValueTop10 = null;
    private volatile LocalDateTime valueCacheTime = null;

    // 백그라운드 calculate 중복 방지
    private final java.util.concurrent.atomic.AtomicBoolean calculating
            = new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

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
     * 신규 강력매수(75+) 등장 알림 (평일 09:00 — 장 시작 직후)
     * - 어제 마감 스냅샷에 없고 오늘 75+ 진입한 종목만 텔레그램·앱 알림
     * - 일 1회만 (lastAlertDate 체크)
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 0 9 * * MON-FRI", zone = "Asia/Seoul")
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
                        outcomeService.record(signalType, dto.getStockCode(), dto.getStockName(),
                                dto.getTotalScore(), dto.getCurrentPrice());
                    } catch (Exception ignore) { /* 적중률 추적은 best-effort */ }
                }
            }

            log.info("[종합추천] 스냅샷 {}건 저장 ({})", result.size(), snapTime.format(TIME_FMT));
        } catch (Exception e) {
            log.error("[종합추천] 스냅샷 실패: {}", e.getMessage());
        }
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
        // 섹터: AI 스냅샷 + 시장분위기 (모든 scoreMap 종목에 부여)
        scoreSectorMomentum(scoreMap);
        long scMs = System.currentTimeMillis() - t0; t0 = System.currentTimeMillis();
        // 기술: 마지막 (모든 종목 수집 후)
        scoreTechnical(scoreMap);
        long tcMs = System.currentTimeMillis() - t0; t0 = System.currentTimeMillis();
        // 가치/안정성: PBR·ROE·부채비율 (DB 만 — 빠름)
        scoreValueStability(scoreMap);
        long vsMs = System.currentTimeMillis() - t0; t0 = System.currentTimeMillis();
        // 리스크 공시 페널티: DART API 호출 — 종목당 2~3초 소요 → 상위 후보(30개) 만 검사.
        applyRiskPenalty(scoreMap);
        long rkMs = System.currentTimeMillis() - t0; t0 = System.currentTimeMillis();
        // 실시간 교차검증: MA20 하회/수급 괴리 감지 → 점수 보정
        applyRealtimeChecks(scoreMap);
        long rtMs = System.currentTimeMillis() - t0;
        log.info("[종합추천] 단계별 소요 - AI:{}ms 실적:{}ms 수급:{}ms 섹터:{}ms 기술:{}ms 가치:{}ms 리스크:{}ms 실시간:{}ms (합 {}ms)",
                aiMs, erMs, sdMs, scMs, tcMs, vsMs, rkMs, rtMs,
                aiMs + erMs + sdMs + scMs + tcMs + vsMs + rkMs + rtMs);

        // 디버그 로그
        for (StockScore s : scoreMap.values()) {
            log.debug("[종합추천] {} — AI:{} 실적:{} 수급:{} 기술:{} 섹터:{} 가치:{} (유효 {}개)",
                    s.stockName, s.aiStrategy, s.earnings, s.supplyDemand, s.technical, s.sectorMomentum,
                    s.valueStability, countValidCategories(s));
        }
        log.info("[종합추천] scoreMap {}종목 (AI시드 {}개)", scoreMap.size(), aiCount);

        List<RecommendationDto> results = scoreMap.values().stream()
                .filter(s -> countValidCategories(s) >= 3)  // 5카테고리 중 최소 3개 valid (60% 커버리지)
                .filter(s -> normalizeScore(
                        // AI전략 카테고리는 totalScore 산식에서 제외 — 후보 발굴/태그 용도로만 사용
                        s.earnings + s.supplyDemand + s.technical + s.sectorMomentum + s.valueStability,
                        countValidCategories(s)) >= 55) // 관망 컷 — 60→55 완화 (TOP10 자리 채우기, 데이터 부족시 5건만 노출되던 문제)
                // tie-break 우선순위:
                //   1) normalized total desc
                //   2) delta(오늘 - 어제) desc — 막 올라온 종목 우선 (추격매수 방지)
                //   3) changeRate desc — 최후 보루
                .sorted(Comparator.comparingInt(StockScore::getNormalizedTotal).reversed()
                        .thenComparing((StockScore s) -> {
                            Integer prev = prevScoreMap.get(s.stockCode);
                            return s.getNormalizedTotal() - (prev != null ? prev : 0);
                        }, Comparator.reverseOrder())
                        .thenComparing(s -> s.changeRate != null ? s.changeRate.doubleValue() : 0.0,
                                Comparator.reverseOrder()))
                .limit(10)
                .map(this::toDto)
                .toList();

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

                // 각 필드별 first non-null — 단일 row 의 결손 컬럼을 다른 최근 row 로 보완
                BigDecimal pbr = firstNonNull(recent, StockFinancialData::getPbr);
                BigDecimal roe = firstNonNull(recent, StockFinancialData::getRoe);
                BigDecimal debtRatio = firstNonNull(recent, StockFinancialData::getDebtRatio);
                BigDecimal opProfit = firstNonNull(recent, StockFinancialData::getOperatingProfit);
                BigDecimal equity = firstNonNull(recent, StockFinancialData::getTotalEquity);

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

    private static BigDecimal firstNonNull(List<StockFinancialData> rows,
                                            java.util.function.Function<StockFinancialData, BigDecimal> getter) {
        for (StockFinancialData r : rows) {
            BigDecimal v = getter.apply(r);
            if (v != null) return v;
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

                // 과열 페널티 — 추격매수 방지. RSI 75+, 볼린저 상단 돌파, 5일 누적 +20% 모두 점수 차감.
                // ts 가 음수로 떨어질 수 있고 그 경우 technical=0 → validCount 에서 빠져 추천 탈락(의도).
                if (rsi != null && rsi.doubleValue() >= 75) {
                    ts -= 5;
                    stock.tags.add("⚠RSI" + rsi.intValue() + "과열");
                }
                if (Boolean.TRUE.equals(ind.getIsBreakout())) {
                    ts -= 3;
                    stock.tags.add("⚠볼린저상단돌파");
                }
                if (prices.size() >= 6) {
                    BigDecimal fiveAgo = prices.get(5);
                    if (fiveAgo != null && fiveAgo.signum() > 0) {
                        double pct = prices.get(0).subtract(fiveAgo).doubleValue()
                                / fiveAgo.doubleValue() * 100.0;
                        if (pct >= 20.0) {
                            ts -= 5;
                            stock.tags.add("⚠5일+" + (int) pct + "%과열");
                        }
                    }
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

    // ==================== N/A & Util ====================

    private int countValidCategories(StockScore s) {
        // 종합 추천은 "현재 매수 신호" 트랙 — 4 카테고리 (실적·수급·기술·섹터).
        // AI전략 / 저평가(가치) 는 별도 트랙으로 분리:
        //   - AI전략: 후보 발굴/태그 용도로만 유지
        //   - 저평가: /api/recommendation/value-top10 별도 endpoint 로 노출
        int c = 0;
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
     * cap (validCount < TOTAL_CATEGORIES 만 적용):
     *   25 + 75 × (validCount / TOTAL_CATEGORIES)
     *   - 1/4 → 43, 2/4 → 62, 3/4 → 81, 4/4 → 100 (cap 미적용 — scaled 그대로)
     *
     * 시그널 추가/삭제 시 TOTAL_CATEGORIES 만 바꾸면 자동 재계산.
     */
    private static int normalizeScore(int raw, int validCount) {
        if (validCount <= 0) return 0;
        int rawCap = TOTAL_CATEGORIES * 20;
        int scaled = raw * 100 / rawCap;
        if (validCount >= TOTAL_CATEGORIES) return Math.min(100, scaled);
        int cap = 25 + (75 * validCount / TOTAL_CATEGORIES);
        return Math.min(cap, scaled);
    }

    private RecommendationDto toDto(StockScore s) {
        int vc = countValidCategories(s);
        // 4 카테고리 합산 — 가치/AI전략 분리.
        int raw = s.earnings + s.supplyDemand + s.technical + s.sectorMomentum;
        int total = normalizeScore(raw, vc);

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
                .validCount(vc)
                .tags(new ArrayList<>(s.tags))
                .changeRate(s.changeRate).build();
    }

    // ==================== Inner Classes ====================

    private static class StockScore {
        String stockCode, stockName;
        int aiStrategy = 0, earnings = 0, supplyDemand = 0, technical = 0, sectorMomentum = 0;
        // valueStability — 다른 카테고리와 달리 "데이터 자체 없음" 과 "데이터 있으나 점수 0" 구분.
        // -1 = financial_data row 없음(NA), 0+ = row 있음 (점수 0이면 가치주 기준 미달, UI에 "0/20" 표기).
        int valueStability = -1;
        Set<String> tags = new LinkedHashSet<>();
        BigDecimal changeRate;
        StockScore(String code, String name) { stockCode = code; stockName = name; }

        int getNormalizedTotal() {
            // 4 카테고리 합산 — AI전략 / 저평가 분리 (별도 트랙).
            int v = 0, sum = 0;
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
        private int totalScore, aiStrategy, earnings, supplyDemand, technical, sectorMomentum, valueStability, validCount;
        private List<String> tags;
        private BigDecimal changeRate;
        private BigDecimal currentPrice;
        // 저평가 TOP 10 전용 — 4 항목 점수 분해 (UI 막대 그래프용). 종합 추천은 모두 null.
        private Integer valuePbrScore;             // 0~8
        private Integer valueRoeCombinedScore;     // 0~5
        private Integer valueDebtScore;            // 0~4
        private Integer valueProfitEquityScore;    // 0~3
    }

    @Getter @AllArgsConstructor
    public static class Top5Response {
        private final List<RecommendationDto> items;
        private final String dataTime;
        private final boolean realtime;
        private final Map<String, Integer> delta;
    }
}
