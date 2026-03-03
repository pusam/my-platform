package com.myplatform.backend.scheduler;

import com.myplatform.backend.dto.PaperTradingDto.AccountSummaryDto;
import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.entity.AiStrategySnapshot;
import com.myplatform.backend.entity.AiStrategySnapshot.StrategyType;
import com.myplatform.backend.repository.AiStrategySnapshotRepository;
import com.myplatform.backend.service.TelegramNotificationService;
import com.myplatform.backend.service.VirtualTradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 일일 포트폴리오 리포트 스케줄러
 * - 매일 장 마감 후 (16:00) 포트폴리오 요약을 텔레그램으로 발송
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DailyReportScheduler {

    private final VirtualTradeService virtualTradeService;
    private final AiStrategySnapshotRepository aiStrategySnapshotRepository;
    private final TelegramNotificationService telegramNotificationService;

    @Value("${alert.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    /**
     * 매일 평일 16:00 장 마감 후 일일 리포트 발송
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
    public void sendDailyReport() {
        if (!schedulerEnabled) {
            log.debug("스케줄러 비활성화 상태 - 일일 리포트 생략");
            return;
        }

        log.info("=== 일일 포트폴리오 리포트 시작 ===");

        try {
            String report = buildDailyReport();
            telegramNotificationService.sendMessage(report);
            log.info("=== 일일 포트폴리오 리포트 발송 완료 ===");
        } catch (Exception e) {
            log.error("일일 포트폴리오 리포트 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 수동 트리거 (테스트용)
     */
    public String buildDailyReport() {
        StringBuilder sb = new StringBuilder();

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd (E)"));
        sb.append(String.format("<b>📊 일일 포트폴리오 리포트</b>\n"));
        sb.append(String.format("📅 %s\n\n", today));

        // 1. 계좌 요약
        try {
            AccountSummaryDto summary = virtualTradeService.getAccountSummary();
            sb.append("<b>💰 계좌 요약</b>\n");
            sb.append(String.format("• 총 자산: <b>%s원</b>\n", formatPrice(summary.getCurrentBalance().add(summary.getTotalEvaluation()))));
            sb.append(String.format("• 현금: %s원\n", formatPrice(summary.getCurrentBalance())));
            sb.append(String.format("• 투자금: %s원\n", formatPrice(summary.getTotalInvested())));
            sb.append(String.format("• 평가금: %s원\n", formatPrice(summary.getTotalEvaluation())));

            String profitSign = summary.getTotalProfitLoss().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
            sb.append(String.format("• 총 손익: <b>%s%s원 (%s%s%%)</b>\n",
                    profitSign, formatPrice(summary.getTotalProfitLoss()),
                    profitSign, summary.getTotalProfitRate().setScale(2).toPlainString()));

            if (summary.getTotalTradeCount() > 0) {
                sb.append(String.format("• 승률: %s%% (%d승 %d패)\n",
                        summary.getWinRate().setScale(1).toPlainString(),
                        summary.getWinCount(), summary.getLoseCount()));
            }
            sb.append("\n");

            // 2. 보유 종목
            List<PortfolioItemDto> portfolio = virtualTradeService.getPortfolio();
            if (!portfolio.isEmpty()) {
                sb.append(String.format("<b>📈 보유 종목 (%d개)</b>\n", portfolio.size()));
                for (PortfolioItemDto item : portfolio) {
                    String itemSign = item.getProfitRate().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                    sb.append(String.format("• %s: %s원 (%s%s%%)\n",
                            item.getStockName(),
                            formatPrice(item.getCurrentPrice()),
                            itemSign, item.getProfitRate().setScale(2).toPlainString()));
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.warn("포트폴리오 조회 실패: {}", e.getMessage());
            sb.append("💰 포트폴리오 데이터 없음\n\n");
        }

        // 3. AI 전략 TOP 추천
        try {
            sb.append("<b>🤖 AI 오늘의 추천</b>\n");
            boolean hasRecommendation = false;

            for (StrategyType type : StrategyType.values()) {
                List<AiStrategySnapshot> snapshots = aiStrategySnapshotRepository.findLatestByStrategyType(type);
                if (!snapshots.isEmpty()) {
                    AiStrategySnapshot top = snapshots.get(0);
                    String label = getStrategyLabel(type);
                    sb.append(String.format("• %s 1위: <b>%s</b> (AI %d점)\n",
                            label, top.getStockName(), top.getScore()));
                    hasRecommendation = true;
                }
            }

            if (!hasRecommendation) {
                sb.append("• 오늘의 추천 데이터 없음\n");
            }
        } catch (Exception e) {
            log.warn("AI 전략 조회 실패: {}", e.getMessage());
        }

        sb.append("\n━━━━━━━━━━━━━━━━\n");
        sb.append("🤖 MyPlatform 일일 리포트");

        return sb.toString();
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) return "0";
        return String.format("%,.0f", price);
    }

    private String getStrategyLabel(StrategyType type) {
        return switch (type) {
            case SCALPING -> "⚡ 스캘핑";
            case SWING -> "📈 스윙";
            case TURNAROUND -> "🔄 턴어라운드";
            case VALUE -> "💎 가치투자";
        };
    }
}
