package com.myplatform.backend.service;

import com.myplatform.backend.dto.NewsSummaryDto;
import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.dto.StockPriceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실전 보유 종목 급락 감지 + AI 이유 요약 + 텔레그램 알림.
 *
 * 흐름:
 *   1) 장중 2분마다 실전 포트폴리오 조회
 *   2) 손익률 -3% 이하 + 쿨다운(30분) 통과한 종목 선별
 *   3) 최근 6시간 뉴스 중 해당 종목명이 제목/요약에 포함된 것 추림
 *   4) Gemini 에게 "왜 떨어졌나" 한 줄 요약 요청
 *   5) 텔레그램 리스크 채널로 발송
 *
 * 이 서비스는 프론트 요청과 무관 — 백그라운드 모니터로만 동작.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PositionDropMonitorService {

    private static final double DROP_THRESHOLD_PCT = -3.0;  // -3% 이하
    private static final Duration COOLDOWN = Duration.ofMinutes(30);
    private static final int NEWS_LOOKBACK_HOURS = 6;

    private final RealTradeService realTradeService;
    private final StockPriceService stockPriceService;
    private final NewsService newsService;
    private final GeminiService geminiService;
    private final TelegramNotificationService telegramService;
    private final KoreaInvestmentService kisService;

    // 종목별 마지막 알림 시각 — 서버 재기동 시 초기화 (쿨다운 리셋은 허용)
    private final Map<String, LocalDateTime> lastAlertTime = new ConcurrentHashMap<>();

    /**
     * 장중 2분마다 점검. 장외에는 동작 안 함.
     */
    @Scheduled(cron = "0 */2 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void checkDrops() {
        if (!kisService.isRealTradingConfigured()) return;

        try {
            List<PortfolioItemDto> portfolio = realTradeService.getPortfolio();
            if (portfolio == null || portfolio.isEmpty()) return;

            for (PortfolioItemDto item : portfolio) {
                evaluateAndAlert(item);
            }
        } catch (Exception e) {
            log.warn("[PositionDrop] 급락 감지 실패: {}", e.getMessage());
        }
    }

    private void evaluateAndAlert(PortfolioItemDto item) {
        BigDecimal rate = item.getProfitRate();
        if (rate == null || rate.doubleValue() > DROP_THRESHOLD_PCT) return;

        // 쿨다운 체크 — 같은 종목에 30분 간격 이상으로만 알림
        String code = item.getStockCode();
        LocalDateTime last = lastAlertTime.get(code);
        if (last != null && Duration.between(last, LocalDateTime.now()).compareTo(COOLDOWN) < 0) {
            return;
        }

        try {
            // 현재가 보강 — 포트폴리오 DTO 에 currentPrice 없을 수 있음
            BigDecimal currentPrice = item.getCurrentPrice();
            BigDecimal dailyChangeRate = null;
            if (currentPrice == null || currentPrice.signum() == 0) {
                StockPriceDto price = stockPriceService.getStockPrice(code);
                if (price != null) {
                    currentPrice = price.getCurrentPrice();
                    dailyChangeRate = price.getChangeRate();
                }
            }

            String reason = buildReason(item, currentPrice, dailyChangeRate);
            telegramService.sendRisk(reason);
            lastAlertTime.put(code, LocalDateTime.now());
            log.info("[PositionDrop] 급락 알림 발송: {} ({}) 손익률 {}%",
                    item.getStockName(), code, rate);
        } catch (Exception e) {
            log.warn("[PositionDrop] {} 알림 실패: {}", code, e.getMessage());
        }
    }

    private String buildReason(PortfolioItemDto item, BigDecimal currentPrice, BigDecimal dailyRate) {
        String stockName = item.getStockName() != null ? item.getStockName() : item.getStockCode();
        BigDecimal rate = item.getProfitRate();

        StringBuilder msg = new StringBuilder();
        msg.append("🔴 <b>보유 종목 급락</b>\n\n");
        msg.append(String.format("<b>%s</b> (%s)\n", stockName, item.getStockCode()));
        if (currentPrice != null) {
            msg.append(String.format("현재가: %,d원\n", currentPrice.longValue()));
        }
        msg.append(String.format("손익률: %.2f%%", rate.doubleValue()));
        if (dailyRate != null) {
            msg.append(String.format(" (당일 %+.2f%%)", dailyRate.doubleValue()));
        }
        msg.append("\n");
        if (item.getQuantity() != null) {
            msg.append(String.format("보유수량: %d주", item.getQuantity()));
            if (item.getProfitLoss() != null) {
                msg.append(String.format(", 평가손익: %,d원",
                        item.getProfitLoss().longValue()));
            }
            msg.append("\n");
        }

        // 뉴스 기반 이유 요약
        String newsReason = summarizeRelatedNews(stockName);
        if (newsReason != null && !newsReason.isBlank()) {
            msg.append("\n📰 <b>추정 이유</b>\n").append(newsReason);
        } else {
            msg.append("\n📰 관련 최근 뉴스 없음 — 시장 전반 요인 가능");
        }

        return msg.toString();
    }

    /**
     * 종목명이 제목/요약에 포함된 최근 뉴스를 찾아 Gemini 로 한 줄 요약.
     * 뉴스 없으면 null. Gemini 실패 시 뉴스 제목만 3개 반환.
     */
    private String summarizeRelatedNews(String stockName) {
        if (stockName == null || stockName.isBlank()) return null;
        try {
            LocalDateTime since = LocalDateTime.now().minusHours(NEWS_LOOKBACK_HOURS);
            List<NewsSummaryDto> all = newsService.getNewsSince(since);
            if (all == null || all.isEmpty()) return null;

            List<NewsSummaryDto> related = new ArrayList<>();
            for (NewsSummaryDto n : all) {
                String title = n.getTitle();
                String summary = n.getSummary();
                if ((title != null && title.contains(stockName))
                        || (summary != null && summary.contains(stockName))) {
                    related.add(n);
                    if (related.size() >= 5) break;
                }
            }
            if (related.isEmpty()) return null;

            // Gemini 한 줄 요약 시도
            StringBuilder promptBody = new StringBuilder();
            promptBody.append("종목: ").append(stockName).append("\n최근 관련 뉴스:\n");
            for (int i = 0; i < related.size(); i++) {
                promptBody.append(i + 1).append(". ").append(related.get(i).getTitle());
                if (related.get(i).getSummary() != null) {
                    String s = related.get(i).getSummary();
                    if (s.length() > 120) s = s.substring(0, 120) + "...";
                    promptBody.append(" - ").append(s);
                }
                promptBody.append("\n");
            }
            promptBody.append("""

                    위 뉴스를 바탕으로 이 종목이 급락한 추정 이유를 한국어로 2문장 이내로 요약.
                    뉴스가 주가 하락과 직접 관련이 없으면 "직접적 악재 없음, 시장 전반 요인 가능" 이라고만 답변.
                    """);

            String ai = geminiService.chat(promptBody.toString());
            if (ai != null && !ai.isBlank()) return ai.trim();
        } catch (Exception e) {
            log.debug("[PositionDrop] 뉴스 요약 실패: {}", e.getMessage());
        }
        return null;
    }
}
