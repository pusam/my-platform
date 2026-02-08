package com.myplatform.backend.service;

import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.dto.PaperTradingDto.AccountSummaryDto;
import com.myplatform.backend.dto.PaperTradingDto.BotStatusDto;
import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.dto.PaperTradingDto.TradeHistoryDto;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.BotConfig;
import com.myplatform.backend.repository.BotConfigRepository;
import com.myplatform.backend.repository.VirtualPortfolioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 자동 매매 봇 서비스 (Momentum Day Trading 전략)
 *
 * ========================================
 * [전략 명세서]
 * ========================================
 *
 * 0. 휴장일 처리
 *    - 주말(토/일): 스케줄러 자체에서 MON-FRI 필터로 제외
 *    - 공휴일: 런타임에 isMarketClosed()로 체크
 *      (신정, 삼일절, 어린이날, 현충일, 광복절, 개천절, 한글날, 성탄절,
 *       설날, 추석, 부처님오신날, 대체공휴일)
 *
 * 1. 매수 로직 (executeBuyLogic)
 *    - 실행 시간: 09:10 ~ 09:30 (매분, 평일/개장일만)
 *    - 종목 선정 기준:
 *      A. 시가총액: 1,000억 원 이상 (슬리피지 방지)
 *      B. 거래량 급증: 현재 거래량이 전일 거래량의 30% 이상
 *      C. 수급 필수: 외국인 또는 기관 순매수 > 0 (필수 조건)
 *      D. 등락률: -2% 이상 (폭락주 제외)
 *    - 최대 보유 종목: 3개 (채우면 매수 중단)
 *    - 종목당 투자 비중: 20%
 *
 * 2. 매도 로직 (checkStopLossAndTakeProfit)
 *    - 감시 시간: 09:10 ~ 15:19 (1분 간격, 평일/개장일만)
 *    - 손절(Stop Loss): -3%
 *    - 익절(Take Profit): +7%
 *
 * 3. 장 마감 청산 (executeTimeCut)
 *    - 시간: 15:20 (평일/개장일만)
 *    - 전량 청산 (오버나잇 리스크 방지)
 *
 * ========================================
 */
@Service
@Slf4j
public class AutoTradingBotService {

    private final VirtualTradeService virtualTradeService;
    private final RealTradeService realTradeService;
    private final VirtualPortfolioRepository portfolioRepository;
    private final QuantScreenerService quantScreenerService;
    private final InvestorSurgeService investorSurgeService;
    private final StockPriceService stockPriceService;
    private final TelegramNotificationService telegramService;
    private final BotConfigRepository botConfigRepository;

    // ========== 전략 상수 ==========
    private static final BigDecimal STOP_LOSS_RATE = new BigDecimal("-3");    // 손절: -3%
    private static final BigDecimal TAKE_PROFIT_RATE = new BigDecimal("7");   // 익절: +7%
    private static final BigDecimal MAX_INVESTMENT_RATIO = new BigDecimal("0.2"); // 종목당 최대 20%
    private static final int MAX_HOLDING_STOCKS = 3;                          // 최대 보유 종목 수

    // ========== 봇 상태 상수 ==========
    private static final String BOT_CONFIG_KEY = "trading_bot";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_STOPPED = "STOPPED";

    // ========== 봇 상태 변수 ==========
    private volatile TradeService activeTradeService;
    private volatile TradingMode currentMode = TradingMode.VIRTUAL;
    private final AtomicBoolean botActive = new AtomicBoolean(false);
    private volatile LocalDateTime lastTradeTime;
    private volatile String lastError;
    private volatile LocalDateTime lastErrorTime;
    private final AtomicInteger todayBuyCount = new AtomicInteger(0);
    private final AtomicInteger todaySellCount = new AtomicInteger(0);
    private volatile LocalDate lastResetDate;

    /**
     * 매매 모드 Enum
     */
    public enum TradingMode {
        VIRTUAL("모의투자"),
        REAL("실전투자");

        private final String displayName;

        TradingMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public AutoTradingBotService(
            @Qualifier("virtualTradeService") VirtualTradeService virtualTradeService,
            @Qualifier("realTradeService") RealTradeService realTradeService,
            VirtualPortfolioRepository portfolioRepository,
            QuantScreenerService quantScreenerService,
            InvestorSurgeService investorSurgeService,
            StockPriceService stockPriceService,
            TelegramNotificationService telegramService,
            BotConfigRepository botConfigRepository) {
        this.virtualTradeService = virtualTradeService;
        this.realTradeService = realTradeService;
        this.portfolioRepository = portfolioRepository;
        this.quantScreenerService = quantScreenerService;
        this.investorSurgeService = investorSurgeService;
        this.stockPriceService = stockPriceService;
        this.telegramService = telegramService;
        this.botConfigRepository = botConfigRepository;
        this.activeTradeService = virtualTradeService;
    }

    // ==================== 봇 상태 관리 ====================

    /**
     * 서버 시작 시 봇 상태 복구
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void restoreBotStateOnStartup() {
        try {
            Thread.sleep(5000);

            BotState savedState = loadBotState();
            if (savedState != null && STATUS_RUNNING.equals(savedState.status)) {
                log.info("[자동매매] 서버 재시작 감지 - 이전 상태 복구 중... (모드: {})", savedState.mode);

                TradingMode mode = TradingMode.valueOf(savedState.mode);
                currentMode = mode;
                activeTradeService = (currentMode == TradingMode.REAL) ? realTradeService : virtualTradeService;
                botActive.set(true);
                resetDailyCounters();

                log.info("[자동매매] 봇 자동 재시작 완료 - 모드: {}", currentMode.getDisplayName());

                if (telegramService.isEnabled()) {
                    String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
                    String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

                    telegramService.sendMessage(
                            String.format("<b>🔄 [%s] 서버 재시작 - 봇 자동 복구!</b>\n\n", modeTag) +
                            "✅ 서버 재시작으로 봇을 자동 재실행했습니다.\n" +
                            "📌 모드: <b>" + currentMode.getDisplayName() + "</b>\n" +
                            "⏰ 복구 시간: " + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "\n\n" +
                            "━━━━━━━━━━━━━━━━\n" +
                            modeEmoji + " MyPlatform " + modeTag
                    );
                }
            } else {
                log.info("[자동매매] 서버 시작 - 봇 비활성화 상태 유지");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[자동매매] 봇 상태 복구 중단됨");
        } catch (Exception e) {
            log.error("[자동매매] 봇 상태 복구 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 봇 상태를 DB에 저장
     */
    @Transactional
    protected void saveBotState(String status, TradingMode mode) {
        try {
            BotConfig config = botConfigRepository.findByConfigKey(BOT_CONFIG_KEY)
                    .orElse(BotConfig.builder().configKey(BOT_CONFIG_KEY).build());

            config.setIsActive(STATUS_RUNNING.equals(status));
            config.setTradingMode(mode.name());
            config.setLastStatusChange(LocalDateTime.now());

            botConfigRepository.save(config);
            log.debug("[자동매매] 봇 상태 DB 저장: status={}, mode={}", status, mode);

        } catch (Exception e) {
            log.warn("[자동매매] 봇 상태 DB 저장 실패: {}", e.getMessage());
        }
    }

    /**
     * DB에서 봇 상태 로드
     */
    private BotState loadBotState() {
        try {
            return botConfigRepository.findByConfigKey(BOT_CONFIG_KEY)
                    .map(config -> new BotState(
                            config.getIsActive() ? STATUS_RUNNING : STATUS_STOPPED,
                            config.getTradingMode() != null ? config.getTradingMode() : TradingMode.VIRTUAL.name()
                    ))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[자동매매] 봇 상태 DB 로드 실패: {}", e.getMessage());
            return null;
        }
    }

    private static class BotState {
        final String status;
        final String mode;

        BotState(String status, String mode) {
            this.status = status;
            this.mode = mode;
        }
    }

    // ==================== 봇 시작/중지 ====================

    /**
     * 봇 시작
     */
    public BotStatusDto startBot(TradingMode mode) {
        if (botActive.get()) {
            log.info("자동매매 봇이 이미 실행 중입니다. 현재 모드: {}", currentMode.getDisplayName());
            return getBotStatus();
        }

        currentMode = mode != null ? mode : TradingMode.VIRTUAL;
        activeTradeService = (currentMode == TradingMode.REAL) ? realTradeService : virtualTradeService;

        botActive.set(true);
        resetDailyCounters();
        saveBotState(STATUS_RUNNING, currentMode);

        log.info("자동매매 봇 시작됨 - 모드: {}", currentMode.getDisplayName());

        if (telegramService.isEnabled()) {
            String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
            String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

            telegramService.sendMessage(
                    String.format("<b>%s [%s] 자동매매 봇 시작!</b>\n\n", modeEmoji, modeTag) +
                    "✅ 봇이 활성화되었습니다.\n" +
                    "📌 전략: <b>Momentum Day Trading</b>\n\n" +
                    "⏰ 매수: 09:10~09:30 (매분 감시)\n" +
                    "⏰ 손절/익절: 09:10~15:19 (매분 체크)\n" +
                    "⏰ 장 마감 청산: 15:20\n\n" +
                    "📊 매수 조건:\n" +
                    "  • 시가총액 1,000억↑\n" +
                    "  • 거래량 전일 대비 30%↑\n" +
                    "  • 외국인/기관 순매수 필수\n" +
                    "  • 등락률 >= -2%\n\n" +
                    "📈 손절: -3% / 익절: +7%\n" +
                    "📦 최대 보유: 3종목\n\n" +
                    "━━━━━━━━━━━━━━━━\n" +
                    modeEmoji + " MyPlatform " + modeTag
            );
        }

        return getBotStatus();
    }

    public BotStatusDto startBot() {
        return startBot(TradingMode.VIRTUAL);
    }

    /**
     * 봇 중지
     */
    public BotStatusDto stopBot() {
        if (!botActive.get()) {
            log.info("자동매매 봇이 이미 중지 상태입니다.");
            return getBotStatus();
        }

        String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
        String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

        botActive.set(false);
        saveBotState(STATUS_STOPPED, currentMode);

        log.info("자동매매 봇 중지됨 - 모드: {}", currentMode.getDisplayName());

        if (telegramService.isEnabled()) {
            telegramService.sendMessage(
                    String.format("<b>%s [%s] 자동매매 봇 중지!</b>\n\n", modeEmoji, modeTag) +
                    "⏸️ 봇이 비활성화되었습니다.\n\n" +
                    "━━━━━━━━━━━━━━━━\n" +
                    modeEmoji + " MyPlatform " + modeTag
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
                .tradingMode(currentMode.name())
                .tradingModeName(currentMode.getDisplayName())
                .build();
    }

    public TradingMode getCurrentMode() {
        return currentMode;
    }

    // ==================== 매수 로직 ====================

    /**
     * 매수 로직 실행
     * - 실행 시간: 09:10 ~ 09:30 (매분, 평일만)
     * - 최대 3종목까지만 보유
     */
    @Scheduled(cron = "0 10-30 9 * * MON-FRI", zone = "Asia/Seoul")
    public void executeBuyLogic() {
        if (!botActive.get()) {
            return;
        }

        // 휴장일 체크 (공휴일)
        if (isMarketClosed()) {
            return;
        }

        log.info("[자동매매] ===== 매수 로직 시작 ({}) =====", LocalTime.now());
        resetDailyCounters();

        try {
            // 현재 보유 종목 수 확인 - 3개 이상이면 매수 중단
            List<PortfolioItemDto> currentPortfolio = activeTradeService.getPortfolio();
            if (currentPortfolio.size() >= MAX_HOLDING_STOCKS) {
                log.info("[자동매매] 최대 보유 종목 수({}) 도달 - 매수 스킵", MAX_HOLDING_STOCKS);
                return;
            }

            int remainingSlots = MAX_HOLDING_STOCKS - currentPortfolio.size();
            log.info("[자동매매] 현재 보유: {}종목, 매수 가능: {}종목", currentPortfolio.size(), remainingSlots);

            // 계좌 정보 조회
            AccountSummaryDto accountSummary = activeTradeService.getAccountSummary();
            BigDecimal totalAsset = accountSummary.getCurrentBalance().add(
                    accountSummary.getTotalEvaluation() != null ? accountSummary.getTotalEvaluation() : BigDecimal.ZERO);
            BigDecimal maxPerStock = totalAsset.multiply(MAX_INVESTMENT_RATIO);

            log.info("[자동매매] 계좌현황 - 잔액: {}원, 총자산: {}원, 종목당 최대: {}원",
                    formatNumber(accountSummary.getCurrentBalance()),
                    formatNumber(totalAsset),
                    formatNumber(maxPerStock));

            // 모멘텀 종목 조회 (시총 1000억+, 거래량 30%+, 등락률 -2%+)
            List<ScreenerResultDto> momentumStocks = quantScreenerService.getMomentumStocks(20);

            if (momentumStocks.isEmpty()) {
                log.info("[자동매매] 모멘텀 조건에 맞는 종목 없음");
                return;
            }

            log.info("[자동매매] 모멘텀 후보 종목 {}건:", momentumStocks.size());
            for (ScreenerResultDto s : momentumStocks) {
                log.info("  - {} ({}) | 등락률: {}% | 거래량비율: {}% | 시총: {}억",
                        s.getStockName(), s.getStockCode(),
                        s.getChangeRate(), s.getVolumeRatio(), s.getMarketCap());
            }

            // 수급 데이터 조회 (필수)
            Map<String, List<InvestorSurgeDto>> surgeStocks = investorSurgeService.getAllSurgeStocks(BigDecimal.ZERO);
            if (surgeStocks == null || surgeStocks.isEmpty()) {
                log.warn("[자동매매] 수급 데이터 없음 - 매수 중단");
                return;
            }

            log.info("[자동매매] 수급 데이터 - 외국인: {}건, 기관: {}건",
                    surgeStocks.get("FOREIGN") != null ? surgeStocks.get("FOREIGN").size() : 0,
                    surgeStocks.get("INSTITUTION") != null ? surgeStocks.get("INSTITUTION").size() : 0);

            // 이미 보유 중인 종목 코드
            List<String> holdingCodes = currentPortfolio.stream()
                    .map(PortfolioItemDto::getStockCode)
                    .collect(Collectors.toList());

            BigDecimal currentBalance = accountSummary.getCurrentBalance();
            int buyCount = 0;

            for (ScreenerResultDto stock : momentumStocks) {
                // 잔액 확인
                if (currentBalance.compareTo(new BigDecimal("100000")) < 0) {
                    log.info("[자동매매] 잔액 부족 (잔액: {}원)", formatNumber(currentBalance));
                    break;
                }

                // 이미 보유 중인 종목 스킵
                if (holdingCodes.contains(stock.getStockCode())) {
                    continue;
                }

                // 수급 신호 확인 (외국인 OR 기관 순매수 > 0)
                SurgeSignalResult surgeResult = checkSurgeSignal(stock.getStockCode(), surgeStocks);
                if (!surgeResult.hasSignal) {
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

                // 매수 수량 계산
                BigDecimal investAmount = currentBalance.compareTo(maxPerStock) < 0 ? currentBalance : maxPerStock;
                int quantity = investAmount.divide(currentPrice, 0, RoundingMode.DOWN).intValue();

                if (quantity <= 0) {
                    continue;
                }

                // 매수 실행
                try {
                    activeTradeService.buy(stock.getStockCode(), stock.getStockName(), currentPrice, quantity, "AUTO_BUY");
                    lastTradeTime = LocalDateTime.now();
                    todayBuyCount.incrementAndGet();
                    buyCount++;

                    // 로그 (거래량 비율, 수급 주체 포함)
                    log.info("[자동매매-{}] ★ 매수 완료 ★", currentMode.name());
                    log.info("  종목: {} ({})", stock.getStockName(), stock.getStockCode());
                    log.info("  매수가: {}원 x {}주 = {}원",
                            formatNumber(currentPrice), quantity,
                            formatNumber(currentPrice.multiply(BigDecimal.valueOf(quantity))));
                    log.info("  거래량비율: {}% | 등락률: {}%", stock.getVolumeRatio(), stock.getChangeRate());
                    log.info("  수급주체: {} | 외국인: {}억 | 기관: {}억",
                            surgeResult.mainBuyer,
                            surgeResult.foreignNetBuy != null ? String.format("%.1f", surgeResult.foreignNetBuy) : "N/A",
                            surgeResult.instNetBuy != null ? String.format("%.1f", surgeResult.instNetBuy) : "N/A");

                    // 텔레그램 알림
                    sendBuyNotification(stock, currentPrice, quantity, surgeResult);

                    // 잔고 갱신
                    AccountSummaryDto refreshed = activeTradeService.getAccountSummary();
                    currentBalance = refreshed.getCurrentBalance();
                    holdingCodes.add(stock.getStockCode());

                    // 최대 종목 수 체크
                    if (holdingCodes.size() >= MAX_HOLDING_STOCKS) {
                        log.info("[자동매매] 최대 보유 종목 수 도달 ({}종목)", MAX_HOLDING_STOCKS);
                        break;
                    }

                    Thread.sleep(currentMode == TradingMode.REAL ? 1000 : 500);

                } catch (Exception e) {
                    log.error("[자동매매] 매수 실패: {} - {}", stock.getStockName(), e.getMessage());
                }
            }

            log.info("[자동매매] ===== 매수 로직 완료 - {}종목 매수 =====", buyCount);

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now();
            log.error("[자동매매] 매수 로직 오류", e);
        }
    }

    // ==================== 손절/익절 로직 ====================

    /**
     * 손절/익절 체크
     * - 감시 시간: 09:10 ~ 15:19 (매분, 평일만)
     * - 손절: -3% / 익절: +7%
     */
    @Scheduled(cron = "0 * 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void checkStopLossAndTakeProfit() {
        if (!botActive.get()) {
            return;
        }

        // 휴장일 체크 (공휴일)
        if (isMarketClosed()) {
            return;
        }

        // 시간 체크: 09:10 ~ 15:19
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 10)) || now.isAfter(LocalTime.of(15, 19))) {
            return;
        }

        try {
            List<PortfolioItemDto> portfolios = activeTradeService.getPortfolio();
            if (portfolios.isEmpty()) {
                return;
            }

            List<String> stockCodes = portfolios.stream()
                    .map(PortfolioItemDto::getStockCode)
                    .collect(Collectors.toList());

            Map<String, StockPriceDto> prices = stockPriceService.getStockPrices(stockCodes);

            for (PortfolioItemDto portfolio : portfolios) {
                StockPriceDto priceDto = prices.get(portfolio.getStockCode());
                if (priceDto == null || priceDto.getCurrentPrice() == null) {
                    continue;
                }

                BigDecimal currentPrice = priceDto.getCurrentPrice();
                BigDecimal avgPrice = portfolio.getAveragePrice();

                if (avgPrice == null || avgPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                // 손익률 계산
                BigDecimal profitRate = currentPrice.subtract(avgPrice)
                        .divide(avgPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                String reason = null;

                // 손절 체크 (-3%)
                if (profitRate.compareTo(STOP_LOSS_RATE) <= 0) {
                    reason = "STOP_LOSS";
                    log.info("[자동매매] 손절 조건 충족: {} - 손익률 {}%", portfolio.getStockName(), profitRate);
                }
                // 익절 체크 (+7%)
                else if (profitRate.compareTo(TAKE_PROFIT_RATE) >= 0) {
                    reason = "TAKE_PROFIT";
                    log.info("[자동매매] 익절 조건 충족: {} - 손익률 {}%", portfolio.getStockName(), profitRate);
                }

                if (reason != null) {
                    executeSell(portfolio, currentPrice, avgPrice, profitRate, reason);
                }
            }

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now();
            log.error("[자동매매] 손절/익절 체크 오류", e);
        }
    }

    /**
     * 매도 실행 (공통)
     */
    private void executeSell(PortfolioItemDto portfolio, BigDecimal currentPrice,
                             BigDecimal avgPrice, BigDecimal profitRate, String reason) {
        try {
            activeTradeService.sell(portfolio.getStockCode(), currentPrice, portfolio.getQuantity(), reason);
            lastTradeTime = LocalDateTime.now();
            todaySellCount.incrementAndGet();

            BigDecimal profitLoss = currentPrice.subtract(avgPrice).multiply(BigDecimal.valueOf(portfolio.getQuantity()));

            log.info("[자동매매-{}] {} 완료: {} x {} @ {}원, 손익: {}원",
                    currentMode.name(), reason, portfolio.getStockName(),
                    portfolio.getQuantity(), formatNumber(currentPrice), formatNumber(profitLoss));

            // 텔레그램 알림
            sendSellNotification(portfolio, currentPrice, avgPrice, profitRate, profitLoss, reason);

        } catch (Exception e) {
            log.error("[자동매매] 매도 실패: {} - {}", portfolio.getStockName(), e.getMessage());
        }
    }

    // ==================== 장 마감 청산 ====================

    /**
     * 장 마감 청산 (15:20, 평일만)
     */
    @Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Seoul")
    public void executeTimeCut() {
        if (!botActive.get()) {
            return;
        }

        // 휴장일 체크 (공휴일)
        if (isMarketClosed()) {
            return;
        }

        log.info("[자동매매] ===== 장 마감 청산 시작 =====");

        try {
            TimeCutResult result = sellAllPortfolio();

            if (telegramService.isEnabled()) {
                sendTimeCutReport(result);
            }

            log.info("[자동매매] 장 마감 청산 완료 - {}종목 매도, 총 손익: {}원",
                    result.soldCount, formatNumber(result.totalProfitLoss));

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now();
            log.error("[자동매매] 장 마감 청산 오류", e);
        }
    }

    /**
     * 전체 포트폴리오 청산
     */
    public TimeCutResult sellAllPortfolio() {
        List<PortfolioItemDto> portfolios = activeTradeService.getPortfolio();

        if (portfolios.isEmpty()) {
            log.info("[자동매매] 보유 종목 없음 - 청산 스킵");
            return new TimeCutResult(0, BigDecimal.ZERO, List.of());
        }

        List<String> stockCodes = portfolios.stream()
                .map(PortfolioItemDto::getStockCode)
                .collect(Collectors.toList());

        Map<String, StockPriceDto> prices = stockPriceService.getStockPrices(stockCodes);

        int soldCount = 0;
        BigDecimal totalProfitLoss = BigDecimal.ZERO;
        List<TimeCutItem> soldItems = new java.util.ArrayList<>();

        for (PortfolioItemDto portfolio : portfolios) {
            StockPriceDto priceDto = prices.get(portfolio.getStockCode());
            if (priceDto == null || priceDto.getCurrentPrice() == null) {
                continue;
            }

            BigDecimal currentPrice = priceDto.getCurrentPrice();

            try {
                TradeHistoryDto result = activeTradeService.sell(
                        portfolio.getStockCode(), currentPrice, portfolio.getQuantity(), "TIME_CUT");

                lastTradeTime = LocalDateTime.now();
                todaySellCount.incrementAndGet();
                soldCount++;

                BigDecimal profitLoss = result.getProfitLoss() != null ? result.getProfitLoss() : BigDecimal.ZERO;
                totalProfitLoss = totalProfitLoss.add(profitLoss);

                BigDecimal avgPrice = portfolio.getAveragePrice();
                BigDecimal profitRate = BigDecimal.ZERO;
                if (avgPrice != null && avgPrice.compareTo(BigDecimal.ZERO) > 0) {
                    profitRate = currentPrice.subtract(avgPrice)
                            .divide(avgPrice, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));
                }

                soldItems.add(new TimeCutItem(
                        portfolio.getStockName(), portfolio.getStockCode(),
                        portfolio.getQuantity(), currentPrice, profitLoss, profitRate));

                log.info("[자동매매] TIME_CUT: {} x {} @ {}원, 손익: {}원",
                        portfolio.getStockName(), portfolio.getQuantity(),
                        formatNumber(currentPrice), formatNumber(profitLoss));

                Thread.sleep(300);

            } catch (Exception e) {
                log.error("[자동매매] TIME_CUT 실패: {} - {}", portfolio.getStockName(), e.getMessage());
            }
        }

        return new TimeCutResult(soldCount, totalProfitLoss, soldItems);
    }

    // ==================== 수급 신호 확인 ====================

    private static class SurgeSignalResult {
        boolean hasSignal;
        String mainBuyer;
        BigDecimal foreignNetBuy;
        BigDecimal instNetBuy;

        SurgeSignalResult(boolean hasSignal, String mainBuyer, BigDecimal foreignNetBuy, BigDecimal instNetBuy) {
            this.hasSignal = hasSignal;
            this.mainBuyer = mainBuyer;
            this.foreignNetBuy = foreignNetBuy;
            this.instNetBuy = instNetBuy;
        }

        static SurgeSignalResult noSignal() {
            return new SurgeSignalResult(false, "없음", null, null);
        }
    }

    /**
     * 수급 신호 확인
     * - 외국인 또는 기관 순매수 > 0 이면 신호 있음
     */
    private SurgeSignalResult checkSurgeSignal(String stockCode, Map<String, List<InvestorSurgeDto>> surgeStocks) {
        if (surgeStocks == null || surgeStocks.isEmpty()) {
            return SurgeSignalResult.noSignal();
        }

        BigDecimal foreignNetBuy = null;
        BigDecimal instNetBuy = null;

        // 외국인 순매수
        List<InvestorSurgeDto> foreignStocks = surgeStocks.get("FOREIGN");
        if (foreignStocks != null) {
            for (InvestorSurgeDto s : foreignStocks) {
                if (s.getStockCode().equals(stockCode) && s.getNetBuyAmount() != null) {
                    foreignNetBuy = s.getNetBuyAmount();
                    break;
                }
            }
        }

        // 기관 순매수
        List<InvestorSurgeDto> instStocks = surgeStocks.get("INSTITUTION");
        if (instStocks != null) {
            for (InvestorSurgeDto s : instStocks) {
                if (s.getStockCode().equals(stockCode) && s.getNetBuyAmount() != null) {
                    instNetBuy = s.getNetBuyAmount();
                    break;
                }
            }
        }

        if (foreignNetBuy == null && instNetBuy == null) {
            return SurgeSignalResult.noSignal();
        }

        boolean foreignBuying = foreignNetBuy != null && foreignNetBuy.compareTo(BigDecimal.ZERO) > 0;
        boolean instBuying = instNetBuy != null && instNetBuy.compareTo(BigDecimal.ZERO) > 0;

        if (!foreignBuying && !instBuying) {
            return SurgeSignalResult.noSignal();
        }

        String mainBuyer;
        if (foreignBuying && instBuying) {
            mainBuyer = "쌍끌이";
        } else if (foreignBuying) {
            mainBuyer = "외국인";
        } else {
            mainBuyer = "기관";
        }

        return new SurgeSignalResult(true, mainBuyer, foreignNetBuy, instNetBuy);
    }

    // ==================== 텔레그램 알림 ====================

    private void sendBuyNotification(ScreenerResultDto stock, BigDecimal price, int quantity, SurgeSignalResult surge) {
        if (!telegramService.isEnabled()) return;

        String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
        String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity));

        telegramService.sendMessage(
                String.format("<b>%s [%s] 매수 체결</b>\n\n", modeEmoji, modeTag) +
                "📈 <b>" + stock.getStockName() + "</b> (" + stock.getStockCode() + ")\n" +
                "💰 " + formatNumber(price) + "원 x " + quantity + "주\n" +
                "💵 매수금액: " + formatNumber(totalAmount) + "원\n\n" +
                "📊 거래량비율: " + stock.getVolumeRatio() + "%\n" +
                "📊 등락률: " + stock.getChangeRate() + "%\n" +
                "📊 수급: " + surge.mainBuyer +
                " (외국인 " + (surge.foreignNetBuy != null ? String.format("%.1f", surge.foreignNetBuy) : "N/A") + "억" +
                " / 기관 " + (surge.instNetBuy != null ? String.format("%.1f", surge.instNetBuy) : "N/A") + "억)\n\n" +
                "━━━━━━━━━━━━━━━━\n" +
                modeEmoji + " MyPlatform " + modeTag
        );
    }

    private void sendSellNotification(PortfolioItemDto portfolio, BigDecimal currentPrice,
                                      BigDecimal avgPrice, BigDecimal profitRate, BigDecimal profitLoss, String reason) {
        if (!telegramService.isEnabled()) return;

        String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
        String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";
        String reasonEmoji = "STOP_LOSS".equals(reason) ? "🔻 손절" : "🔺 익절";
        String reasonTag = "STOP_LOSS".equals(reason) ? "손절" : "익절";

        telegramService.sendMessage(
                String.format("<b>%s [%s] %s 체결</b>\n\n", modeEmoji, modeTag, reasonTag) +
                reasonEmoji + " <b>" + portfolio.getStockName() + "</b> (" + portfolio.getStockCode() + ")\n" +
                "💰 매도가: " + formatNumber(currentPrice) + "원\n" +
                "📊 평균단가: " + formatNumber(avgPrice) + "원\n" +
                "📦 수량: " + portfolio.getQuantity() + "주\n" +
                "📈 손익률: " + String.format("%+.2f", profitRate) + "%\n" +
                "💵 손익금액: " + String.format("%+,d", profitLoss.intValue()) + "원\n\n" +
                "━━━━━━━━━━━━━━━━\n" +
                modeEmoji + " MyPlatform " + modeTag
        );
    }

    private void sendTimeCutReport(TimeCutResult result) {
        String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
        String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";
        String profitEmoji = result.totalProfitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
        String profitSign = result.totalProfitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";

        StringBuilder message = new StringBuilder();
        message.append(String.format("<b>🔔 [%s] 장 마감 청산 완료!</b>\n\n", modeTag));
        message.append("⏰ ").append(LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))).append("\n\n");

        if (result.soldCount == 0) {
            message.append("📭 청산할 보유 종목 없음\n");
        } else {
            message.append("📊 <b>청산 종목 (").append(result.soldCount).append("건)</b>\n");
            for (TimeCutItem item : result.soldItems) {
                String itemSign = item.profitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                String itemEmoji = item.profitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "🔴" : "🔵";
                message.append(itemEmoji).append(" ").append(item.stockName)
                        .append(": ").append(itemSign).append(formatNumber(item.profitLoss)).append("원")
                        .append(" (").append(itemSign).append(String.format("%.2f", item.profitRate)).append("%)\n");
            }
        }

        message.append("\n━━━━━━━━━━━━━━━━\n");
        message.append(profitEmoji).append(" <b>총 손익: ").append(profitSign)
                .append(formatNumber(result.totalProfitLoss)).append("원</b>\n");
        message.append("\n📌 금일 거래: 매수 ").append(todayBuyCount.get())
                .append("건 / 매도 ").append(todaySellCount.get()).append("건\n\n");
        message.append("━━━━━━━━━━━━━━━━\n");
        message.append(modeEmoji).append(" MyPlatform ").append(modeTag);

        telegramService.sendMessage(message.toString());
    }

    // ==================== 유틸리티 ====================

    private void resetDailyCounters() {
        LocalDate today = LocalDate.now();
        if (lastResetDate == null || !lastResetDate.equals(today)) {
            todayBuyCount.set(0);
            todaySellCount.set(0);
            lastResetDate = today;
        }
    }

    // ==================== 휴장일 체크 ====================

    /**
     * 한국 증시 휴장일 (고정 공휴일)
     * - 매년 반복되는 공휴일 목록
     * - 대체공휴일은 매년 다르므로 별도 관리 필요
     */
    private static final Set<MonthDay> KOREA_FIXED_HOLIDAYS = Set.of(
            MonthDay.of(1, 1),   // 신정
            MonthDay.of(3, 1),   // 삼일절
            MonthDay.of(5, 5),   // 어린이날
            MonthDay.of(6, 6),   // 현충일
            MonthDay.of(8, 15),  // 광복절
            MonthDay.of(10, 3),  // 개천절
            MonthDay.of(10, 9),  // 한글날
            MonthDay.of(12, 25)  // 성탄절
    );

    /**
     * 2025년 한국 증시 휴장일 (음력 공휴일 + 대체공휴일)
     */
    private static final Set<LocalDate> KOREA_HOLIDAYS_2025 = Set.of(
            // 설날 연휴 (1/28~1/30)
            LocalDate.of(2025, 1, 28),
            LocalDate.of(2025, 1, 29),
            LocalDate.of(2025, 1, 30),
            // 부처님오신날
            LocalDate.of(2025, 5, 5),  // 어린이날과 겹침
            LocalDate.of(2025, 5, 6),  // 대체공휴일
            // 추석 연휴 (10/5~10/7)
            LocalDate.of(2025, 10, 6),
            LocalDate.of(2025, 10, 7),
            LocalDate.of(2025, 10, 8)
    );

    /**
     * 2026년 한국 증시 휴장일 (음력 공휴일 + 대체공휴일)
     */
    private static final Set<LocalDate> KOREA_HOLIDAYS_2026 = Set.of(
            // 설날 연휴 (2/16~2/18)
            LocalDate.of(2026, 2, 16),
            LocalDate.of(2026, 2, 17),
            LocalDate.of(2026, 2, 18),
            // 부처님오신날
            LocalDate.of(2026, 5, 24),
            // 추석 연휴 (9/24~9/26)
            LocalDate.of(2026, 9, 24),
            LocalDate.of(2026, 9, 25),
            LocalDate.of(2026, 9, 26)
    );

    /**
     * 주식 시장 휴장일인지 확인
     * - 주말 (토, 일)
     * - 고정 공휴일 (신정, 삼일절 등)
     * - 음력 공휴일 (설날, 추석, 부처님오신날)
     */
    private boolean isMarketClosed() {
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        // 주말 체크
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            log.debug("[자동매매] 주말 휴장일 - {}", dayOfWeek);
            return true;
        }

        // 고정 공휴일 체크
        MonthDay monthDay = MonthDay.from(today);
        if (KOREA_FIXED_HOLIDAYS.contains(monthDay)) {
            log.info("[자동매매] 공휴일 휴장 - {}", today);
            return true;
        }

        // 연도별 음력/대체 공휴일 체크
        int year = today.getYear();
        if (year == 2025 && KOREA_HOLIDAYS_2025.contains(today)) {
            log.info("[자동매매] 공휴일 휴장 (2025) - {}", today);
            return true;
        }
        if (year == 2026 && KOREA_HOLIDAYS_2026.contains(today)) {
            log.info("[자동매매] 공휴일 휴장 (2026) - {}", today);
            return true;
        }

        return false;
    }

    private String formatNumber(BigDecimal value) {
        if (value == null) return "0";
        return String.format("%,d", value.longValue());
    }

    // ==================== DTO 클래스 ====================

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class TimeCutResult {
        private int soldCount;
        private BigDecimal totalProfitLoss;
        private List<TimeCutItem> soldItems;
    }

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
