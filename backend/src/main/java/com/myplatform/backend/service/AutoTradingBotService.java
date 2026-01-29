package com.myplatform.backend.service;

import com.myplatform.backend.dto.PaperTradingDto.*;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.entity.VirtualAccount;
import com.myplatform.backend.entity.VirtualPortfolio;
import com.myplatform.backend.repository.VirtualPortfolioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 자동 매매 봇 서비스
 * - 마법의 공식 상위 종목 자동 매수
 * - 손절/익절 자동 실행
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoTradingBotService {

    private final VirtualTradeService virtualTradeService;
    private final VirtualPortfolioRepository portfolioRepository;
    private final QuantScreenerService quantScreenerService;
    private final InvestorSurgeService investorSurgeService;
    private final StockPriceService stockPriceService;
    private final TelegramNotificationService telegramService;

    // 봇 상태
    private final AtomicBoolean botActive = new AtomicBoolean(false);
    private volatile LocalDateTime lastTradeTime;
    private volatile String lastError;
    private volatile LocalDateTime lastErrorTime;
    private final AtomicInteger todayBuyCount = new AtomicInteger(0);
    private final AtomicInteger todaySellCount = new AtomicInteger(0);
    private volatile LocalDate lastResetDate;

    // 손절/익절 기준
    private static final BigDecimal STOP_LOSS_RATE = new BigDecimal("-3"); // -3%
    private static final BigDecimal TAKE_PROFIT_RATE = new BigDecimal("5"); // +5%
    private static final BigDecimal MAX_INVESTMENT_RATIO = new BigDecimal("0.2"); // 종목당 최대 20%

    /**
     * 봇 시작
     */
    public BotStatusDto startBot() {
        if (botActive.get()) {
            log.info("자동매매 봇이 이미 실행 중입니다.");
            return getBotStatus();
        }

        botActive.set(true);
        resetDailyCounters();
        log.info("자동매매 봇 시작됨");

        // 텔레그램 알림
        if (telegramService.isEnabled()) {
            telegramService.sendMessage(
                    "<b>🤖 [모의투자] 자동매매 봇 시작!</b>\n\n" +
                    "✅ 봇이 활성화되었습니다.\n" +
                    "⏰ 매수: 평일 09:30\n" +
                    "⏰ 손절/익절 체크: 매분\n" +
                    "⏰ 장 마감 청산: 평일 15:20\n\n" +
                    "━━━━━━━━━━━━━━━━\n" +
                    "🤖 MyPlatform 모의투자"
            );
        }

        return getBotStatus();
    }

    /**
     * 봇 중지
     */
    public BotStatusDto stopBot() {
        if (!botActive.get()) {
            log.info("자동매매 봇이 이미 중지 상태입니다.");
            return getBotStatus();
        }

        botActive.set(false);
        log.info("자동매매 봇 중지됨");

        // 텔레그램 알림
        if (telegramService.isEnabled()) {
            telegramService.sendMessage(
                    "<b>🤖 [모의투자] 자동매매 봇 중지!</b>\n\n" +
                    "⏸️ 봇이 비활성화되었습니다.\n\n" +
                    "━━━━━━━━━━━━━━━━\n" +
                    "🤖 MyPlatform 모의투자"
            );
        }

        return getBotStatus();
    }

    /**
     * 봇 상태 조회
     */
    public BotStatusDto getBotStatus() {
        resetDailyCounters();

        String status;
        if (!botActive.get()) {
            status = "STOPPED";
        } else if (lastError != null && lastErrorTime != null &&
                   lastErrorTime.isAfter(LocalDateTime.now().minusMinutes(30))) {
            status = "ERROR";
        } else {
            status = "RUNNING";
        }

        return BotStatusDto.builder()
                .active(botActive.get())
                .lastTradeTime(lastTradeTime)
                .lastError(lastError)
                .lastErrorTime(lastErrorTime)
                .status(status)
                .todayBuyCount(todayBuyCount.get())
                .todaySellCount(todaySellCount.get())
                .build();
    }

    /**
     * 매수 로직 실행 (평일 09:30)
     */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Seoul")
    public void executeBuyLogic() {
        if (!botActive.get()) {
            log.debug("자동매매 봇이 비활성화 상태입니다.");
            return;
        }

        log.info("[자동매매] 매수 로직 실행 시작");
        resetDailyCounters();

        try {
            VirtualAccount account = virtualTradeService.getOrCreateActiveAccount();

            // 종목당 최대 투자금액 계산
            BigDecimal totalAsset = account.getCurrentBalance().add(account.getTotalEvaluation());
            BigDecimal maxPerStock = totalAsset.multiply(MAX_INVESTMENT_RATIO);

            // 마법의 공식 상위 종목 조회
            List<ScreenerResultDto> magicFormulaStocks = quantScreenerService.getMagicFormulaStocks(10, null);

            if (magicFormulaStocks.isEmpty()) {
                log.info("[자동매매] 마법의 공식 종목이 없습니다.");
                return;
            }

            // 외국인/기관 수급 확인
            Map<String, List<InvestorSurgeDto>> surgeStocks = null;
            try {
                surgeStocks = investorSurgeService.getAllSurgeStocks(new BigDecimal("30"));
            } catch (Exception e) {
                log.warn("[자동매매] 수급 데이터 조회 실패: {}", e.getMessage());
            }

            // 이미 보유 중인 종목 코드
            List<String> holdingCodes = portfolioRepository.findByAccountId(account.getId())
                    .stream()
                    .map(VirtualPortfolio::getStockCode)
                    .collect(Collectors.toList());

            int buyCount = 0;
            for (ScreenerResultDto stock : magicFormulaStocks) {
                // 잔액 확인
                if (account.getCurrentBalance().compareTo(new BigDecimal("100000")) < 0) {
                    log.info("[자동매매] 잔액 부족으로 매수 중단");
                    break;
                }

                // 이미 보유 중인 종목 제외
                if (holdingCodes.contains(stock.getStockCode())) {
                    continue;
                }

                // 수급 신호 확인 (외국인 또는 기관 순매수)
                boolean hasSurgeSignal = checkSurgeSignal(stock.getStockCode(), surgeStocks);
                if (!hasSurgeSignal && surgeStocks != null) {
                    log.debug("[자동매매] {} - 수급 신호 없음, 스킵", stock.getStockName());
                    continue;
                }

                // 현재가 조회
                StockPriceDto priceDto = stockPriceService.getStockPrice(stock.getStockCode());
                if (priceDto == null || priceDto.getCurrentPrice() == null ||
                    priceDto.getCurrentPrice().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                BigDecimal currentPrice = priceDto.getCurrentPrice();

                // 매수 수량 계산 (최대 투자금액 내에서)
                BigDecimal investAmount = account.getCurrentBalance().compareTo(maxPerStock) < 0
                        ? account.getCurrentBalance()
                        : maxPerStock;
                int quantity = investAmount.divide(currentPrice, 0, RoundingMode.DOWN).intValue();

                if (quantity <= 0) {
                    continue;
                }

                // 매수 실행
                try {
                    virtualTradeService.buy(stock.getStockCode(), currentPrice, quantity, "AUTO_BUY");
                    lastTradeTime = LocalDateTime.now();
                    todayBuyCount.incrementAndGet();
                    buyCount++;

                    log.info("[자동매매] 매수 완료: {} x {} @ {}원",
                            stock.getStockName(), quantity, currentPrice);

                    // 계좌 정보 갱신
                    account = virtualTradeService.getOrCreateActiveAccount();
                    holdingCodes.add(stock.getStockCode());

                    // 최대 3종목까지만 매수
                    if (buyCount >= 3) {
                        log.info("[자동매매] 일일 최대 매수 종목 수 도달");
                        break;
                    }

                    // API 호출 제한 방지
                    Thread.sleep(500);

                } catch (Exception e) {
                    log.error("[자동매매] 매수 실패: {} - {}", stock.getStockName(), e.getMessage());
                }
            }

            log.info("[자동매매] 매수 로직 완료 - {}종목 매수", buyCount);

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now();
            log.error("[자동매매] 매수 로직 오류", e);
        }
    }

    /**
     * 손절/익절 체크 (평일 09:00~15:59, 매분)
     */
    @Scheduled(cron = "0 * 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void checkStopLossAndTakeProfit() {
        if (!botActive.get()) {
            return;
        }

        // 주말 체크
        LocalDate today = LocalDate.now();
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return;
        }

        try {
            VirtualAccount account = virtualTradeService.getOrCreateActiveAccount();
            List<VirtualPortfolio> portfolios = portfolioRepository.findByAccountId(account.getId());

            if (portfolios.isEmpty()) {
                return;
            }

            // 종목코드 리스트
            List<String> stockCodes = portfolios.stream()
                    .map(VirtualPortfolio::getStockCode)
                    .collect(Collectors.toList());

            // 일괄 시세 조회
            Map<String, StockPriceDto> prices = stockPriceService.getStockPrices(stockCodes);

            for (VirtualPortfolio portfolio : portfolios) {
                StockPriceDto priceDto = prices.get(portfolio.getStockCode());
                if (priceDto == null || priceDto.getCurrentPrice() == null) {
                    continue;
                }

                BigDecimal currentPrice = priceDto.getCurrentPrice();
                BigDecimal avgPrice = portfolio.getAveragePrice();

                // 손익률 계산
                BigDecimal profitRate = currentPrice.subtract(avgPrice)
                        .divide(avgPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                String reason = null;

                // 손절 체크 (-3% 이하)
                if (profitRate.compareTo(STOP_LOSS_RATE) <= 0) {
                    reason = "STOP_LOSS";
                    log.info("[자동매매] 손절 조건 충족: {} - 손익률 {}%", portfolio.getStockName(), profitRate);
                }
                // 익절 체크 (+5% 이상)
                else if (profitRate.compareTo(TAKE_PROFIT_RATE) >= 0) {
                    reason = "TAKE_PROFIT";
                    log.info("[자동매매] 익절 조건 충족: {} - 손익률 {}%", portfolio.getStockName(), profitRate);
                }

                if (reason != null) {
                    try {
                        virtualTradeService.sell(
                                portfolio.getStockCode(),
                                currentPrice,
                                portfolio.getQuantity(),
                                reason
                        );
                        lastTradeTime = LocalDateTime.now();
                        todaySellCount.incrementAndGet();

                        log.info("[자동매매] {} 완료: {} x {} @ {}원",
                                reason, portfolio.getStockName(), portfolio.getQuantity(), currentPrice);

                    } catch (Exception e) {
                        log.error("[자동매매] 매도 실패: {} - {}", portfolio.getStockName(), e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now();
            log.error("[자동매매] 손절/익절 체크 오류", e);
        }
    }

    /**
     * 수급 신호 확인
     */
    private boolean checkSurgeSignal(String stockCode, Map<String, List<InvestorSurgeDto>> surgeStocks) {
        if (surgeStocks == null) {
            return true; // 수급 데이터 없으면 패스
        }

        // 외국인 순매수 확인
        List<InvestorSurgeDto> foreignStocks = surgeStocks.get("FOREIGN");
        if (foreignStocks != null) {
            boolean foreignBuying = foreignStocks.stream()
                    .anyMatch(s -> s.getStockCode().equals(stockCode) &&
                                   s.getNetBuyAmount() != null &&
                                   s.getNetBuyAmount().compareTo(BigDecimal.ZERO) > 0);
            if (foreignBuying) return true;
        }

        // 기관 순매수 확인
        List<InvestorSurgeDto> instStocks = surgeStocks.get("INSTITUTION");
        if (instStocks != null) {
            boolean instBuying = instStocks.stream()
                    .anyMatch(s -> s.getStockCode().equals(stockCode) &&
                                   s.getNetBuyAmount() != null &&
                                   s.getNetBuyAmount().compareTo(BigDecimal.ZERO) > 0);
            if (instBuying) return true;
        }

        return false;
    }

    /**
     * 장 마감 청산 (평일 15:20) - 오버나잇 리스크 방지
     */
    @Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Seoul")
    public void executeTimeCut() {
        if (!botActive.get()) {
            log.debug("[자동매매] 봇이 비활성화 상태이므로 장 마감 청산을 건너뜁니다.");
            return;
        }

        log.info("[자동매매] 장 마감 청산(Time-Cut) 실행 시작");

        try {
            TimeCutResult result = sellAllPortfolio();

            // 텔레그램 알림
            if (telegramService.isEnabled()) {
                sendTimeCutReport(result);
            }

            log.info("[자동매매] 장 마감 청산 완료 - {}종목 매도, 총 손익: {}원",
                    result.getSoldCount(), result.getTotalProfitLoss());

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now();
            log.error("[자동매매] 장 마감 청산 오류", e);

            if (telegramService.isEnabled()) {
                telegramService.sendMessage(
                        "<b>⚠️ [장 마감] 청산 오류 발생!</b>\n\n" +
                        "❌ 에러: " + e.getMessage() + "\n\n" +
                        "━━━━━━━━━━━━━━━━\n" +
                        "🤖 MyPlatform 모의투자"
                );
            }
        }
    }

    /**
     * 전체 포트폴리오 청산 (Time-Cut)
     * @return 청산 결과 (매도 종목 수, 총 손익)
     */
    public TimeCutResult sellAllPortfolio() {
        VirtualAccount account = virtualTradeService.getOrCreateActiveAccount();
        List<VirtualPortfolio> portfolios = portfolioRepository.findByAccountId(account.getId());

        if (portfolios.isEmpty()) {
            log.info("[자동매매] 보유 종목이 없어 장 마감 청산을 건너뜁니다.");
            return new TimeCutResult(0, BigDecimal.ZERO, List.of());
        }

        // 종목코드 리스트
        List<String> stockCodes = portfolios.stream()
                .map(VirtualPortfolio::getStockCode)
                .collect(Collectors.toList());

        // 일괄 시세 조회
        Map<String, StockPriceDto> prices = stockPriceService.getStockPrices(stockCodes);

        int soldCount = 0;
        BigDecimal totalProfitLoss = BigDecimal.ZERO;
        List<TimeCutItem> soldItems = new java.util.ArrayList<>();

        for (VirtualPortfolio portfolio : portfolios) {
            StockPriceDto priceDto = prices.get(portfolio.getStockCode());
            if (priceDto == null || priceDto.getCurrentPrice() == null) {
                log.warn("[자동매매] {} 시세 조회 실패, 스킵", portfolio.getStockCode());
                continue;
            }

            BigDecimal currentPrice = priceDto.getCurrentPrice();

            try {
                // 전량 매도 (사유: TIME_CUT)
                TradeHistoryDto result = virtualTradeService.sell(
                        portfolio.getStockCode(),
                        currentPrice,
                        portfolio.getQuantity(),
                        "TIME_CUT"
                );

                lastTradeTime = LocalDateTime.now();
                todaySellCount.incrementAndGet();
                soldCount++;

                BigDecimal profitLoss = result.getProfitLoss() != null ? result.getProfitLoss() : BigDecimal.ZERO;
                totalProfitLoss = totalProfitLoss.add(profitLoss);

                // 손익률 계산
                BigDecimal profitRate = currentPrice.subtract(portfolio.getAveragePrice())
                        .divide(portfolio.getAveragePrice(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                soldItems.add(new TimeCutItem(
                        portfolio.getStockName(),
                        portfolio.getStockCode(),
                        portfolio.getQuantity(),
                        currentPrice,
                        profitLoss,
                        profitRate
                ));

                log.info("[자동매매] TIME_CUT 매도: {} x {} @ {}원, 손익: {}원",
                        portfolio.getStockName(), portfolio.getQuantity(), currentPrice, profitLoss);

                // API 호출 제한 방지
                Thread.sleep(300);

            } catch (Exception e) {
                log.error("[자동매매] TIME_CUT 매도 실패: {} - {}", portfolio.getStockName(), e.getMessage());
            }
        }

        return new TimeCutResult(soldCount, totalProfitLoss, soldItems);
    }

    /**
     * 장 마감 청산 결과 텔레그램 리포트 발송
     */
    private void sendTimeCutReport(TimeCutResult result) {
        StringBuilder message = new StringBuilder();

        String profitEmoji = result.getTotalProfitLoss().compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
        String profitSign = result.getTotalProfitLoss().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";

        message.append("<b>🔔 [장 마감] 금일 매매 종료!</b>\n\n");
        message.append("⏰ ").append(LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))).append("\n\n");

        if (result.getSoldCount() == 0) {
            message.append("📭 청산할 보유 종목이 없습니다.\n");
        } else {
            message.append("📊 <b>청산 종목 (").append(result.getSoldCount()).append("건)</b>\n");

            for (TimeCutItem item : result.getSoldItems()) {
                String itemProfitSign = item.getProfitLoss().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                String itemEmoji = item.getProfitLoss().compareTo(BigDecimal.ZERO) >= 0 ? "🔴" : "🔵";

                message.append(itemEmoji).append(" ")
                        .append(item.getStockName())
                        .append(": ").append(itemProfitSign)
                        .append(String.format("%,.0f", item.getProfitLoss())).append("원")
                        .append(" (").append(itemProfitSign)
                        .append(String.format("%.2f", item.getProfitRate())).append("%)\n");
            }
        }

        message.append("\n━━━━━━━━━━━━━━━━\n");
        message.append(profitEmoji).append(" <b>총 손익: ").append(profitSign)
                .append(String.format("%,.0f", result.getTotalProfitLoss())).append("원</b>\n");

        // 오늘의 거래 요약
        message.append("\n📌 금일 거래: 매수 ").append(todayBuyCount.get())
                .append("건 / 매도 ").append(todaySellCount.get()).append("건\n");

        message.append("\n━━━━━━━━━━━━━━━━\n");
        message.append("🤖 MyPlatform 모의투자");

        telegramService.sendMessage(message.toString());
    }

    /**
     * 일일 카운터 초기화
     */
    private void resetDailyCounters() {
        LocalDate today = LocalDate.now();
        if (lastResetDate == null || !lastResetDate.equals(today)) {
            todayBuyCount.set(0);
            todaySellCount.set(0);
            lastResetDate = today;
        }
    }

    /**
     * 장 마감 청산 결과 DTO
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class TimeCutResult {
        private int soldCount;
        private BigDecimal totalProfitLoss;
        private List<TimeCutItem> soldItems;
    }

    /**
     * 장 마감 청산 개별 종목 정보
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class TimeCutItem {
        private String stockName;
        private String stockCode;
        private int quantity;
        private BigDecimal sellPrice;
        private BigDecimal profitLoss;
        private BigDecimal profitRate;
    }
}
