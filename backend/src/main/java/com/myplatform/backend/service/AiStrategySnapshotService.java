package com.myplatform.backend.service;

import com.myplatform.backend.dto.AiStrategySnapshotDto;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.AiStrategySnapshot;
import com.myplatform.backend.entity.AiStrategySnapshot.StrategyType;
import com.myplatform.backend.repository.AiStrategySnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
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

    // AI 스코어링 후보 수집 수 (Gemini 평가용)
    private static final int CANDIDATE_LIMIT = 10;
    // 최종 저장 수 (블렌딩 후 TOP N)
    private static final int SNAPSHOT_LIMIT = 5;

    // 네이버 금융 상승률 상위 URL (폴백용)
    private static final String NAVER_RISE_URL = "https://finance.naver.com/sise/sise_rise.naver";
    private static final String CRAWL_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // ========== 서버 시작 시 Warm-up ==========

    /**
     * 서버 시작 시 모든 전략 스냅샷 초기화 (Warm-up)
     * - 주말/휴일에도 실행하여 DB에 최소 데이터 보장
     * - 각 전략별 순차적으로 수집 (API Rate Limit 고려)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpSnapshots() {
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
                Thread.sleep(1500);
            } catch (Exception e) {
                log.error("[Warm-up] {} 전략 스냅샷 수집 실패: {}", type.name(), e.getMessage());
                failCount++;
            }
        }

        log.info("[Warm-up] 초기화 완료 - 성공: {}, 실패: {}", successCount, failCount);
    }

    // ========== 스케줄러 (Dual Track) ==========

    /**
     * Track A: 스캘핑 전략 스냅샷 (2분 간격)
     * - 장중 09:05 ~ 15:20
     * - 평일만 실행
     */
    @Scheduled(cron = "0 */2 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectScalpingSnapshot() {
        LocalTime now = LocalTime.now();

        // 09:05 이전, 15:20 이후는 스킵
        if (now.isBefore(LocalTime.of(9, 5)) || now.isAfter(LocalTime.of(15, 20))) {
            return;
        }

        try {
            collectAndSaveSnapshot(StrategyType.SCALPING);
            List<AiStrategySnapshot> saved = snapshotRepository.findLatestByStrategyType(StrategyType.SCALPING);
            log.info("[Scheduler] SCALPING Strategy updated: {} stocks saved.", saved.size());
        } catch (Exception e) {
            log.error("[Scheduler] SCALPING Strategy update failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Track B: 중장기 전략 스냅샷 (30분 간격)
     * - 장중 09:00 ~ 15:30
     * - 평일만 실행
     * - SWING, TURNAROUND, VALUE 전략 수집
     */
    @Scheduled(cron = "0 0,30 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectLongTermSnapshots() {
        LocalTime now = LocalTime.now();

        // 15:30 이후는 스킵
        if (now.isAfter(LocalTime.of(15, 30))) {
            return;
        }

        try {
            collectAndSaveSnapshot(StrategyType.SWING);
            List<AiStrategySnapshot> swingSaved = snapshotRepository.findLatestByStrategyType(StrategyType.SWING);
            log.info("[Scheduler] SWING Strategy updated: {} stocks saved.", swingSaved.size());
            Thread.sleep(1000); // API 호출 간격

            collectAndSaveSnapshot(StrategyType.TURNAROUND);
            List<AiStrategySnapshot> turnaroundSaved = snapshotRepository.findLatestByStrategyType(StrategyType.TURNAROUND);
            log.info("[Scheduler] TURNAROUND Strategy updated: {} stocks saved.", turnaroundSaved.size());
            Thread.sleep(1000);

            collectAndSaveSnapshot(StrategyType.VALUE);
            List<AiStrategySnapshot> valueSaved = snapshotRepository.findLatestByStrategyType(StrategyType.VALUE);
            log.info("[Scheduler] VALUE Strategy updated: {} stocks saved.", valueSaved.size());

        } catch (Exception e) {
            log.error("[Scheduler] Long-term strategies update failed: {}", e.getMessage(), e);
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
            // AI 스코어링 적용 (최대 10개 → 블렌딩 → TOP 5)
            List<AiStrategySnapshot> snapshots = applyGeminiScoring(candidates, strategyType);
            snapshotRepository.saveAll(snapshots);
            log.debug("[Snapshot] {} saved: {} stocks (from {} candidates).",
                    strategyType.name(), snapshots.size(), candidates.size());
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
                log.debug("[AI Scoring] {} - AI 결과 없음, 알고리즘 점수만 사용", strategyType.name());
            }
        } catch (Exception e) {
            log.warn("[AI Scoring] {} - Gemini 스코어링 실패 (graceful degradation): {}",
                    strategyType.name(), e.getMessage());
            // 실패 시 알고리즘 점수만으로 동작
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
     * 스캘핑(모멘텀) 전략 데이터 수집
     * - 거래량 급증 + 양봉 종목
     * - 점수: 거래량 비율 기반 (최대 100점)
     */
    private List<AiStrategySnapshot> collectScalpingData(LocalDateTime createdAt) {
        List<ScreenerResultDto> momentum = quantScreenerService.getMomentumStocks(CANDIDATE_LIMIT);

        if (momentum.isEmpty()) {
            log.warn("[SCALPING] KIS API 실패 - 네이버 크롤링 폴백");
            List<AiStrategySnapshot> fallback = crawlNaverTopGainers(StrategyType.SCALPING, createdAt);
            return fallback.isEmpty() ? createFallbackStocks(StrategyType.SCALPING, createdAt) : fallback;
        }

        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        int rank = 1;
        for (ScreenerResultDto dto : momentum) {
            // 점수 계산: 거래량 비율 기반 (300% 이상이면 100점, 30%면 10점)
            int score = calculateScalpingScore(dto.getVolumeRatio(), dto.getChangeRate());

            // 추천 사유 생성
            String reason = generateScalpingReason(dto.getVolumeRatio(), dto.getChangeRate());

            AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                    .strategyType(StrategyType.SCALPING)
                    .stockCode(dto.getStockCode())
                    .stockName(dto.getStockName())
                    .currentPrice(dto.getCurrentPrice())
                    .changeRate(dto.getChangeRate())
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

            // 추천 사유 생성
            String reason = generateSwingReason(dto.getRoe(), dto.getOperatingMargin(), dto.getPer());

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

            // 추천 사유 생성
            String reason = generateValueReason(dto.getPeg(), dto.getEpsGrowth(), dto.getRoe());

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

    // ========== 네이버 크롤링 폴백 ==========

    /**
     * 네이버 금융 상승률 상위 크롤링 (KOSPI + KOSDAQ)
     * - KIS API/DB 데이터 소스 실패 시 폴백
     * - 참조: MarketTimingService.crawlStockCount() 동일 셀렉터
     */
    private List<AiStrategySnapshot> crawlNaverTopGainers(StrategyType strategyType, LocalDateTime createdAt) {
        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        try {
            for (String sosok : new String[]{"0", "1"}) { // 0=KOSPI, 1=KOSDAQ
                if (snapshots.size() >= CANDIDATE_LIMIT) break;

                String url = NAVER_RISE_URL + "?sosok=" + sosok;
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
                        // 종목코드 추출
                        String href = link.attr("href");
                        String stockCode = href.replaceAll(".*code=([0-9]+).*", "$1");
                        if (stockCode.length() != 6) continue;

                        String stockName = link.text().trim();
                        if (stockName.isEmpty()) continue;

                        // 링크가 포함된 td 인덱스 기준으로 현재가/등락률 파싱
                        int linkTdIndex = -1;
                        for (int i = 0; i < tds.size(); i++) {
                            if (tds.get(i).selectFirst("a[href*=code=]") != null) {
                                linkTdIndex = i;
                                break;
                            }
                        }
                        if (linkTdIndex < 0 || linkTdIndex + 3 >= tds.size()) continue;

                        // 현재가 (linkTdIndex + 1)
                        String priceText = tds.get(linkTdIndex + 1).text().replace(",", "").trim();
                        BigDecimal currentPrice = new BigDecimal(priceText);
                        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) continue;

                        // 등락률 (linkTdIndex + 3)
                        String rateText = tds.get(linkTdIndex + 3).text()
                                .replace("%", "").replace("+", "").trim();
                        BigDecimal changeRate = new BigDecimal(rateText);

                        // 점수: 변화율 기반
                        int score = (int) Math.min(100, 30 + changeRate.doubleValue() * 2.3);

                        AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                                .strategyType(strategyType)
                                .stockCode(stockCode)
                                .stockName(stockName)
                                .currentPrice(currentPrice)
                                .changeRate(changeRate)
                                .score(score)
                                .reason(String.format("상승률 상위 (+%.2f%%)", changeRate))
                                .rankNum(snapshots.size() + 1)
                                .createdAt(createdAt)
                                .build();

                        snapshots.add(snapshot);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                }

                // KOSPI → KOSDAQ 간 딜레이
                if ("0".equals(sosok) && snapshots.size() < CANDIDATE_LIMIT) {
                    Thread.sleep(500);
                }
            }

            log.info("[네이버 폴백] {} - {}개 종목 크롤링 완료", strategyType.name(), snapshots.size());
        } catch (Exception e) {
            log.warn("[네이버 폴백] {} - 크롤링 실패: {}", strategyType.name(), e.getMessage());
        }

        return snapshots;
    }

    /**
     * 최후 안전장치: 시가총액 상위 대표주 5개
     * - 네이버 크롤링까지 실패 시 사용
     */
    private List<AiStrategySnapshot> createFallbackStocks(StrategyType strategyType, LocalDateTime createdAt) {
        log.info("[최종 폴백] {} - 시가총액 상위 대표주 사용", strategyType.name());

        String[][] bluechips = {
                {"005930", "삼성전자"},
                {"000660", "SK하이닉스"},
                {"373220", "LG에너지솔루션"},
                {"005380", "현대차"},
                {"000270", "기아"}
        };

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

            // Fallback: DB에 데이터가 없으면 동기적으로 수집
            if (snapshots.isEmpty()) {
                log.warn("[Fallback] {} 전략 데이터 없음 - 동기 수집 시작", type.name());
                try {
                    collectAndSaveSnapshot(type);
                    snapshots = snapshotRepository.findLatestByStrategyType(type);
                    log.info("[Fallback] {} Strategy collected: {} stocks.", type.name(), snapshots.size());
                } catch (Exception e) {
                    log.error("[Fallback] {} 전략 동기 수집 실패: {}", type.name(), e.getMessage());
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
