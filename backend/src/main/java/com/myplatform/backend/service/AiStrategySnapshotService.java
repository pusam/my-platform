package com.myplatform.backend.service;

import com.myplatform.backend.dto.AiStrategySnapshotDto;
import com.myplatform.backend.dto.ScreenerResultDto;
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

    // 스냅샷당 저장할 종목 수
    private static final int SNAPSHOT_LIMIT = 5;

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
     * 오래된 스냅샷 정리 (매일 06:00)
     * - 7일 이상 된 데이터 삭제
     */
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    public void cleanupOldSnapshots() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(7);
        int deleted = snapshotRepository.deleteOldSnapshots(cutoffTime);
        log.info("[스냅샷 정리] {}일 이전 데이터 {}건 삭제", 7, deleted);
    }

    // ========== 스냅샷 수집 로직 ==========

    /**
     * 특정 전략의 스냅샷 수집 및 저장
     */
    @Transactional
    public void collectAndSaveSnapshot(StrategyType strategyType) {
        LocalDateTime now = LocalDateTime.now();
        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        switch (strategyType) {
            case SCALPING:
                snapshots = collectScalpingData(now);
                break;
            case SWING:
                snapshots = collectSwingData(now);
                break;
            case TURNAROUND:
                snapshots = collectTurnaroundData(now);
                break;
            case VALUE:
                snapshots = collectValueData(now);
                break;
        }

        if (!snapshots.isEmpty()) {
            snapshotRepository.saveAll(snapshots);
            log.debug("[Snapshot] {} saved: {} stocks.", strategyType.name(), snapshots.size());
        } else {
            log.warn("[Snapshot] {} - No data collected.", strategyType.name());
        }
    }

    /**
     * 스캘핑(모멘텀) 전략 데이터 수집
     * - 거래량 급증 + 양봉 종목
     * - 점수: 거래량 비율 기반 (최대 100점)
     */
    private List<AiStrategySnapshot> collectScalpingData(LocalDateTime createdAt) {
        List<ScreenerResultDto> momentum = quantScreenerService.getMomentumStocks(SNAPSHOT_LIMIT);
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
     */
    private List<AiStrategySnapshot> collectSwingData(LocalDateTime createdAt) {
        List<ScreenerResultDto> magicFormula = quantScreenerService.getMagicFormulaStocks(SNAPSHOT_LIMIT, null);
        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        int rank = 1;
        for (ScreenerResultDto dto : magicFormula) {
            // 점수 계산: ROE와 영업이익률 기반
            int score = calculateSwingScore(dto.getRoe(), dto.getOperatingMargin(), dto.getPer());

            // 추천 사유 생성
            String reason = generateSwingReason(dto.getRoe(), dto.getOperatingMargin(), dto.getPer());

            AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                    .strategyType(StrategyType.SWING)
                    .stockCode(dto.getStockCode())
                    .stockName(dto.getStockName())
                    .currentPrice(dto.getCurrentPrice())
                    .changeRate(null) // 마법의 공식은 등락률 없음
                    .score(score)
                    .reason(reason)
                    .rankNum(rank++)
                    .per(dto.getPer())
                    .pbr(dto.getPbr())
                    .roe(dto.getRoe())
                    .operatingMargin(dto.getOperatingMargin())
                    .magicFormulaRank(dto.getMagicFormulaRank())
                    .marketCap(dto.getMarketCap())
                    .createdAt(createdAt)
                    .build();

            snapshots.add(snapshot);
        }

        return snapshots;
    }

    /**
     * 턴어라운드 전략 데이터 수집
     * - 적자→흑자 전환 또는 이익 급증 종목
     * - 점수: 순이익 변화율 기반
     */
    private List<AiStrategySnapshot> collectTurnaroundData(LocalDateTime createdAt) {
        List<ScreenerResultDto> turnaround = quantScreenerService.getTurnaroundStocks(SNAPSHOT_LIMIT);
        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        int rank = 1;
        for (ScreenerResultDto dto : turnaround) {
            // 점수 계산: 턴어라운드 유형 및 변화율 기반
            int score = calculateTurnaroundScore(dto.getTurnaroundType(), dto.getNetIncomeChangeRate());

            // 추천 사유 생성
            String reason = generateTurnaroundReason(dto.getTurnaroundType(), dto.getNetIncomeChangeRate());

            AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                    .strategyType(StrategyType.TURNAROUND)
                    .stockCode(dto.getStockCode())
                    .stockName(dto.getStockName())
                    .currentPrice(dto.getCurrentPrice())
                    .changeRate(null)
                    .score(score)
                    .reason(reason)
                    .rankNum(rank++)
                    .per(dto.getPer())
                    .pbr(dto.getPbr())
                    .roe(dto.getRoe())
                    .turnaroundType(dto.getTurnaroundType())
                    .netIncomeChangeRate(dto.getNetIncomeChangeRate())
                    .marketCap(dto.getMarketCap())
                    .createdAt(createdAt)
                    .build();

            snapshots.add(snapshot);
        }

        return snapshots;
    }

    /**
     * 가치투자(PEG) 전략 데이터 수집
     * - 저평가 성장주 (PEG < 1.0)
     * - 점수: PEG 기반 (낮을수록 높은 점수)
     */
    private List<AiStrategySnapshot> collectValueData(LocalDateTime createdAt) {
        List<ScreenerResultDto> lowPeg = quantScreenerService.getLowPegStocks(
                new BigDecimal("1.0"), new BigDecimal("10"), SNAPSHOT_LIMIT);
        List<AiStrategySnapshot> snapshots = new ArrayList<>();

        int rank = 1;
        for (ScreenerResultDto dto : lowPeg) {
            // 점수 계산: PEG, ROE, PER 기반
            int score = calculateValueScore(dto.getPeg(), dto.getRoe(), dto.getPer());

            // 추천 사유 생성
            String reason = generateValueReason(dto.getPeg(), dto.getEpsGrowth(), dto.getRoe());

            AiStrategySnapshot snapshot = AiStrategySnapshot.builder()
                    .strategyType(StrategyType.VALUE)
                    .stockCode(dto.getStockCode())
                    .stockName(dto.getStockName())
                    .currentPrice(dto.getCurrentPrice())
                    .changeRate(null)
                    .score(score)
                    .reason(reason)
                    .rankNum(rank++)
                    .per(dto.getPer())
                    .pbr(dto.getPbr())
                    .roe(dto.getRoe())
                    .peg(dto.getPeg())
                    .epsGrowth(dto.getEpsGrowth())
                    .marketCap(dto.getMarketCap())
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

            List<AiStrategySnapshotDto> dtos = snapshots.stream()
                    .map(AiStrategySnapshotDto::fromEntity)
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

        return snapshots.stream()
                .map(AiStrategySnapshotDto::fromEntity)
                .collect(Collectors.toList());
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
