package com.myplatform.backend.service;

import com.myplatform.backend.dto.EarningSurpriseDto;
import com.myplatform.backend.dto.EarningSurpriseDto.SurpriseType;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 어닝 서프라이즈 감지 서비스
 * - 최근 2분기 재무 데이터 비교
 * - 영업이익 전분기 대비 20%+ 증가 = 포지티브 서프라이즈
 * - 영업이익 전분기 대비 20%+ 감소 = 네거티브 서프라이즈
 * - 적자→흑자 전환도 서프라이즈로 감지
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EarningSurpriseService {

    private final StockFinancialDataRepository financialDataRepository;
    private final TelegramNotificationService telegramService;

    // 서프라이즈 임계값: 영업이익 변화율 ±20%
    private static final BigDecimal SURPRISE_THRESHOLD = new BigDecimal("20");
    private static final BigDecimal NEGATIVE_SURPRISE_THRESHOLD = new BigDecimal("-20");

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    // 캐시: 일별 갱신
    private volatile List<EarningSurpriseDto> cachedSurprises = new ArrayList<>();
    private volatile LocalDate cacheDate = null;

    /**
     * 어닝 서프라이즈 감지 (분기 비교)
     * - 최근 2분기 데이터 비교
     * - 영업이익 전분기 대비 20%+ 증가 = 포지티브 서프라이즈
     * - 영업이익 전분기 대비 20%- 감소 = 네거티브 서프라이즈
     * - 적자→흑자 전환도 서프라이즈로 감지
     */
    public List<EarningSurpriseDto> detectEarningSurprises() {
        // 당일 캐시 사용
        if (cacheDate != null && cacheDate.equals(LocalDate.now()) && !cachedSurprises.isEmpty()) {
            log.debug("[어닝서프라이즈] 캐시 사용 - {}건", cachedSurprises.size());
            return cachedSurprises;
        }

        log.info("[어닝서프라이즈] 감지 시작");
        List<EarningSurpriseDto> surprises = new ArrayList<>();

        try {
            // 최근 2개 분기 데이터 조회
            List<StockFinancialData> allData = financialDataRepository.findLatestTwoQuartersPerStock();

            if (allData == null || allData.isEmpty()) {
                log.warn("[어닝서프라이즈] 재무 데이터 없음");
                return surprises;
            }

            // 종목별 그룹화
            Map<String, List<StockFinancialData>> groupedByStock = allData.stream()
                    .collect(Collectors.groupingBy(StockFinancialData::getStockCode));

            for (Map.Entry<String, List<StockFinancialData>> entry : groupedByStock.entrySet()) {
                List<StockFinancialData> quarters = entry.getValue();

                // 2개 분기 데이터가 있어야 비교 가능
                if (quarters.size() < 2) continue;

                // reportDate 내림차순 정렬 (최신이 먼저)
                quarters.sort((a, b) -> b.getReportDate().compareTo(a.getReportDate()));

                StockFinancialData latest = quarters.get(0);
                StockFinancialData previous = quarters.get(1);

                EarningSurpriseDto surprise = analyzeQuarters(latest, previous);
                if (surprise != null) {
                    surprises.add(surprise);
                }
            }

            // 영업이익 변화율 절대값 기준 내림차순 정렬
            surprises.sort((a, b) -> {
                BigDecimal absA = a.getOperatingProfitChangeRate() != null
                        ? a.getOperatingProfitChangeRate().abs() : BigDecimal.ZERO;
                BigDecimal absB = b.getOperatingProfitChangeRate() != null
                        ? b.getOperatingProfitChangeRate().abs() : BigDecimal.ZERO;
                return absB.compareTo(absA);
            });

            // 캐시 갱신
            cachedSurprises = surprises;
            cacheDate = LocalDate.now();

            log.info("[어닝서프라이즈] 감지 완료 - 총 {}건 (포지티브: {}, 네거티브: {}, 턴어라운드: {})",
                    surprises.size(),
                    surprises.stream().filter(s -> s.getSurpriseType() == SurpriseType.POSITIVE).count(),
                    surprises.stream().filter(s -> s.getSurpriseType() == SurpriseType.NEGATIVE).count(),
                    surprises.stream().filter(s -> s.getSurpriseType() == SurpriseType.TURNAROUND).count());

        } catch (Exception e) {
            log.error("[어닝서프라이즈] 감지 실패: {}", e.getMessage(), e);
        }

        return surprises;
    }

    /**
     * 두 분기 데이터 비교 분석
     */
    private EarningSurpriseDto analyzeQuarters(StockFinancialData latest, StockFinancialData previous) {
        BigDecimal latestOp = latest.getOperatingProfit();
        BigDecimal prevOp = previous.getOperatingProfit();
        BigDecimal latestNet = latest.getNetIncome();
        BigDecimal prevNet = previous.getNetIncome();
        BigDecimal latestRev = latest.getRevenue();
        BigDecimal prevRev = previous.getRevenue();

        // 영업이익이 둘 다 없으면 비교 불가
        if (latestOp == null && latestNet == null) return null;

        SurpriseType surpriseType = null;
        BigDecimal opChangeRate = null;
        BigDecimal netChangeRate = null;
        BigDecimal revChangeRate = null;
        String summary;

        // 1. 적자→흑자 전환 체크 (영업이익 기준)
        if (latestOp != null && prevOp != null
                && prevOp.compareTo(BigDecimal.ZERO) < 0
                && latestOp.compareTo(BigDecimal.ZERO) > 0) {
            surpriseType = SurpriseType.TURNAROUND;
            opChangeRate = calculateChangeRate(latestOp, prevOp);
            summary = String.format("영업이익 적자→흑자 전환! (%.0f억 → %.0f억)", prevOp, latestOp);
        }
        // 2. 영업이익 변화율 계산
        else if (latestOp != null && prevOp != null
                && prevOp.compareTo(BigDecimal.ZERO) != 0) {
            opChangeRate = calculateChangeRate(latestOp, prevOp);

            if (opChangeRate.compareTo(SURPRISE_THRESHOLD) >= 0) {
                surpriseType = SurpriseType.POSITIVE;
                summary = String.format("영업이익 %.1f%% 증가 (%.0f억 → %.0f억)",
                        opChangeRate, prevOp, latestOp);
            } else if (opChangeRate.compareTo(NEGATIVE_SURPRISE_THRESHOLD) <= 0) {
                surpriseType = SurpriseType.NEGATIVE;
                summary = String.format("영업이익 %.1f%% 감소 (%.0f억 → %.0f억)",
                        opChangeRate, prevOp, latestOp);
            } else {
                return null; // 임계값 미달
            }
        }
        // 3. 영업이익 없으면 순이익으로 대체
        else if (latestNet != null && prevNet != null) {
            // 적자→흑자 전환 (순이익 기준)
            if (prevNet.compareTo(BigDecimal.ZERO) < 0
                    && latestNet.compareTo(BigDecimal.ZERO) > 0) {
                surpriseType = SurpriseType.TURNAROUND;
                netChangeRate = calculateChangeRate(latestNet, prevNet);
                summary = String.format("순이익 적자→흑자 전환! (%.0f억 → %.0f억)", prevNet, latestNet);
            } else if (prevNet.compareTo(BigDecimal.ZERO) != 0) {
                netChangeRate = calculateChangeRate(latestNet, prevNet);
                if (netChangeRate.compareTo(SURPRISE_THRESHOLD) >= 0) {
                    surpriseType = SurpriseType.POSITIVE;
                    summary = String.format("순이익 %.1f%% 증가 (%.0f억 → %.0f억)",
                            netChangeRate, prevNet, latestNet);
                } else if (netChangeRate.compareTo(NEGATIVE_SURPRISE_THRESHOLD) <= 0) {
                    surpriseType = SurpriseType.NEGATIVE;
                    summary = String.format("순이익 %.1f%% 감소 (%.0f억 → %.0f억)",
                            netChangeRate, prevNet, latestNet);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        } else {
            return null;
        }

        // 순이익 변화율 (아직 계산 안 한 경우)
        if (netChangeRate == null && latestNet != null && prevNet != null
                && prevNet.compareTo(BigDecimal.ZERO) != 0) {
            netChangeRate = calculateChangeRate(latestNet, prevNet);
        }

        // 매출 변화율
        if (latestRev != null && prevRev != null && prevRev.compareTo(BigDecimal.ZERO) != 0) {
            revChangeRate = calculateChangeRate(latestRev, prevRev);
        }

        return EarningSurpriseDto.builder()
                .stockCode(latest.getStockCode())
                .stockName(latest.getStockName())
                .market(latest.getMarket())
                .latestOperatingProfit(latestOp)
                .previousOperatingProfit(prevOp)
                .operatingProfitChangeRate(opChangeRate)
                .latestNetIncome(latestNet)
                .previousNetIncome(prevNet)
                .netIncomeChangeRate(netChangeRate)
                .latestRevenue(latestRev)
                .revenueChangeRate(revChangeRate)
                .latestReportDate(latest.getReportDate())
                .previousReportDate(previous.getReportDate())
                .surpriseType(surpriseType)
                .summary(summary)
                .build();
    }

    /**
     * 변화율 계산 (%)
     */
    private BigDecimal calculateChangeRate(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return current.subtract(previous)
                .divide(previous.abs(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 어닝 서프라이즈 텔레그램 알림
     */
    public void sendEarningSurpriseAlert() {
        log.info("[어닝서프라이즈] 텔레그램 알림 시작");

        List<EarningSurpriseDto> surprises = detectEarningSurprises();

        // 포지티브 + 턴어라운드만 알림
        List<EarningSurpriseDto> positiveSurprises = surprises.stream()
                .filter(s -> s.getSurpriseType() == SurpriseType.POSITIVE
                        || s.getSurpriseType() == SurpriseType.TURNAROUND)
                .limit(10)
                .collect(Collectors.toList());

        if (positiveSurprises.isEmpty()) {
            log.info("[어닝서프라이즈] 포지티브 서프라이즈 없음 - 알림 생략");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<b>\uD83C\uDFAF 어닝 서프라이즈 감지</b>\n\n");

        int idx = 1;
        for (EarningSurpriseDto s : positiveSurprises) {
            String typeEmoji = s.getSurpriseType() == SurpriseType.TURNAROUND ? "\uD83D\uDD04" : "\uD83D\uDCC8";
            sb.append(String.format("%s <b>%d. %s</b> (%s)\n",
                    typeEmoji, idx++, s.getStockName(), s.getStockCode()));
            sb.append(String.format("   %s\n", s.getSummary()));

            if (s.getRevenueChangeRate() != null) {
                sb.append(String.format("   매출 변화: %+.1f%%\n", s.getRevenueChangeRate()));
            }
            sb.append("\n");
        }

        sb.append(String.format("⏰ %s\n", LocalDateTime.now().format(TIME_FORMATTER)));
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("\uD83E\uDD16 MyPlatform 어닝 서프라이즈 알림");

        telegramService.sendMessage(sb.toString());
        log.info("[어닝서프라이즈] 텔레그램 알림 발송 - {}건", positiveSurprises.size());
    }

    /**
     * 서프라이즈 종목의 stockCode Set 반환 (전략 스코어 연동용)
     */
    public Set<String> getPositiveSurpriseStockCodes() {
        return detectEarningSurprises().stream()
                .filter(s -> s.getSurpriseType() == SurpriseType.POSITIVE
                        || s.getSurpriseType() == SurpriseType.TURNAROUND)
                .map(EarningSurpriseDto::getStockCode)
                .collect(Collectors.toSet());
    }

    /**
     * 서프라이즈 유형별 stockCode Map 반환 (전략 스코어 세분화용)
     */
    public Map<String, SurpriseType> getSurpriseTypeMap() {
        return detectEarningSurprises().stream()
                .filter(s -> s.getSurpriseType() == SurpriseType.POSITIVE
                        || s.getSurpriseType() == SurpriseType.TURNAROUND)
                .collect(Collectors.toMap(
                        EarningSurpriseDto::getStockCode,
                        EarningSurpriseDto::getSurpriseType,
                        (a, b) -> a // 중복 시 첫 번째 유지
                ));
    }

    /**
     * 캐시 강제 갱신
     */
    public void refreshCache() {
        cacheDate = null;
        cachedSurprises = new ArrayList<>();
        detectEarningSurprises();
    }
}
