package com.myplatform.backend.service;

import com.myplatform.backend.dto.AiStrategySnapshotDto;
import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.EarningSurpriseDto;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.AiStrategySnapshot;
import com.myplatform.backend.entity.AiStrategySnapshot.StrategyType;
import com.myplatform.backend.repository.AiStrategySnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 투자 전략 스냅샷 서비스
 *
 * [스케줄 전략 - Dual Track]
 * Track A (SCALPING): 2분 간격
 *   - 장중 09:05 ~ 15:20
 *   - 거래량/모멘텀은 빠르게 변화하므로 짧은 주기
 *
 * Track B (SWING, TURNAROUND, VALUE): 30분 간격
 *   - 장중 09:00 ~ 15:30
 *   - 재무 데이터는 변화 주기가 길어 30분 주기
 *
 * [핵심 원칙]
 * - 사용자 요청 시 API 호출 X, DB 조회만
 * - 100번 새로고침 = 0번 외부 API 호출
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class AiStrategySnapshotService {

    private final AiStrategySnapshotRepository snapshotRepository;
    private final QuantScreenerService quantScreenerService;
    private final StockPriceService stockPriceService;
    private final GeminiService geminiService;
    private final InvestorTradeService investorTradeService;
    private final EarningSurpriseService earningSurpriseService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // AI 스코어링 후보 수집 수 (Gemini 평가용)
    private static final int CANDIDATE_LIMIT = 10;
    // 최종 저장 수 (블렌딩 후 TOP N)
    private static final int SNAPSHOT_LIMIT = 5;
    // 최소 주가 (초저가 소형주 제외: 오리엔트정공 700원, 소프트센 400원 등)
    private static final BigDecimal MIN_STOCK_PRICE = new BigDecimal("1000");
    // 최소 시가총액 (억원, 네이버 폴백 시 소형주 필터)
    private static final BigDecimal MIN_MARKET_CAP = new BigDecimal("3000");

    // 네이버 모바일 API - 시가총액 상위 (JSON, primary) — 거래량 API(/stocks/volume/) 404 폐기 → 시총 상위로 변경
    private static final String NAVER_VOLUME_API = "https://m.stock.naver.com/api/stocks/marketValue/%s?page=1&pageSize=%d";
    // 네이버 금융 거래상위 HTML (fallback)
    private static final String NAVER_VOLUME_HTML_URL = "https://finance.naver.com/sise/sise_quant.naver";
    private static final String CRAWL_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String NAVER_REFERER = "https://m.stock.naver.com/";

    // ========== 서버 시작 시 Warm-up ==========

    /**
     * 서버 시작 시 모든 전략 스냅샷 초기화 (Warm-up)
     * - @Async로 메인 스레드 블로킹 방지
     * - 30초 지연 후 시작 (다른 서비스 초기화 대기)
     * - 각 전략별 순차적으로 수집 (API Rate Limit 고려)
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmUpSnapshots() {
        try {
            // 다른 서비스 초기화 완료 대기 (SectorTrading, InvestorTrade 등)
            Thread.sleep(30000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        log.info("[Warm-up] 서버 시작 - 모든 전략 스냅샷 초기 수집 시작");

        int successCount = 0;
        int failCount = 0;

        for (StrategyType type : StrategyType.values()) {
            try {
                log.info("[Warm-up] {} 전략 스냅샷 수집 중...", type.name());
                collectAndSaveSnapshot(type);

                List<AiStrategySnapshot> saved = snapshotRepository.findLatestByStrategyType(type);
                log.info("[Warm-up] {} Strategy initialized: {} stocks saved.", type.name(), saved.size());
                successCount++;

                // API 호출 간격 (Rate Limit 방지)
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Warm-up] 중단됨");
                return;
            } catch (Exception e) {
                log.error("[Warm-up] {} 전략 스냅샷 수집 실패: {}", type.name(), e.getMessage());
                failCount++;
            }
        }

        log.info("[Warm-up] 초기화 완료 - 성공: {}, 실패: {}", successCount, failCount);
    }

    // ========== 스케줄러 (Dual Track) ==========

    /**
     * Track A: 스캘핑 전략 스냅샷 (30분 간격, Gemini Rate Limit 방지)
     * - 기존 5분 → 30분 (Gemini 호출이 Rate Limit의 주범)
     * - 장중 09:05 ~ 15:20
     */
    @Scheduled(cron = "0 0,30 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectScalpingSnapshot() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 5)) || now.isAfter(LocalTime.of(15, 20))) {
            return;
        }

        try {
            collectAndSaveSnapshot(StrategyType.SCALPING);
            log.info("[Scheduler] SCALPING Strategy updated.");
        } catch (Exception e) {
            log.error("[Scheduler] SCALPING update failed: {}", e.getMessage());
        }
    }

    /**
     * Track B: 중장기 전략 스냅샷 (60분 간격, Rate Limit 완화)
     * - 기존 30분 → 60분으로 변경 (Gemini Rate Limit 방지)
     * - 장중 09:00 ~ 15:30
     * - 평일만 실행
     * - SWING, TURNAROUND, VALUE 전략을 로테이션 수집 (매 시간 1개씩)
     */
    @Scheduled(cron = "0 0 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectLongTermSnapshots() {
        LocalTime now = LocalTime.now();
        if (now.isAfter(LocalTime.of(15, 30))) return;

        int hour = now.getHour();

        // 09:00 첫 실행: DB에 없는 전략 모두 수집
        if (hour == 9) {
            for (StrategyType type : new StrategyType[]{StrategyType.SWING, StrategyType.TURNAROUND, StrategyType.VALUE}) {
                List<AiStrategySnapshot> existing = snapshotRepository.findLatestByStrategyType(type);
                if (existing.isEmpty()) {
                    try {
                        collectAndSaveSnapshot(type);
                        log.info("[Scheduler] 09:00 초기 수집: {} (DB에 없었음)", type.name());
                        Thread.sleep(3000); // Gemini Rate Limit 방지
                    } catch (Exception e) {
                        log.error("[Scheduler] 09:00 {} 수집 실패: {}", type.name(), e.getMessage());
                    }
                }
            }
            return;
        }

        // 10시 이후: 시간별 로테이션 (1개씩)
        StrategyType[] rotation = {StrategyType.SWING, StrategyType.TURNAROUND, StrategyType.VALUE};
        StrategyType target = rotation[(hour - 10) % 3];

        try {
            collectAndSaveSnapshot(target);
            log.info("[Scheduler] {} Strategy updated.", target.name());
        } catch (Exception e) {
            log.error("[Scheduler] {} Strategy failed: {}", target.name(), e.getMessage());
        }
    }

    /**
     * 장 마감 확정 배치 (15:40)
     * - 모든 전략 스냅샷의 현재가/등락률을 최종 종가로 업데이트
     * - 장중 데이터가 아닌 확정 종가로 보정
     */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void updateClosingPrices() {
        log.info("[Closing Batch] 장 마감 확정 배치 시작");
        long startTime = System.currentTimeMillis();

        int totalUpdated = 0;
        for (StrategyType type : StrategyType.values()) {
            try {
                int updated = updateSnapshotPrices(type);
                totalUpdated += updated;
                log.info("[Closing Batch] {} 전략: {}건 업데이트", type.name(), updated);
                Thread.sleep(500); // API Rate Limit 방지
            } catch (Exception e) {
                log.error("[Closing Batch] {} 전략 업데이트 실패: {}", type.name(), e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Closing Batch] 장 마감 확정 배치 완료 - 총 {}건 업데이트, {}ms", totalUpdated, elapsed);
    }

    /**
     * 특정 전략의 최신 스냅샷 가격/등락률 업데이트
     * @return 업데이트된 스냅샷 수
     */
    @Transactional
    public int updateSnapshotPrices(StrategyType strategyType) {
        List<AiStrategySnapshot> snapshots = snapshotRepository.findLatestByStrategyType(strategyType);
        if (snapshots.isEmpty()) {
            return 0;
        }

        // 종목코드 목록 추출
        List<String> stockCodes = snapshots.stream()
                .map(AiStrategySnapshot::getStockCode)
                .collect(Collectors.toList());

        // 실시간 시세 조회
        Map<String, StockPriceDto> priceMap = stockPriceService.getStockPrices(stockCodes);

        int updatedCount = 0;
        for (AiStrategySnapshot snapshot : snapshots) {
            StockPriceDto priceDto = priceMap.get(snapshot.getStockCode());
            if (priceDto == null) {
                continue;
            }

            boolean updated = false;

            // 현재가 업데이트
            if (priceDto.getCurrentPrice() != null && priceDto.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0) {
                snapshot.setCurrentPrice(priceDto.getCurrentPrice());
                updated = true;
            }

            // 등락률 업데이트 (API에서 제공하거나 계산)
            BigDecimal changeRate = calculateChangeRate(priceDto, snapshot.getCurrentPrice());
            if (changeRate != null) {
                snapshot.setChangeRate(changeRate);
                updated = true;
            }

            if (updated) {
                snapshotRepository.save(snapshot);
                updatedCount++;
            }
        }

        return updatedCount;
    }

    /**
     * 등락률 계산 (API 값 우선, 없거나 0이면 직접 계산)
     * 공식: (현재가 - 전일종가) / 전일종가 * 100
     *
     * [장전/장후 처리]
     * - 장전/장후에는 prdy_ctrt(전일대비율)이 0으로 오지만
     * - prdy_vrss(전일대비)는 유효한 값이 있으므로 이를 활용하여 계산
     */
    private BigDecimal calculateChangeRate(StockPriceDto priceDto, BigDecimal currentPrice) {
        // 1. API에서 prdy_ctrt(전일대비율)이 유효하면 사용 (0이 아닌 경우)
        if (priceDto.getChangeRate() != null && priceDto.getChangeRate().compareTo(BigDecimal.ZERO) != 0) {
            return priceDto.getChangeRate();
        }

        // 2. prdy_ctrt가 없거나 0인 경우: prdy_vrss(전일대비)로 직접 계산
        BigDecimal price = priceDto.getCurrentPrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            price = currentPrice;
        }

        BigDecimal changePrice = priceDto.getChangePrice();
        // changePrice가 0이 아닐 때만 계산 (0이면 실제로 변동이 없는 것)
        if (price != null && changePrice != null
                && changePrice.compareTo(BigDecimal.ZERO) != 0
                && price.compareTo(BigDecimal.ZERO) > 0) {
            // 전일종가 = 현재가 - 전일대비
            BigDecimal previousClose = price.subtract(changePrice);
            if (previousClose.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal calculated = changePrice.divide(previousClose, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
                log.debug("[등락률 계산] 장전/장후 계산: 현재가={}, 전일대비={}, 전일종가={}, 등락률={}%",
                        price, changePrice, previousClose, calculated);
                return calculated;
            }
        }

        return null;
    }

    /**
     * 오래된 스냅샷 정리 (매일 06:00)
     * - 7일 이상 된 데이터 삭제
     */
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    public void cleanupOldSnapshots() {
        // 안전장치: 최근 24시간 내 스냅샷이 있을 때만 정리 실행
        LocalDateTime recentCheck = LocalDateTime.now().minusHours(24);
        List<AiStrategySnapshot> recent = snapshotRepository.findLatestByStrategyType(StrategyType.SCALPING);
        if (recent.isEmpty() || recent.get(0).getCreatedAt().isBefore(recentCheck)) {
            log.error("[스냅샷 정리] 최근 24시간 내 스냅샷 없음 → 정리 중단 (데이터 보호)");
            return;
        }

        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(7);
        int deleted = snapshotRepository.deleteOldSnapshots(cutoffTime);
        log.info("[스냅샷 정리] {}일 이전 데이터 {}건 삭제", 7, deleted);
    }

    /**
     * 모든 스냅샷 데이터 보정 (관리자용)
     * - 0% 등락률 데이터를 실시간 API로 업데이트
     * @return 업데이트된 총 스냅샷 수
     */
    @Transactional
    public int fixAllSnapshotData() {
        log.info("[Data Fix] 스냅샷 데이터 보정 시작");
        int totalUpdated = 0;

        for (StrategyType type : StrategyType.values()) {
            try {
                int updated = updateSnapshotPrices(type);
                totalUpdated += updated;
                log.info("[Data Fix] {} 전략: {}건 보정", type.name(), updated);
                Thread.sleep(300);
            } catch (Exception e) {
                log.error("[Data Fix] {} 전략 보정 실패: {}", type.name(), e.getMessage());
            }
        }

        log.info("[Data Fix] 스냅샷 데이터 보정 완료 - 총 {}건", totalUpdated);
        return totalUpdated;
    }

    // ========== 스냅샷 수집 로직 ==========

    /**
     * 특정 전략의 스냅샷 수집 및 저장
     */
    @Transactional
    public void collectAndSaveSnapshot(StrategyType strategyType) {
        LocalDateTime now = LocalDateTime.now();
        List<AiStrategySnapshot> candidates = new ArrayList<>();

        switch (strategyType) {
            case SCALPING:
                candidates = collectScalpingData(now);
                break;
            case SWING:
                candidates = collectSwingData(now);
                break;
            case TURNAROUND:
                candidates = collectTurnaroundData(now);
                break;
            case VALUE:
                candidates = collectValueData(now);
                break;
        }

        if (!candidates.isEmpty()) {
            List<AiStrategySnapshot> snapshots = applyGeminiScoring(candidates, strategyType);

            // ai_score가 전체 null이면 저장 스킵 (기존 DB 보존)
            boolean hasAnyAiScore = snapshots.stream().anyMatch(s -> s.getAiScore() != null);
            if (!hasAnyAiScore) {
                // 직전 DB에도 ai_score가 없었다면 → 규칙 기반 임시 점수 부여 후 저장
                for (int i = 0; i < snapshots.size(); i++) {
                    AiStrategySnapshot s = snapshots.get(i);
                    if (s.getAiScore() == null) {
                        // 규칙 기반 폴백: 순위 기반 점수 (Gemini 없이도 유의미)
                        int fallbackAiScore = Math.max(10, 80 - (i * 15)); // 1위=80, 2위=65, 3위=50, 4위=35, 5위=20
                        s.setAiScore(fallbackAiScore);
                        s.setAiComment("알고리즘 기반");
                    }
                }
                log.info("[Snapshot] {} - Gemini 전체 실패 → 규칙 기반 ai_score 부여 후 저장", strategyType.name());
            }

            snapshotRepository.saveAll(snapshots);
            log.info("[Snapshot] {} saved: {} stocks (AI: {})",
                    strategyType.name(), snapshots.size(), hasAnyAiScore ? "Gemini" : "규칙기반");
        } else {
            log.warn("[Snapshot] {} - No data collected.", strategyType.name());
        }
    }

    /**
     * Gemini AI 스코어링 적용
     * 1. 모든 후보의 originalScore 보존
     * 2. Gemini API로 AI 점수 획득
     * 3. 블렌딩: score = originalScore * 0.6 + aiScore * 0.4
     * 4. 내림차순 정렬 후 상위 SNAPSHOT_LIMIT개 선택
     *
     * Gemini 실패 시 알고리즘 점수만으로 기존 동작 유지 (graceful degradation)
     */
    private List<AiStrategySnapshot> applyGeminiScoring(
            List<AiStrategySnapshot> candidates, StrategyType strategyType) {

        // 1. 원본 점수 보존
        for (AiStrategySnapshot s : candidates) {
            s.setOriginalScore(s.getScore());
        }

        // 2. Gemini AI 스코어링 (상위 3개만 전송하여 API 호출 최적화)
        //    알고리즘 점수 기준 상위 3개만 Gemini에 보내고, 나머지는 알고리즘 점수 유지
        List<AiStrategySnapshot> geminiCandidates = candidates.stream()
                .sorted((a, b) -> Integer.compare(
                        b.getOriginalScore() != null ? b.getOriginalScore() : 0,
                        a.getOriginalScore() != null ? a.getOriginalScore() : 0))
                .limit(3)
                .collect(Collectors.toList());

        try {
            Map<String, GeminiService.AiScoreResult> aiResults =
                    geminiService.scoreStockCandidates(geminiCandidates, strategyType.name());

            if (!aiResults.isEmpty()) {
                for (AiStrategySnapshot s : candidates) {
                    GeminiService.AiScoreResult aiResult = aiResults.get(s.getStockCode());
                    if (aiResult != null) {
                        s.setAiScore(aiResult.getAiScore());
                        s.setAiComment(aiResult.getAiComment());
                        // 테마 태그 저장 (콤마 구분 문자열)
                        if (aiResult.getThemes() != null && !aiResult.getThemes().isEmpty()) {
                            s.setAiThemes(String.join(",", aiResult.getThemes()));
                        }
                        // 블렌딩: 알고리즘 60% + AI 40%
                        int blendedScore = (int) Math.round(
                                s.getOriginalScore() * 0.6 + aiResult.getAiScore() * 0.4);
                        s.setScore(Math.max(0, Math.min(100, blendedScore)));
                    }
                    // AI 점수 없는 종목: originalScore 유지 (이미 score == originalScore)
                }
                log.info("[AI Scoring] {} - AI 블렌딩 완료 ({}개 종목 스코어링됨)",
                        strategyType.name(), aiResults.size());
            } else {
                // Gemini 응답 없음 → 직전 DB 스냅샷에서 ai_score 복사
                copyPreviousAiScores(candidates, strategyType);
            }
        } catch (Exception e) {
            log.warn("[AI Scoring] {} - Gemini 스코어링 실패 (직전 AI점수 복원): {}",
                    strategyType.name(), e.getMessage());
            // 실패 시 직전 DB 스냅샷에서 ai_score 복사
            copyPreviousAiScores(candidates, strategyType);
        }

        // 3. 블렌딩 점수 기준 내림차순 정렬
        candidates.sort((a, b) -> Integer.compare(
                b.getScore() != null ? b.getScore() : 0,
                a.getScore() != null ? a.getScore() : 0));

        // 4. 상위 SNAPSHOT_LIMIT개 선택 + rankNum 재부여
        List<AiStrategySnapshot> topSnapshots = candidates.stream()
                .limit(SNAPSHOT_LIMIT)
                .collect(Collectors.toList());

        int rank = 1;
        for (AiStrategySnapshot s : topSnapshots) {
            s.setRankNum(rank++);
        }

        return topSnapshots;
    }

    /**
     * Gemini 실패 시 직전 DB 스냅샷의 AI 점수를 현재 후보에 복사
     * - aiScore, aiComment, aiThemes를 직전 스냅샷에서 가져와 덮어쓰기 방지
     */
    private void copyPreviousAiScores(List<AiStrategySnapshot> candidates, StrategyType strategyType) {
        try {
            List<AiStrategySnapshot> prevSnapshots = snapshotRepository.findLatestByStrategyType(strategyType);

            // 종목코드 → 직전 AI 점수 맵 (null이 아닌 것만)
            Map<String, AiStrategySnapshot> prevMap = new HashMap<>();
            if (!prevSnapshots.isEmpty()) {
                for (AiStrategySnapshot prev : prevSnapshots) {
                    if (prev.getStockCode() != null && prev.getAiScore() != null) {
                        prevMap.put(prev.getStockCode(), prev);
                    }
                }
            }

            int copied = 0;
            for (AiStrategySnapshot s : candidates) {
                if (s.getAiScore() == null && s.getStockCode() != null) {
                    AiStrategySnapshot prev = prevMap.get(s.getStockCode());
                    if (prev != null) {
                        s.setAiScore(prev.getAiScore());
                        s.setAiComment(prev.getAiComment());
                        s.setAiThemes(prev.getAiThemes());
                        if (s.getOriginalScore() != null) {
                            int blended = (int) Math.round(
                                    s.getOriginalScore() * 0.6 + prev.getAiScore() * 0.4);
                            s.setScore(Math.max(0, Math.min(100, blended)));
                        }
                        copied++;
                    }
                    // 직전 스냅샷에도 없으면 → collectAndSaveSnapshot의 규칙 기반 폴백이 처리
                }
            }
            if (copied > 0) {
                log.info("[AI Fallback] {} - 직전 AI점수 {}개 복원", strategyType.name(), copied);
            }
        } catch (Exception e) {
            log.debug("[AI Fallback] {} - 직전 AI점수 복원 실패: {}", strategyType.name(), e.getMessage());
        }
    }

    /**
     * 스캘핑(모멘텀) 전략 데이터 수집
     * - 거래량 급증 + 양봉 종목
     * - 점수: 거래량 비율 기반 (최대 100점)
     */
    private List<AiStrategySnapshot> collectScalpingData(LocalDateTime createdAt) {
        List<ScreenerResultDto> momentum = quantScreenerService.getMomentumStocks(CANDIDATE_LIMIT);

        // 소형주 필터 (시가총액 null이거나 주가 1000원 미만 제외)
        if (!momentum.isEmpty()) {
            int beforeSize = momentum.size();
            momentum = momentum.stream()
                    .filter(dto -> dto.getCurrentPrice() != null
                            && dto.getCurrentPrice().compareTo(MIN_STOCK_PRICE) >= 0)
                    .filter(dto -> dto.getMarketCap() != null
                            && dto.getMarketCap().compareTo(MIN_MARKET_CAP) >= 0)
                    .collect(Collectors.toList());
            if (beforeSize != momentum.size()) {
                log.info("[SCALPING] 소형주 필터: {}건 → {}건 (주가<{}원 또는 시총<{}억 제외)",
                        beforeSize, momentum.size(), MIN_STOCK_PRICE, MIN_MARKET_CAP);
            }
        }

        if (momentum.isEmpty()) {
            log.warn("[SCALPING] KIS API 실패/소형주 필터 후 빈 목록 - 네이버 크롤링 폴백");
            List<AiStrategySnapshot> fallback = crawlNaverTopGainers(StrategyType.SCALPING, createdAt);
            return fallback.isEmpty() ? createFallbackStocks(StrategyType.SCALPING, createdAt) : fallback;
        }

        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        // 실시간 시세 조회 (등락률 포함)
        List<String> stockCodes = momentum.stream()
                .map(ScreenerResultDto::getStockCode)
                .collect(Collectors.toList());
        Map<String, StockPriceDto> priceMap = stockPriceService.getStockPrices(stockCodes);

        log.debug("[SCALPING] 실시간 시세 조회: {}건 중 {}건 성공",
                stockCodes.size(), priceMap.size());

        int rank = 1;
        for (ScreenerResultDto dto : momentum) {
            // 점수 계산: 거래량 비율 기반 (300% 이상이면 100점, 30%면 10점)
            int score = calculateScalpingScore(dto.getVolumeRatio(), dto.getChangeRate());

            // 추천 사유 생성
            String reason = generateScalpingReason(dto.getVolumeRatio(), dto.getChangeRate());

            // 실시간 시세 데이터 가져오기
            StockPriceDto priceDto = priceMap.get(dto.getStockCode());

            // 현재가 및 등락률 결정 (실시간 시세 우선, 없으면 ScreenerResultDto 값 사용)
            BigDecimal currentPrice = dto.getCurrentPrice();
            BigDecimal changeRate = dto.getChangeRate();

            if (priceDto != null) {
                if (priceDto.getCurrentPrice() != null && priceDto.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0) {
                    currentPrice = priceDto.getCurrentPrice();
                }
                if (priceDto.getChangeRate() != null) {
                    changeRate = priceDto.getChangeRate();
                }
            }

            AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                    .strategyType(StrategyType.SCALPING)
                    .stockCode(dto.getStockCode())
                    .stockName(dto.getStockName())
                    .currentPrice(currentPrice)
                    .changeRate(changeRate)
                    .score(score)
                    .reason(reason)
                    .rankNum(rank++)
                    .volumeRatio(dto.getVolumeRatio())
                    .marketCap(dto.getMarketCap())
                    .createdAt(createdAt)
                    .build();

            snapshots.add(snapshot);
        }

        return snapshots;
    }

    /**
     * 외국인/기관 3일 연속 매수 종목 → 보너스 점수 맵 생성
     * - 외국인 3일+ 연속매수: +8점
     * - 기관 3일+ 연속매수: +7점
     * - 양쪽 모두: +15점 (중복 가산)
     * - 5일+ 연속매수 시 추가 +5점
     */
    private Map<String, Integer> getConsecutiveBuyBonusMap() {
        Map<String, Integer> bonusMap = new HashMap<>();
        try {
            List<ConsecutiveBuyDto> foreignBuys = investorTradeService.getConsecutiveBuyStocks("FOREIGN", 3);
            List<ConsecutiveBuyDto> institutionBuys = investorTradeService.getConsecutiveBuyStocks("INSTITUTION", 3);

            if (foreignBuys != null) {
                for (ConsecutiveBuyDto dto : foreignBuys) {
                    int bonus = 8;
                    if (dto.getConsecutiveDays() != null && dto.getConsecutiveDays() >= 5) {
                        bonus += 5;
                    }
                    bonusMap.merge(dto.getStockCode(), bonus, Integer::sum);
                }
            }
            if (institutionBuys != null) {
                for (ConsecutiveBuyDto dto : institutionBuys) {
                    int bonus = 7;
                    if (dto.getConsecutiveDays() != null && dto.getConsecutiveDays() >= 5) {
                        bonus += 5;
                    }
                    bonusMap.merge(dto.getStockCode(), bonus, Integer::sum);
                }
            }
            log.info("[연속매수 보너스] 외국인 {}건, 기관 {}건 → 보너스 대상 {}종목",
                    foreignBuys != null ? foreignBuys.size() : 0,
                    institutionBuys != null ? institutionBuys.size() : 0,
                    bonusMap.size());
        } catch (Exception e) {
            log.warn("[연속매수 보너스] 조회 실패 (무시): {}", e.getMessage());
        }
        return bonusMap;
    }

    /**
     * 어닝 서프라이즈 맵 조회 (stockCode → SurpriseType)
     */
    private Map<String, EarningSurpriseDto.SurpriseType> getEarningSurpriseMap() {
        try {
            Map<String, EarningSurpriseDto.SurpriseType> map = earningSurpriseService.getSurpriseTypeMap();
            if (!map.isEmpty()) {
                log.info("[어닝서프라이즈 보너스] 대상 {}종목", map.size());
            }
            return map;
        } catch (Exception e) {
            log.warn("[어닝서프라이즈 보너스] 조회 실패 (무시): {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 어닝 서프라이즈 보너스 점수 계산
     * - POSITIVE: +10점
     * - TURNAROUND: +12점
     */
    private int getEarningSurpriseBonus(String stockCode,
                                         Map<String, EarningSurpriseDto.SurpriseType> surpriseTypeMap) {
        EarningSurpriseDto.SurpriseType type = surpriseTypeMap.get(stockCode);
        if (type == null) return 0;
        return switch (type) {
            case TURNAROUND -> 12;
            case POSITIVE -> 10;
            default -> 0;
        };
    }

    /**
     * 스윙(마법의 공식) 전략 데이터 수집
     * - PER, ROE, 영업이익률 기반 종합 순위
     * - 점수: 마법의 공식 순위 기반 (상위일수록 높음)
     * - 실시간 등락률(changeRate) 추가 (KIS API prdy_ctrt)
     * - EPS 성장률(epsGrowth) 추가
     */
    private List<AiStrategySnapshot> collectSwingData(LocalDateTime createdAt) {
        List<ScreenerResultDto> magicFormula = quantScreenerService.getMagicFormulaStocks(CANDIDATE_LIMIT, null);
        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        if (magicFormula.isEmpty()) {
            log.warn("[SWING] DB 조회 실패 - 네이버 크롤링 폴백");
            List<AiStrategySnapshot> fallback = crawlNaverTopGainers(StrategyType.SWING, createdAt);
            return fallback.isEmpty() ? createFallbackStocks(StrategyType.SWING, createdAt) : fallback;
        }

        // 연속매수 보너스 맵 조회
        Map<String, Integer> consecutiveBuyBonus = getConsecutiveBuyBonusMap();

        // 어닝 서프라이즈 맵 조회
        Map<String, EarningSurpriseDto.SurpriseType> surpriseTypeMap = getEarningSurpriseMap();

        // 실시간 시세 조회 (등락률 포함)
        List<String> stockCodes = magicFormula.stream()
                .map(ScreenerResultDto::getStockCode)
                .collect(Collectors.toList());
        Map<String, StockPriceDto> priceMap = stockPriceService.getStockPrices(stockCodes);

        log.debug("[SWING] 실시간 시세 조회: {}건 중 {}건 성공",
                stockCodes.size(), priceMap.size());

        int rank = 1;
        for (ScreenerResultDto dto : magicFormula) {
            // 점수 계산: ROE와 영업이익률 기반
            int score = calculateSwingScore(dto.getRoe(), dto.getOperatingMargin(), dto.getPer());

            // 연속매수 보너스 가산
            int bonus = consecutiveBuyBonus.getOrDefault(dto.getStockCode(), 0);
            if (bonus > 0) {
                score = Math.min(100, score + bonus);
                log.info("[SWING] {} 연속매수 보너스 +{}점 → {}점", dto.getStockName(), bonus, score);
            }

            // 어닝 서프라이즈 보너스 가산
            int surpriseBonus = getEarningSurpriseBonus(dto.getStockCode(), surpriseTypeMap);
            if (surpriseBonus > 0) {
                score = Math.min(100, score + surpriseBonus);
                log.info("[SWING] {} 어닝서프라이즈 보너스 +{}점 → {}점", dto.getStockName(), surpriseBonus, score);
            }

            // 추천 사유 생성
            String reason = generateSwingReason(dto.getRoe(), dto.getOperatingMargin(), dto.getPer());
            if (bonus > 0) {
                reason += " | 외국인/기관 연속매수(+" + bonus + "점)";
            }
            if (surpriseBonus > 0) {
                EarningSurpriseDto.SurpriseType sType = surpriseTypeMap.get(dto.getStockCode());
                String surpriseLabel = sType == EarningSurpriseDto.SurpriseType.TURNAROUND
                        ? "적자→흑자 전환" : "어닝서프라이즈";
                reason += " | " + surpriseLabel + "(+" + surpriseBonus + "점)";
            }

            // 실시간 시세 데이터 가져오기
            StockPriceDto priceDto = priceMap.get(dto.getStockCode());

            // 현재가 및 등락률 결정 (실시간 시세 우선, 없으면 ScreenerResultDto 값 사용)
            BigDecimal currentPrice = dto.getCurrentPrice();
            BigDecimal changeRate = null;

            if (priceDto != null) {
                // 실시간 현재가가 있으면 사용
                if (priceDto.getCurrentPrice() != null && priceDto.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0) {
                    currentPrice = priceDto.getCurrentPrice();
                }
                // 실시간 등락률(prdy_ctrt) 사용
                changeRate = priceDto.getChangeRate();
            }

            // EPS 성장률 결정 (epsGrowth 우선, 없으면 profitGrowth 사용)
            BigDecimal epsGrowth = dto.getEpsGrowth();
            if (epsGrowth == null || epsGrowth.compareTo(BigDecimal.ZERO) == 0) {
                epsGrowth = dto.getProfitGrowth();
            }
            // 비정상적인 값 필터링 (1000% 이상은 데이터 오류로 간주)
            if (epsGrowth != null && epsGrowth.abs().compareTo(new BigDecimal("500")) > 0) {
                log.debug("[SWING] {} - EPS 성장률 비정상 값 무시: {}%", dto.getStockName(), epsGrowth);
                epsGrowth = null;
            }

            AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                    .strategyType(StrategyType.SWING)
                    .stockCode(dto.getStockCode())
                    .stockName(dto.getStockName())
                    .currentPrice(currentPrice)
                    .changeRate(changeRate)
                    .score(score)
                    .reason(reason)
                    .rankNum(rank++)
                    .per(dto.getPer())
                    .pbr(dto.getPbr())
                    .roe(dto.getRoe())
                    .operatingMargin(dto.getOperatingMargin())
                    .epsGrowth(epsGrowth)
                    .magicFormulaRank(dto.getMagicFormulaRank())
                    .marketCap(dto.getMarketCap())
                    .createdAt(createdAt)
                    .build();

            snapshots.add(snapshot);

            log.debug("[SWING] {} - 현재가: {}, 등락률: {}%, EPS성장률: {}%",
                    dto.getStockName(), currentPrice, changeRate, epsGrowth);
        }

        return snapshots;
    }

    /**
     * 턴어라운드 전략 데이터 수집
     * - 적자→흑자 전환 또는 이익 급증 종목
     * - 점수: 순이익 변화율 기반
     * - 실시간 등락률(changeRate) 추가 (KIS API prdy_ctrt)
     */
    private List<AiStrategySnapshot> collectTurnaroundData(LocalDateTime createdAt) {
        List<ScreenerResultDto> turnaround = quantScreenerService.getTurnaroundStocks(CANDIDATE_LIMIT);
        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        if (turnaround.isEmpty()) {
            log.warn("[TURNAROUND] DB 조회 실패 - 네이버 크롤링 폴백");
            List<AiStrategySnapshot> fallback = crawlNaverTopGainers(StrategyType.TURNAROUND, createdAt);
            return fallback.isEmpty() ? createFallbackStocks(StrategyType.TURNAROUND, createdAt) : fallback;
        }

        // 실시간 시세 조회 (등락률 포함)
        List<String> stockCodes = turnaround.stream()
                .map(ScreenerResultDto::getStockCode)
                .collect(Collectors.toList());
        Map<String, StockPriceDto> priceMap = stockPriceService.getStockPrices(stockCodes);

        log.debug("[TURNAROUND] 실시간 시세 조회: {}건 중 {}건 성공",
                stockCodes.size(), priceMap.size());

        int rank = 1;
        for (ScreenerResultDto dto : turnaround) {
            // 점수 계산: 턴어라운드 유형 및 변화율 기반
            int score = calculateTurnaroundScore(dto.getTurnaroundType(), dto.getNetIncomeChangeRate());

            // 추천 사유 생성
            String reason = generateTurnaroundReason(dto.getTurnaroundType(), dto.getNetIncomeChangeRate());

            // 실시간 시세 데이터 가져오기
            StockPriceDto priceDto = priceMap.get(dto.getStockCode());

            // 현재가 및 등락률 결정
            BigDecimal currentPrice = dto.getCurrentPrice();
            BigDecimal changeRate = null;

            if (priceDto != null) {
                if (priceDto.getCurrentPrice() != null && priceDto.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0) {
                    currentPrice = priceDto.getCurrentPrice();
                }
                changeRate = priceDto.getChangeRate();
            }

            // 순이익 변화율 검증 (999.99는 흑자전환 특별 표기이므로 유지)
            BigDecimal netIncomeChangeRate = dto.getNetIncomeChangeRate();

            AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                    .strategyType(StrategyType.TURNAROUND)
                    .stockCode(dto.getStockCode())
                    .stockName(dto.getStockName())
                    .currentPrice(currentPrice)
                    .changeRate(changeRate)
                    .score(score)
                    .reason(reason)
                    .rankNum(rank++)
                    .per(dto.getPer())
                    .pbr(dto.getPbr())
                    .roe(dto.getRoe())
                    .turnaroundType(dto.getTurnaroundType())
                    .netIncomeChangeRate(netIncomeChangeRate)
                    .marketCap(dto.getMarketCap())
                    .createdAt(createdAt)
                    .build();

            snapshots.add(snapshot);

            log.debug("[TURNAROUND] {} - 현재가: {}, 등락률: {}%, 순이익변화: {}%",
                    dto.getStockName(), currentPrice, changeRate, netIncomeChangeRate);
        }

        return snapshots;
    }

    /**
     * 가치투자(PEG) 전략 데이터 수집
     * - 저평가 성장주 (PEG < 1.0)
     * - 점수: PEG 기반 (낮을수록 높은 점수)
     * - 실시간 등락률(changeRate) 추가 (KIS API prdy_ctrt)
     */
    private List<AiStrategySnapshot> collectValueData(LocalDateTime createdAt) {
        List<ScreenerResultDto> lowPeg = quantScreenerService.getLowPegStocks(
                new BigDecimal("1.0"), new BigDecimal("10"), CANDIDATE_LIMIT);
        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        if (lowPeg.isEmpty()) {
            log.warn("[VALUE] DB 조회 실패 - 네이버 크롤링 폴백");
            List<AiStrategySnapshot> fallback = crawlNaverTopGainers(StrategyType.VALUE, createdAt);
            return fallback.isEmpty() ? createFallbackStocks(StrategyType.VALUE, createdAt) : fallback;
        }

        // 연속매수 보너스 맵 조회
        Map<String, Integer> consecutiveBuyBonus = getConsecutiveBuyBonusMap();

        // 어닝 서프라이즈 맵 조회
        Map<String, EarningSurpriseDto.SurpriseType> surpriseTypeMap = getEarningSurpriseMap();

        // 실시간 시세 조회 (등락률 포함)
        List<String> stockCodes = lowPeg.stream()
                .map(ScreenerResultDto::getStockCode)
                .collect(Collectors.toList());
        Map<String, StockPriceDto> priceMap = stockPriceService.getStockPrices(stockCodes);

        log.debug("[VALUE] 실시간 시세 조회: {}건 중 {}건 성공",
                stockCodes.size(), priceMap.size());

        int rank = 1;
        for (ScreenerResultDto dto : lowPeg) {
            // 점수 계산: PEG, ROE, PER 기반
            int score = calculateValueScore(dto.getPeg(), dto.getRoe(), dto.getPer());

            // 연속매수 보너스 가산
            int bonus = consecutiveBuyBonus.getOrDefault(dto.getStockCode(), 0);
            if (bonus > 0) {
                score = Math.min(100, score + bonus);
                log.info("[VALUE] {} 연속매수 보너스 +{}점 → {}점", dto.getStockName(), bonus, score);
            }

            // 어닝 서프라이즈 보너스 가산
            int surpriseBonus = getEarningSurpriseBonus(dto.getStockCode(), surpriseTypeMap);
            if (surpriseBonus > 0) {
                score = Math.min(100, score + surpriseBonus);
                log.info("[VALUE] {} 어닝서프라이즈 보너스 +{}점 → {}점", dto.getStockName(), surpriseBonus, score);
            }

            // 추천 사유 생성
            String reason = generateValueReason(dto.getPeg(), dto.getEpsGrowth(), dto.getRoe());
            if (bonus > 0) {
                reason += " | 외국인/기관 연속매수(+" + bonus + "점)";
            }
            if (surpriseBonus > 0) {
                EarningSurpriseDto.SurpriseType sType = surpriseTypeMap.get(dto.getStockCode());
                String surpriseLabel = sType == EarningSurpriseDto.SurpriseType.TURNAROUND
                        ? "적자→흑자 전환" : "어닝서프라이즈";
                reason += " | " + surpriseLabel + "(+" + surpriseBonus + "점)";
            }

            // 실시간 시세 데이터 가져오기
            StockPriceDto priceDto = priceMap.get(dto.getStockCode());

            // 현재가 및 등락률 결정
            BigDecimal currentPrice = dto.getCurrentPrice();
            BigDecimal changeRate = null;

            if (priceDto != null) {
                if (priceDto.getCurrentPrice() != null && priceDto.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0) {
                    currentPrice = priceDto.getCurrentPrice();
                }
                changeRate = priceDto.getChangeRate();
            }

            // EPS 성장률 검증 (비정상적 값 필터링)
            BigDecimal epsGrowth = dto.getEpsGrowth();
            if (epsGrowth != null && epsGrowth.abs().compareTo(new BigDecimal("500")) > 0) {
                log.debug("[VALUE] {} - EPS 성장률 비정상 값 무시: {}%", dto.getStockName(), epsGrowth);
                epsGrowth = dto.getProfitGrowth(); // profitGrowth로 대체
                if (epsGrowth != null && epsGrowth.abs().compareTo(new BigDecimal("500")) > 0) {
                    epsGrowth = null;
                }
            }

            AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                    .strategyType(StrategyType.VALUE)
                    .stockCode(dto.getStockCode())
                    .stockName(dto.getStockName())
                    .currentPrice(currentPrice)
                    .changeRate(changeRate)
                    .score(score)
                    .reason(reason)
                    .rankNum(rank++)
                    .per(dto.getPer())
                    .pbr(dto.getPbr())
                    .roe(dto.getRoe())
                    .peg(dto.getPeg())
                    .epsGrowth(epsGrowth)
                    .marketCap(dto.getMarketCap())
                    .createdAt(createdAt)
                    .build();

            snapshots.add(snapshot);

            log.debug("[VALUE] {} - 현재가: {}, 등락률: {}%, EPS성장률: {}%",
                    dto.getStockName(), currentPrice, changeRate, epsGrowth);
        }

        return snapshots;
    }

    // ========== 네이버 폴백 (3단계) ==========

    /**
     * 네이버 폴백 진입점 (3단계 체인)
     * 1단계: 네이버 모바일 API (JSON) - 거래량 상위
     * 2단계: 네이버 금융 거래상위 HTML 크롤링
     * 3단계: (호출측에서) createFallbackStocks()
     */
    private List<AiStrategySnapshot> crawlNaverTopGainers(StrategyType strategyType, LocalDateTime createdAt) {
        // 1단계: 네이버 모바일 API (JSON)
        List<AiStrategySnapshot> snapshots = fetchNaverVolumeApi(strategyType, createdAt);
        if (!snapshots.isEmpty()) {
            return snapshots;
        }

        // 2단계: 네이버 금융 거래상위 HTML 크롤링
        log.info("[네이버 폴백] {} - 시총 상위 API 결과 없음, 거래상위 HTML 크롤링 시도", strategyType.name());
        return crawlNaverVolumeHtml(strategyType, createdAt);
    }

    /**
     * 1단계: 네이버 모바일 API 시가총액 상위 종목 조회 (JSON)
     * - 거래량 API(/stocks/volume/) 폐기(404) → 시총 상위로 대체
     * - KOSPI + KOSDAQ 순차 조회
     */
    private List<AiStrategySnapshot> fetchNaverVolumeApi(StrategyType strategyType, LocalDateTime createdAt) {
        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        try {
            for (String market : new String[]{"KOSPI", "KOSDAQ"}) {
                if (snapshots.size() >= CANDIDATE_LIMIT) break;

                int remaining = CANDIDATE_LIMIT - snapshots.size();
                String url = String.format(NAVER_VOLUME_API, market, remaining);

                HttpHeaders headers = new HttpHeaders();
                headers.set("User-Agent", CRAWL_USER_AGENT);
                headers.set("Referer", NAVER_REFERER);
                headers.set("Accept", "application/json, text/plain, */*");
                headers.set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
                headers.set("Connection", "keep-alive");

                HttpEntity<String> entity = new HttpEntity<>(headers);
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) continue;

                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode stocks = root.has("stocks") ? root.get("stocks") : root;
                if (stocks == null || !stocks.isArray()) continue;

                for (JsonNode stock : stocks) {
                    if (snapshots.size() >= CANDIDATE_LIMIT) break;

                    try {
                        String stockCode = getJsonText(stock, "itemCode", "stockCode", "cd");
                        String stockName = getJsonText(stock, "stockName", "name", "nm");

                        if (stockCode == null || !stockCode.matches("[0-9]{6}")) continue;
                        if (stockName == null || stockName.isEmpty()) continue;

                        BigDecimal currentPrice = parseNumericValue(
                                getJsonText(stock, "closePrice", "currentPrice", "stckPrpr"));
                        BigDecimal changeRate = parseNumericValue(
                                getJsonText(stock, "fluctuationsRatio", "changeRate", "prdyCtrt"));

                        if (currentPrice == null || currentPrice.compareTo(MIN_STOCK_PRICE) < 0) continue;

                        // 점수: 시총 상위 기본 60점 + 등락률 반영
                        int score = 60;
                        if (changeRate != null) {
                            score = (int) Math.min(100, Math.max(30, 60 + changeRate.doubleValue() * 2));
                        }

                        String reason = changeRate != null
                                ? String.format("시총 상위 대형주 (%+.2f%%)", changeRate)
                                : "시총 상위 대형주";

                        AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                                .strategyType(strategyType)
                                .stockCode(stockCode)
                                .stockName(stockName)
                                .currentPrice(currentPrice)
                                .changeRate(changeRate)
                                .score(score)
                                .reason(reason)
                                .rankNum(snapshots.size() + 1)
                                .createdAt(createdAt)
                                .build();

                        snapshots.add(snapshot);
                    } catch (Exception e) {
                        continue;
                    }
                }

                // KOSPI → KOSDAQ 간 딜레이
                if ("KOSPI".equals(market) && snapshots.size() < CANDIDATE_LIMIT) {
                    Thread.sleep(300);
                }
            }

            if (!snapshots.isEmpty()) {
                log.info("[네이버 API 폴백] {} - 시총 상위 {}개 종목 조회 완료", strategyType.name(), snapshots.size());
            }
        } catch (Exception e) {
            log.warn("[네이버 API 폴백] {} - API 실패: {}", strategyType.name(), e.getMessage());
        }

        return snapshots;
    }

    /**
     * 2단계: 네이버 금융 거래상위 HTML 크롤링 (KOSPI + KOSDAQ)
     * - 참조: MarketTimingService.crawlStockCount() 동일 셀렉터
     * - 거래상위는 장 마감 후에도 데이터 존재 (상승률 대비 안정적)
     */
    private List<AiStrategySnapshot> crawlNaverVolumeHtml(StrategyType strategyType, LocalDateTime createdAt) {
        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        try {
            for (String sosok : new String[]{"0", "1"}) { // 0=KOSPI, 1=KOSDAQ
                if (snapshots.size() >= CANDIDATE_LIMIT) break;

                String url = NAVER_VOLUME_HTML_URL + "?sosok=" + sosok;
                Document doc = Jsoup.connect(url)
                        .userAgent(CRAWL_USER_AGENT)
                        .timeout(10000)
                        .get();

                Elements rows = doc.select("table.type_2 tbody tr");

                for (Element row : rows) {
                    if (snapshots.size() >= CANDIDATE_LIMIT) break;

                    Elements tds = row.select("td");
                    if (tds.size() < 4) continue;

                    Element link = row.selectFirst("a[href*=code=]");
                    if (link == null) continue;

                    try {
                        String href = link.attr("href");
                        String stockCode = href.replaceAll(".*code=([0-9]+).*", "$1");
                        if (stockCode.length() != 6) continue;

                        String stockName = link.text().trim();
                        if (stockName.isEmpty()) continue;

                        // 링크 td 인덱스 기준 파싱
                        int linkTdIndex = -1;
                        for (int i = 0; i < tds.size(); i++) {
                            if (tds.get(i).selectFirst("a[href*=code=]") != null) {
                                linkTdIndex = i;
                                break;
                            }
                        }
                        if (linkTdIndex < 0 || linkTdIndex + 3 >= tds.size()) continue;

                        String priceText = tds.get(linkTdIndex + 1).text().replace(",", "").trim();
                        BigDecimal currentPrice = new BigDecimal(priceText);
                        if (currentPrice.compareTo(MIN_STOCK_PRICE) < 0) continue;

                        String rateText = tds.get(linkTdIndex + 3).text()
                                .replace("%", "").replace("+", "").trim();
                        BigDecimal changeRate = new BigDecimal(rateText);

                        int score = (int) Math.min(100, Math.max(30, 60 + changeRate.doubleValue() * 2));

                        AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                                .strategyType(strategyType)
                                .stockCode(stockCode)
                                .stockName(stockName)
                                .currentPrice(currentPrice)
                                .changeRate(changeRate)
                                .score(score)
                                .reason(String.format("거래량 상위 (%+.2f%%)", changeRate))
                                .rankNum(snapshots.size() + 1)
                                .createdAt(createdAt)
                                .build();

                        snapshots.add(snapshot);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                }

                if ("0".equals(sosok) && snapshots.size() < CANDIDATE_LIMIT) {
                    Thread.sleep(500);
                }
            }

            if (!snapshots.isEmpty()) {
                log.info("[네이버 HTML 폴백] {} - {}개 종목 크롤링 완료", strategyType.name(), snapshots.size());
            }
        } catch (Exception e) {
            log.warn("[네이버 HTML 폴백] {} - 크롤링 실패: {}", strategyType.name(), e.getMessage());
        }

        return snapshots;
    }

    /** JSON 필드 다중 이름 지원 (네이버 API 필드명 변동 대응) */
    private String getJsonText(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            if (node.has(field) && !node.get(field).isNull()) {
                return node.get(field).asText().trim();
            }
        }
        return null;
    }

    /** 숫자 문자열 파싱 (콤마/부호/% 제거) */
    private BigDecimal parseNumericValue(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return new BigDecimal(text.replace(",", "").replace("+", "").replace("%", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 3단계 최종 안전장치: 대형주 3개
     * - 모든 크롤링 실패 시에도 빈 화면 방지
     * - stockPriceService로 현재가 조회
     */
    private List<AiStrategySnapshot> createFallbackStocks(StrategyType strategyType, LocalDateTime createdAt) {
        log.info("[최종 폴백] {} - 대형주 폴백 사용", strategyType.name());

        // 전략별 차별화된 폴백 종목
        String[][] bluechips;
        if (strategyType == StrategyType.SCALPING) {
            // 스캘핑: 유동성 높은 대형주 (거래량 풍부)
            bluechips = new String[][]{
                    {"005930", "삼성전자"}, {"000660", "SK하이닉스"},
                    {"005380", "현대차"}, {"035720", "카카오"}, {"035420", "NAVER"}
            };
        } else if (strategyType == StrategyType.TURNAROUND) {
            // 턴어라운드: 실적 반등 기대주
            bluechips = new String[][]{
                    {"005930", "삼성전자"}, {"000660", "SK하이닉스"},
                    {"105560", "KB금융"}, {"055550", "신한지주"}, {"086790", "하나금융지주"}
            };
        } else {
            // SWING/VALUE: 우량 가치주
            bluechips = new String[][]{
                    {"005930", "삼성전자"}, {"000660", "SK하이닉스"},
                    {"035420", "NAVER"}, {"105560", "KB금융"}, {"051910", "LG화학"}
            };
        }

        List<String> codes = Arrays.stream(bluechips).map(b -> b[0]).collect(Collectors.toList());
        Map<String, StockPriceDto> priceMap = stockPriceService.getStockPrices(codes);

        List<AiStrategySnapshot> snapshots = new ArrayList<>();
        int rank = 1;

        for (String[] stock : bluechips) {
            BigDecimal currentPrice = BigDecimal.ZERO;
            BigDecimal changeRate = null;

            StockPriceDto priceDto = priceMap.get(stock[0]);
            if (priceDto != null) {
                if (priceDto.getCurrentPrice() != null) {
                    currentPrice = priceDto.getCurrentPrice();
                }
                changeRate = priceDto.getChangeRate();
            }

            AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                    .strategyType(strategyType)
                    .stockCode(stock[0])
                    .stockName(stock[1])
                    .currentPrice(currentPrice)
                    .changeRate(changeRate)
                    .score(50)
                    .reason("시가총액 상위 대표주")
                    .rankNum(rank++)
                    .createdAt(createdAt)
                    .build();

            snapshots.add(snapshot);
        }

        return snapshots;
    }

    // ========== 점수 계산 로직 ==========

    /**
     * 스캘핑 점수 계산
     * - 거래량 비율: 0~60점 (300% = 60점)
     * - 등락률: 0~40점 (10% = 40점)
     */
    private int calculateScalpingScore(BigDecimal volumeRatio, BigDecimal changeRate) {
        int score = 0;

        // 거래량 점수 (최대 60점)
        if (volumeRatio != null) {
            double volScore = Math.min(60, volumeRatio.doubleValue() / 5.0); // 300% = 60점
            score += (int) volScore;
        }

        // 등락률 점수 (최대 40점)
        if (changeRate != null && changeRate.compareTo(BigDecimal.ZERO) > 0) {
            double rateScore = Math.min(40, changeRate.doubleValue() * 4); // 10% = 40점
            score += (int) rateScore;
        }

        return Math.min(100, score);
    }

    /**
     * 스윙(마법의 공식) 점수 계산
     * - ROE: 0~40점 (20% ROE = 40점)
     * - 영업이익률: 0~30점 (15% = 30점)
     * - PER 역수 보너스: 0~30점 (PER 5 = 30점)
     */
    private int calculateSwingScore(BigDecimal roe, BigDecimal operatingMargin, BigDecimal per) {
        int score = 0;

        // ROE 점수 (최대 40점)
        if (roe != null && roe.compareTo(BigDecimal.ZERO) > 0) {
            double roeScore = Math.min(40, roe.doubleValue() * 2); // 20% = 40점
            score += (int) roeScore;
        }

        // 영업이익률 점수 (최대 30점)
        if (operatingMargin != null && operatingMargin.compareTo(BigDecimal.ZERO) > 0) {
            double marginScore = Math.min(30, operatingMargin.doubleValue() * 2); // 15% = 30점
            score += (int) marginScore;
        }

        // PER 역수 보너스 (최대 30점, PER이 낮을수록 좋음)
        if (per != null && per.compareTo(BigDecimal.ZERO) > 0) {
            double perBonus = Math.min(30, 150 / per.doubleValue()); // PER 5 = 30점
            score += (int) perBonus;
        }

        return Math.min(100, score);
    }

    /**
     * 턴어라운드 점수 계산
     * - 흑자전환: 기본 70점 + 시가총액 보너스
     * - 이익성장: 변화율 기반 (100% = 50점, 최대 80점)
     */
    private int calculateTurnaroundScore(String turnaroundType, BigDecimal changeRate) {
        int score = 0;

        if ("LOSS_TO_PROFIT".equals(turnaroundType)) {
            // 흑자전환은 기본 70점
            score = 70;
            // 추가 보너스 가능 (시가총액, 업종 등 고려 가능)
            score += 15; // 임시 보너스
        } else if ("PROFIT_GROWTH".equals(turnaroundType) && changeRate != null) {
            // 이익 성장률 기반 (50% = 25점, 100% = 50점, 최대 80점)
            double growthScore = Math.min(80, changeRate.doubleValue() / 2);
            score = (int) growthScore;
        }

        return Math.min(100, Math.max(0, score));
    }

    /**
     * 가치투자(PEG) 점수 계산
     * - PEG 역수: 0~70점 (PEG 0.3 = 70점)
     * - ROE 보너스: 0~20점
     * - PER 보너스: 0~10점
     */
    private int calculateValueScore(BigDecimal peg, BigDecimal roe, BigDecimal per) {
        int score = 0;

        // PEG 역수 점수 (최대 70점, PEG가 낮을수록 좋음)
        if (peg != null && peg.compareTo(BigDecimal.ZERO) > 0) {
            double pegScore = Math.min(70, (1 / peg.doubleValue()) * 50); // PEG 0.5 = 100점 -> 70점 cap
            score += (int) pegScore;
        }

        // ROE 보너스 (최대 20점)
        if (roe != null && roe.compareTo(BigDecimal.ZERO) > 0) {
            double roeBonus = Math.min(20, roe.doubleValue());
            score += (int) roeBonus;
        }

        // PER 보너스 (최대 10점, PER 10 이하면 보너스)
        if (per != null && per.compareTo(new BigDecimal("10")) < 0) {
            double perBonus = Math.min(10, 10 - per.doubleValue());
            score += (int) perBonus;
        }

        return Math.min(100, score);
    }

    // ========== 추천 사유 생성 ==========

    private String generateScalpingReason(BigDecimal volumeRatio, BigDecimal changeRate) {
        StringBuilder reason = new StringBuilder();

        if (volumeRatio != null) {
            reason.append(String.format("거래량 %,.0f%% 급증", volumeRatio));
        }

        if (changeRate != null && changeRate.compareTo(BigDecimal.ZERO) > 0) {
            if (reason.length() > 0) reason.append(", ");
            reason.append(String.format("+%.2f%% 상승", changeRate));
        }

        return reason.length() > 0 ? reason.toString() : "모멘텀 발생";
    }

    private String generateSwingReason(BigDecimal roe, BigDecimal operatingMargin, BigDecimal per) {
        List<String> reasons = new ArrayList<>();

        if (roe != null && roe.compareTo(new BigDecimal("15")) >= 0) {
            reasons.add(String.format("ROE %.1f%%", roe));
        }

        if (operatingMargin != null && operatingMargin.compareTo(new BigDecimal("10")) >= 0) {
            reasons.add(String.format("영업이익률 %.1f%%", operatingMargin));
        }

        if (per != null && per.compareTo(new BigDecimal("10")) < 0) {
            reasons.add(String.format("PER %.1f배", per));
        }

        return reasons.isEmpty() ? "우량 가치주" : String.join(", ", reasons);
    }

    private String generateTurnaroundReason(String turnaroundType, BigDecimal changeRate) {
        if ("LOSS_TO_PROFIT".equals(turnaroundType)) {
            return "적자→흑자 전환 성공";
        } else if ("PROFIT_GROWTH".equals(turnaroundType) && changeRate != null) {
            return String.format("순이익 %,.0f%% 급증", changeRate);
        }
        return "실적 개선 중";
    }

    private String generateValueReason(BigDecimal peg, BigDecimal epsGrowth, BigDecimal roe) {
        List<String> reasons = new ArrayList<>();

        if (peg != null) {
            reasons.add(String.format("PEG %.2f", peg));
        }

        if (epsGrowth != null && epsGrowth.compareTo(new BigDecimal("20")) >= 0) {
            reasons.add(String.format("EPS성장 %.0f%%", epsGrowth));
        }

        if (roe != null && roe.compareTo(new BigDecimal("10")) >= 0) {
            reasons.add(String.format("ROE %.1f%%", roe));
        }

        return reasons.isEmpty() ? "저평가 성장주" : String.join(", ", reasons);
    }

    // ========== API 응답용 메서드 (DB 조회만) ==========

    /**
     * 모든 전략의 최신 스냅샷 조회
     * - DB 조회 우선, 비어있으면 동기적으로 수집 (Fallback)
     * - 기간별 수익률 계산 포함
     */
    @Transactional
    public AiStrategySnapshotDto.AllStrategiesResponse getAllLatestSnapshots() {
        Map<String, List<AiStrategySnapshotDto>> strategies = new LinkedHashMap<>();
        Map<String, LocalDateTime> lastUpdated = new LinkedHashMap<>();

        for (StrategyType type : StrategyType.values()) {
            List<AiStrategySnapshot> snapshots = snapshotRepository.findLatestByStrategyType(type);

            // Fallback: DB에 데이터가 없으면 빈 리스트로 진행 (동기 수집 제거 — Gemini Rate Limit 방지)
            if (snapshots.isEmpty()) {
                log.warn("[AiStrategy] {} 전략 DB 데이터 없음 — 스케줄러가 수집할 때까지 대기", type.name());
            }

            // ★ Freshness check: 장중에 오래된 스냅샷이면 경고 (하지만 데이터는 반환)
            if (!snapshots.isEmpty()) {
                LocalDateTime latestCreatedAt = snapshots.get(0).getCreatedAt();
                LocalDateTime now = LocalDateTime.now();
                long staleMinutes = Duration.between(latestCreatedAt, now).toMinutes();
                if (staleMinutes > 120) {
                    log.warn("[AiStrategy] ⚠ {} 스냅샷 {}분 전 데이터 — stale이지만 반환 (빈 응답 방지)",
                            type.name(), staleMinutes);
                    // 기존: 빈 리스트 반환 → 종합추천 연쇄 실패
                    // 변경: stale이라도 데이터 반환 (없는 것보다 나음)
                }
            }

            // Entity -> DTO 변환 + 수익률 계산
            List<AiStrategySnapshotDto> dtos = snapshots.stream()
                    .map(this::toSnapshotDtoWithReturns)
                    .collect(Collectors.toList());

            strategies.put(type.name(), dtos);

            // 최종 업데이트 시각
            if (!snapshots.isEmpty()) {
                lastUpdated.put(type.name(), snapshots.get(0).getCreatedAt());
            }
        }

        return AiStrategySnapshotDto.AllStrategiesResponse.builder()
                .strategies(strategies)
                .lastUpdated(lastUpdated)
                .responseTime(LocalDateTime.now())
                .build();
    }

    /**
     * 특정 전략의 최신 스냅샷 조회
     * - DB 조회 우선, 비어있으면 동기적으로 수집 (Fallback)
     * - 기간별 수익률 계산 포함
     */
    @Transactional
    public List<AiStrategySnapshotDto> getLatestByStrategy(StrategyType strategyType) {
        List<AiStrategySnapshot> snapshots = snapshotRepository.findLatestByStrategyType(strategyType);

        // Fallback: DB에 데이터가 없으면 동기적으로 수집
        if (snapshots.isEmpty()) {
            log.warn("[Fallback] {} 전략 데이터 없음 - 동기 수집 시작", strategyType.name());
            try {
                collectAndSaveSnapshot(strategyType);
                snapshots = snapshotRepository.findLatestByStrategyType(strategyType);
                log.info("[Fallback] {} Strategy collected: {} stocks.", strategyType.name(), snapshots.size());
            } catch (Exception e) {
                log.error("[Fallback] {} 전략 동기 수집 실패: {}", strategyType.name(), e.getMessage());
            }
        }

        // Entity -> DTO 변환 + 수익률 계산
        return snapshots.stream()
                .map(this::toSnapshotDtoWithReturns)
                .collect(Collectors.toList());
    }

    // ========== 수익률 계산 로직 ==========

    /**
     * Entity -> DTO 변환 + 기간별 수익률 계산
     */
    private AiStrategySnapshotDto toSnapshotDtoWithReturns(AiStrategySnapshot entity) {
        AiStrategySnapshotDto dto = AiStrategySnapshotDto.fromEntity(entity);

        // 현재가가 없으면 수익률 계산 불가
        if (entity.getCurrentPrice() == null || entity.getCurrentPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return dto;
        }

        LocalDateTime now = LocalDateTime.now();
        BigDecimal currentPrice = entity.getCurrentPrice();
        String stockCode = entity.getStockCode();

        // 1주 수익률 (7일 전)
        dto.setReturn1Week(calculateReturn(stockCode, currentPrice, now.minusDays(7), now.minusDays(5)));

        // 1개월 수익률 (30일 전)
        dto.setReturn1Month(calculateReturn(stockCode, currentPrice, now.minusDays(32), now.minusDays(28)));

        // 3개월 수익률 (90일 전)
        dto.setReturn3Month(calculateReturn(stockCode, currentPrice, now.minusDays(95), now.minusDays(85)));

        return dto;
    }

    /**
     * 수익률 계산
     * - startDate ~ endDate 범위 내 가장 최신 과거 스냅샷을 기준으로 계산
     *
     * @param stockCode 종목코드
     * @param currentPrice 현재가
     * @param startDate 기준 시작일 (예: 7일 전)
     * @param endDate 기준 종료일 (허용 범위)
     * @return 수익률 (%) 또는 데이터 없으면 null
     */
    private Double calculateReturn(String stockCode, BigDecimal currentPrice,
                                   LocalDateTime startDate, LocalDateTime endDate) {
        try {
            Optional<AiStrategySnapshot> pastSnapshot =
                    snapshotRepository.findNearestSnapshotInRange(stockCode, startDate, endDate);

            if (pastSnapshot.isEmpty()) {
                // 범위 내 데이터 없으면 해당 날짜 이전 가장 가까운 데이터 사용
                pastSnapshot = snapshotRepository.findNearestSnapshotBefore(stockCode, endDate);
            }

            if (pastSnapshot.isPresent() && pastSnapshot.get().getCurrentPrice() != null) {
                BigDecimal pastPrice = pastSnapshot.get().getCurrentPrice();
                if (pastPrice.compareTo(BigDecimal.ZERO) > 0) {
                    // 수익률 = (현재가 - 과거가) / 과거가 * 100
                    double returnRate = currentPrice.subtract(pastPrice)
                            .divide(pastPrice, 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .doubleValue();
                    return Math.round(returnRate * 100.0) / 100.0; // 소수점 2자리
                }
            }
        } catch (Exception e) {
            log.debug("[수익률 계산] {} 과거 데이터 조회 실패: {}", stockCode, e.getMessage());
        }

        return null; // 데이터 부족
    }

    /**
     * 수동 스냅샷 수집 (테스트/관리용)
     */
    public Map<String, Integer> collectAllSnapshotsManually() {
        Map<String, Integer> result = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (StrategyType type : StrategyType.values()) {
            try {
                collectAndSaveSnapshot(type);
                List<AiStrategySnapshot> saved = snapshotRepository.findLatestByStrategyType(type);
                result.put(type.name(), saved.size());
                Thread.sleep(500);
            } catch (Exception e) {
                log.error("수동 스냅샷 수집 실패 - {}: {}", type, e.getMessage());
                result.put(type.name(), 0);
            }
        }

        return result;
    }

    /**
     * 스냅샷 통계 조회 (디버깅용)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSnapshotStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        List<Object[]> counts = snapshotRepository.countByStrategyType();
        Map<String, Long> countByType = new LinkedHashMap<>();
        for (Object[] row : counts) {
            countByType.put(((StrategyType) row[0]).name(), (Long) row[1]);
        }
        stats.put("countByType", countByType);

        for (StrategyType type : StrategyType.values()) {
            Optional<LocalDateTime> latestTime = snapshotRepository.findLatestCreatedAt(type);
            stats.put(type.name() + "_lastUpdated", latestTime.orElse(null));
        }

        return stats;
    }
}
