package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.AlertHistory;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.StockWatchlist;
import com.myplatform.backend.repository.AlertHistoryRepository;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import com.myplatform.core.util.DateTimeUtil;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 관심종목 실시간 리스크 알리미
 * - 장중 10분 주기로 관심종목 감시
 * - 4대 리스크 조건 감지 → 텔레그램 즉시 알림
 * - 종목당 1시간 쿨다운으로 중복 방지
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WatchlistRiskMonitorService {

    private final StockWatchlistRepository watchlistRepository;
    private final AlertHistoryRepository alertHistoryRepository;
    private final StockPriceService stockPriceService;
    private final DartService dartService;
    private final InvestorDailyTradeRepository investorTradeRepository;
    private final TelegramNotificationService telegramService;
    private final SchedulerLockService schedulerLockService;

    private static final int COOLDOWN_MINUTES = 60;
    private static final String ALERT_TYPE_RISK = "WATCHLIST_RISK";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // 리스크 레벨
    public enum RiskLevel {
        DANGER,   // 🔴 위험
        WARNING   // 🟡 주의
    }

    /**
     * 거래시간(NXT 8:00~20:00 + KRX 정규장) 10분 주기 리스크 감시
     */
    @Scheduled(scheduler = "cacheScheduler", cron = "0 0/10 8-19 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledRiskMonitor() {
        // 10분 cron — TTL 8분 으로 다음 cron 까지 락 풀림. monitorWatchlistRisks 는 public 이라 직접 호출도 가능 — 락은 cron 만 보호.
        if (!schedulerLockService.tryLock("watchlist-risk.monitor", Duration.ofMinutes(8))) {
            log.debug("관심종목 리스크 감시 다른 인스턴스에서 진행 중 — 스킵");
            return;
        }
        monitorWatchlistRisks();
    }

    /**
     * 관심종목 전체 리스크 스캔
     */
    public List<WatchlistRiskDto> monitorWatchlistRisks() {
        // 모든 사용자의 활성 관심종목 수집 (중복 제거)
        List<StockWatchlist> allWatchlist = watchlistRepository.findByIsActiveAndAlertTriggeredAndTargetPriceIsNotNull(true, false);
        // targetPrice null인 것도 포함해야 하므로 전체 활성 목록 조회 — DB 단 필터
        List<StockWatchlist> allActive = watchlistRepository.findByIsActiveTrue();

        Set<String> uniqueCodes = allActive.stream()
                .map(StockWatchlist::getStockCode)
                .collect(Collectors.toSet());

        if (uniqueCodes.isEmpty()) return Collections.emptyList();

        // 배치로 시세 조회
        Map<String, StockPriceDto> priceMap;
        try {
            priceMap = stockPriceService.getStockPrices(new ArrayList<>(uniqueCodes));
        } catch (Exception e) {
            log.warn("[리스크모니터] 시세 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }

        List<WatchlistRiskDto> allRisks = new ArrayList<>();

        for (String stockCode : uniqueCodes) {
            StockPriceDto price = priceMap.get(stockCode);
            String stockName = allActive.stream()
                    .filter(w -> stockCode.equals(w.getStockCode()))
                    .map(StockWatchlist::getStockName)
                    .findFirst().orElse(stockCode);

            List<RiskDetail> risks = new ArrayList<>();

            // ① DART 부정적 공시
            checkDartRisk(stockCode, stockName, risks);

            // ② 외국인 순매도 급전환
            checkForeignSelloff(stockCode, risks);

            // ③ 거래량 급감
            checkVolumeDrop(stockCode, price, risks);

            // ④ 주가 급락
            checkPriceDrop(stockCode, price, risks);

            if (!risks.isEmpty()) {
                RiskLevel level = risks.stream().anyMatch(r -> r.getLevel() == RiskLevel.DANGER)
                        ? RiskLevel.DANGER : RiskLevel.WARNING;

                WatchlistRiskDto dto = WatchlistRiskDto.builder()
                        .stockCode(stockCode)
                        .stockName(stockName)
                        .currentPrice(price != null ? price.getCurrentPrice() : null)
                        .changeRate(price != null ? price.getChangeRate() : null)
                        .riskLevel(level)
                        .risks(risks)
                        .detectedAt(DateTimeUtil.kstNow())
                        .build();

                allRisks.add(dto);
                sendRiskAlert(dto);
            }
        }

        if (!allRisks.isEmpty()) {
            log.info("[리스크모니터] 관심종목 리스크 감지 {}건", allRisks.size());
        }

        return allRisks;
    }

    /**
     * 관심종목의 현재 리스크 상태 조회 (프론트엔드용)
     */
    public Map<String, WatchlistRiskDto> getCurrentRiskStatus(List<String> stockCodes) {
        Map<String, WatchlistRiskDto> result = new HashMap<>();
        if (stockCodes == null || stockCodes.isEmpty()) return result;

        Map<String, StockPriceDto> priceMap;
        try {
            priceMap = stockPriceService.getStockPrices(stockCodes);
        } catch (Exception e) {
            return result;
        }

        for (String code : stockCodes) {
            StockPriceDto price = priceMap.get(code);
            List<RiskDetail> risks = new ArrayList<>();

            checkVolumeDrop(code, price, risks);
            checkPriceDrop(code, price, risks);

            if (!risks.isEmpty()) {
                RiskLevel level = risks.stream().anyMatch(r -> r.getLevel() == RiskLevel.DANGER)
                        ? RiskLevel.DANGER : RiskLevel.WARNING;
                result.put(code, WatchlistRiskDto.builder()
                        .stockCode(code)
                        .riskLevel(level)
                        .risks(risks)
                        .build());
            }
        }
        return result;
    }

    // ==================== 4대 리스크 체크 ====================

    private void checkDartRisk(String stockCode, String stockName, List<RiskDetail> risks) {
        if (!dartService.isAvailable()) return;
        try {
            List<RiskAnalysisDto.DartDisclosure> disclosures = dartService.searchDisclosuresByStockCode(stockCode, stockName);
            List<RiskAnalysisDto.DartDisclosure> dangerous = dartService.filterDangerousDisclosures(disclosures);

            if (!dangerous.isEmpty()) {
                RiskAnalysisDto.DartDisclosure first = dangerous.get(0);
                risks.add(RiskDetail.builder()
                        .type("DART_DANGER")
                        .level(RiskLevel.DANGER)
                        .message("부정적 공시: " + first.getReportNm())
                        .detail(first.getMatchedKeyword() != null ? "키워드: " + first.getMatchedKeyword() : null)
                        .build());
            }
        } catch (Exception e) {
            log.debug("[리스크모니터] DART 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }
    }

    private void checkForeignSelloff(String stockCode, List<RiskDetail> risks) {
        try {
            LocalDate today = LocalDate.now();
            LocalDate twoDaysAgo = today.minusDays(3); // 주말 고려해서 3일

            List<InvestorDailyTrade> trades = investorTradeRepository
                    .findByStockCodeAndDateRange(stockCode, twoDaysAgo, today);

            if (trades.isEmpty()) return;

            // 외국인 매수(BUY) 데이터에서 순매수 금액 추출 (날짜별)
            Map<LocalDate, BigDecimal> foreignNetBuy = new HashMap<>();
            for (InvestorDailyTrade t : trades) {
                if ("FOREIGN".equals(t.getInvestorType()) && "BUY".equals(t.getTradeType())) {
                    foreignNetBuy.put(t.getTradeDate(), t.getNetBuyAmount());
                }
            }

            if (foreignNetBuy.size() < 2) return;

            // 날짜 정렬 (최신 순)
            List<LocalDate> dates = foreignNetBuy.keySet().stream()
                    .sorted(Comparator.reverseOrder())
                    .toList();

            BigDecimal todayAmount = foreignNetBuy.get(dates.get(0));    // 당일(최신)
            BigDecimal yesterdayAmount = foreignNetBuy.get(dates.get(1)); // 전일

            if (todayAmount == null || yesterdayAmount == null) return;

            // 전일 순매수(양수)였는데 당일 -50억 이하로 전환
            if (yesterdayAmount.compareTo(BigDecimal.ZERO) > 0
                    && todayAmount.compareTo(new BigDecimal("-50")) <= 0) {

                risks.add(RiskDetail.builder()
                        .type("FOREIGN_SELLOFF")
                        .level(RiskLevel.WARNING)
                        .message(String.format("외국인 순매도 급전환 %s억", todayAmount))
                        .detail(String.format("전일 +%s억 → 당일 %s억", yesterdayAmount, todayAmount))
                        .build());
            }
        } catch (Exception e) {
            log.debug("[리스크모니터] 외국인 매매 조회 실패 [{}]: {}", stockCode, e.getMessage());
        }
    }

    private void checkVolumeDrop(String stockCode, StockPriceDto price, List<RiskDetail> risks) {
        if (price == null || price.getVolume() == null || price.getPreviousDayVolume() == null) return;
        if (price.getPreviousDayVolume().compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal volumeRatio = price.getVolume()
                .divide(price.getPreviousDayVolume(), 4, java.math.RoundingMode.HALF_UP);

        // 전일 대비 30% 이하 (70% 급감)
        if (volumeRatio.compareTo(new BigDecimal("0.3")) <= 0) {
            int dropPercent = (int) ((1 - volumeRatio.doubleValue()) * 100);
            risks.add(RiskDetail.builder()
                    .type("VOLUME_DROP")
                    .level(RiskLevel.WARNING)
                    .message(String.format("거래량 급감 -%d%%", dropPercent))
                    .detail("전일 대비 거래량 " + (int)(volumeRatio.doubleValue() * 100) + "%")
                    .build());
        }
    }

    private void checkPriceDrop(String stockCode, StockPriceDto price, List<RiskDetail> risks) {
        if (price == null || price.getChangeRate() == null) return;

        BigDecimal changeRate = price.getChangeRate();

        if (changeRate.compareTo(new BigDecimal("-5")) <= 0) {
            risks.add(RiskDetail.builder()
                    .type("PRICE_CRASH")
                    .level(RiskLevel.DANGER)
                    .message(String.format("급락 %s%%", changeRate))
                    .detail("장중 -5% 이상 급락")
                    .build());
        } else if (changeRate.compareTo(new BigDecimal("-3")) <= 0) {
            risks.add(RiskDetail.builder()
                    .type("PRICE_DROP")
                    .level(RiskLevel.WARNING)
                    .message(String.format("하락 %s%%", changeRate))
                    .detail("장중 -3% 이상 하락")
                    .build());
        }
    }

    // ==================== 알림 발송 ====================

    private void sendRiskAlert(WatchlistRiskDto dto) {
        String alertKey = dto.getStockCode() + "_" + ALERT_TYPE_RISK;

        // 쿨다운 체크 (1시간)
        if (alertHistoryRepository.existsRecentAlert(alertKey,
                DateTimeUtil.kstNow().minusMinutes(COOLDOWN_MINUTES))) {
            return;
        }

        String emoji = dto.getRiskLevel() == RiskLevel.DANGER ? "🔴" : "🟡";
        String levelText = dto.getRiskLevel() == RiskLevel.DANGER ? "위험" : "주의";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<b>%s [%s] 리스크 감지 — %s</b>\n\n", emoji, levelText, dto.getStockName()));
        sb.append(String.format("📊 <b>%s</b> (%s)\n", dto.getStockName(), dto.getStockCode()));

        if (dto.getCurrentPrice() != null) {
            sb.append(String.format("💰 현재가: <b>%s원</b>", formatPrice(dto.getCurrentPrice())));
            if (dto.getChangeRate() != null) {
                sb.append(String.format(" (%s%s%%)",
                        dto.getChangeRate().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "",
                        dto.getChangeRate()));
            }
            sb.append("\n");
        }

        sb.append("\n🔍 <b>감지 항목</b>\n");
        for (RiskDetail risk : dto.getRisks()) {
            String riskEmoji = risk.getLevel() == RiskLevel.DANGER ? "🔴" : "🟡";
            sb.append(String.format("  %s %s\n", riskEmoji, risk.getMessage()));
            if (risk.getDetail() != null) {
                sb.append(String.format("    → %s\n", risk.getDetail()));
            }
        }

        sb.append(String.format("\n⏰ %s\n", DateTimeUtil.kstNow().format(TIME_FMT)));
        sb.append("━━━━━━━━━━━━━━━━\n🤖 MyPlatform 리스크 알리미");

        telegramService.sendRisk(sb.toString());

        // 알림 이력 저장
        AlertHistory history = new AlertHistory();
        history.setAlertKey(alertKey);
        history.setStockCode(dto.getStockCode());
        history.setStockName(dto.getStockName());
        history.setInvestorType("SYSTEM");
        history.setAlertType(ALERT_TYPE_RISK);
        history.setSentAt(DateTimeUtil.kstNow());
        alertHistoryRepository.save(history);

        log.info("[리스크모니터] 알림 발송: {} [{}] - {}",
                dto.getStockName(), dto.getRiskLevel(),
                dto.getRisks().stream().map(RiskDetail::getType).collect(Collectors.joining(",")));
    }

    private String formatPrice(BigDecimal price) {
        return String.format("%,.0f", price);
    }

    // ==================== DTO ====================

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WatchlistRiskDto {
        private String stockCode;
        private String stockName;
        private BigDecimal currentPrice;
        private BigDecimal changeRate;
        private RiskLevel riskLevel;
        private List<RiskDetail> risks;
        private LocalDateTime detectedAt;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RiskDetail {
        private String type;      // DART_DANGER, FOREIGN_SELLOFF, VOLUME_DROP, PRICE_DROP, PRICE_CRASH
        private RiskLevel level;
        private String message;
        private String detail;
    }
}
