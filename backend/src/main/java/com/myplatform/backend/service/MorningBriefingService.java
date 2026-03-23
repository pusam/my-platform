package com.myplatform.backend.service;

import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.MarketTimingDto;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.dto.WatchlistDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 모닝 브리핑 서비스
 * - 매일 07:30 장 시작 전 텔레그램으로 전일 시장 요약 발송
 * - 시장 상태, 외국인/기관 연속매수, 관심종목, 마법의 공식 종목 정보 포함
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MorningBriefingService {

    private final InvestorTradeService investorTradeService;
    private final WatchlistService watchlistService;
    private final MarketTimingService marketTimingService;
    private final QuantScreenerService quantScreenerService;
    private final TelegramNotificationService telegramNotificationService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String DEFAULT_USERNAME = "admin";

    /**
     * 모닝 브리핑 발송
     */
    public void sendMorningBriefing() {
        log.info("=== 모닝 브리핑 생성 시작 ===");

        StringBuilder sb = new StringBuilder();
        String today = LocalDate.now().format(DATE_FORMATTER);

        sb.append(String.format("<b>\uD83C\uDF05 모닝 브리핑 (%s)</b>\n\n", today));

        // 1. 시장 상태 요약
        appendMarketStatus(sb);

        // 2. 외국인 연속 매수 TOP 5
        appendConsecutiveBuys(sb, "FOREIGN", "외국인", "\uD83C\uDFE6");

        // 3. 기관 연속 매수 TOP 5
        appendConsecutiveBuys(sb, "INSTITUTION", "기관", "\uD83C\uDFDB\uFE0F");

        // 4. 관심종목 현황
        appendWatchlist(sb);

        // 5. 마법의 공식 TOP 3
        appendMagicFormula(sb);

        // 푸터
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("\uD83E\uDD16 MyPlatform 모닝 브리핑");

        String message = sb.toString();
        telegramNotificationService.sendMessage(message);

        log.info("=== 모닝 브리핑 발송 완료 ===");
    }

    /**
     * 시장 상태 요약 섹션
     */
    private void appendMarketStatus(StringBuilder sb) {
        try {
            MarketTimingDto timing = marketTimingService.getCurrentMarketTiming();
            sb.append("\uD83D\uDCCA <b>시장 상태</b>\n");

            if (timing != null && timing.getCombinedAdr() != null) {
                String conditionLabel = timing.getOverallCondition() != null
                        ? timing.getOverallCondition().getLabel()
                        : "데이터 없음";
                sb.append(String.format("ADR: %.1f | 상태: %s\n",
                        timing.getCombinedAdr(), conditionLabel));

                if (timing.getDiagnosis() != null) {
                    sb.append(String.format("💬 %s\n", timing.getDiagnosis()));
                }
            } else {
                sb.append("데이터 없음\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.warn("모닝 브리핑 - 시장 상태 조회 실패: {}", e.getMessage());
            sb.append("\uD83D\uDCCA <b>시장 상태</b>\n");
            sb.append("조회 실패\n\n");
        }
    }

    /**
     * 연속 매수 종목 섹션
     */
    private void appendConsecutiveBuys(StringBuilder sb, String investorType, String investorLabel, String emoji) {
        try {
            List<ConsecutiveBuyDto> stocks = investorTradeService.getConsecutiveBuyStocks(investorType, 3);
            sb.append(String.format("%s <b>%s 연속 매수 TOP 5</b>\n", emoji, investorLabel));

            if (stocks != null && !stocks.isEmpty()) {
                int count = Math.min(stocks.size(), 5);
                for (int i = 0; i < count; i++) {
                    ConsecutiveBuyDto stock = stocks.get(i);
                    sb.append(String.format("%d. %s (%s) - %d일 연속",
                            i + 1, stock.getStockName(), stock.getStockCode(), stock.getConsecutiveDays()));

                    if (stock.getTotalNetBuyAmount() != null) {
                        sb.append(String.format(" (누적 %s억)", formatAmount(stock.getTotalNetBuyAmount())));
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("해당 종목 없음\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.warn("모닝 브리핑 - {} 연속매수 조회 실패: {}", investorLabel, e.getMessage());
            sb.append(String.format("%s <b>%s 연속 매수 TOP 5</b>\n", emoji, investorLabel));
            sb.append("조회 실패\n\n");
        }
    }

    /**
     * 관심종목 현황 섹션
     */
    private void appendWatchlist(StringBuilder sb) {
        try {
            List<WatchlistDto.WatchlistItem> items = watchlistService.getWatchlist(DEFAULT_USERNAME);
            sb.append("⭐ <b>관심종목</b>\n");

            if (items != null && !items.isEmpty()) {
                for (WatchlistDto.WatchlistItem item : items) {
                    sb.append(String.format("• %s (%s)", item.getStockName(), item.getStockCode()));

                    if (item.getCurrentPrice() != null) {
                        sb.append(String.format(" %s원", formatPrice(item.getCurrentPrice())));
                    }
                    if (item.getTargetPrice() != null) {
                        sb.append(String.format(" (목표가: %s)", formatPrice(item.getTargetPrice())));
                    }
                    if (item.getChangeRate() != null) {
                        sb.append(String.format(" %+.2f%%", item.getChangeRate()));
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("등록된 관심종목 없음\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.warn("모닝 브리핑 - 관심종목 조회 실패: {}", e.getMessage());
            sb.append("⭐ <b>관심종목</b>\n");
            sb.append("조회 실패\n\n");
        }
    }

    /**
     * 마법의 공식 TOP 3 섹션
     */
    private void appendMagicFormula(StringBuilder sb) {
        try {
            List<ScreenerResultDto> stocks = quantScreenerService.getMagicFormulaStocks(3, null);
            sb.append("✨ <b>마법의 공식 TOP 3</b>\n");

            if (stocks != null && !stocks.isEmpty()) {
                for (int i = 0; i < stocks.size(); i++) {
                    ScreenerResultDto stock = stocks.get(i);
                    sb.append(String.format("%d. %s (%s)", i + 1, stock.getStockName(), stock.getStockCode()));

                    if (stock.getPer() != null) {
                        sb.append(String.format(" PER %.1f", stock.getPer()));
                    }
                    if (stock.getRoe() != null) {
                        sb.append(String.format(" ROE %.1f%%", stock.getRoe()));
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("해당 종목 없음\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.warn("모닝 브리핑 - 마법의 공식 조회 실패: {}", e.getMessage());
            sb.append("✨ <b>마법의 공식 TOP 3</b>\n");
            sb.append("조회 실패\n\n");
        }
    }

    /**
     * 가격 포맷팅 (천 단위 콤마)
     */
    private String formatPrice(BigDecimal price) {
        if (price == null) return "N/A";
        return String.format("%,.0f", price);
    }

    /**
     * 금액 포맷팅 (소수점 1자리)
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "0";
        return String.format("%,.1f", amount);
    }
}
