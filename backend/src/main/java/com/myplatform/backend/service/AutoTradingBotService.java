package com.myplatform.backend.service;

import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.dto.PaperTradingDto.AccountSummaryDto;
import com.myplatform.backend.dto.PaperTradingDto.BotStatusDto;
import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.dto.PaperTradingDto.TradeHistoryDto;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.repository.VirtualPortfolioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 자동 매매 봇 서비스
 * - 마법의 공식 상위 종목 자동 매수
 * - 손절/익절 자동 실행
 * - VIRTUAL(모의투자) / REAL(실전투자) 모드 지원
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

    // 현재 사용 중인 매매 서비스
    private volatile TradeService activeTradeService;
    private volatile TradingMode currentMode = TradingMode.VIRTUAL;

    // 봇 상태
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
            TelegramNotificationService telegramService) {
        this.virtualTradeService = virtualTradeService;
        this.realTradeService = realTradeService;
        this.portfolioRepository = portfolioRepository;
        this.quantScreenerService = quantScreenerService;
        this.investorSurgeService = investorSurgeService;
        this.stockPriceService = stockPriceService;
        this.telegramService = telegramService;

        // 기본값: 모의투자
        this.activeTradeService = virtualTradeService;
    }

    /**
     * 서버 시작 시 봇 상태 복구
     * - 이전에 RUNNING 상태였다면 자동으로 봇 재시작
     * - 텔레그램으로 재시작 알림 발송
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void restoreBotStateOnStartup() {
        try {
            // 다른 초기화 작업 대기
            Thread.sleep(5000);

            BotState savedState = loadBotState();
            if (savedState != null && STATUS_RUNNING.equals(savedState.status)) {
                log.info("[자동매매] 서버 재시작 감지 - 이전 상태 복구 중... (모드: {})", savedState.mode);

                // 봇 재시작 (내부적으로 상태 저장하므로 별도 저장 불필요)
                TradingMode mode = TradingMode.valueOf(savedState.mode);

                // 봇 활성화 (startBot 호출하지 않고 직접 설정 - 중복 알림 방지)
                currentMode = mode;
                activeTradeService = (currentMode == TradingMode.REAL) ? realTradeService : virtualTradeService;
                botActive.set(true);
                resetDailyCounters();

                log.info("[자동매매] 봇 자동 재시작 완료 - 모드: {}", currentMode.getDisplayName());

                // 텔레그램 알림 (재시작 전용 메시지)
                if (telegramService.isEnabled()) {
                    String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
                    String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

                    telegramService.sendMessage(
                            String.format("<b>🔄 [%s] 서버 재시작 - 봇 자동 복구!</b>\n\n", modeTag) +
                            "✅ 서버 재시작으로 봇을 자동 재실행했습니다.\n" +
                            "📌 모드: <b>" + currentMode.getDisplayName() + "</b>\n" +
                            "⏰ 복구 시간: " + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "\n\n" +
                            "💡 봇이 이전 상태로 복구되었습니다.\n" +
                            "   정상적으로 매매가 계속됩니다.\n\n" +
                            "━━━━━━━━━━━━━━━━\n" +
                            modeEmoji + " MyPlatform " + modeTag
                    );
                }
            } else {
                log.info("[자동매매] 서버 시작 - 봇 비활성화 상태 유지 (이전 상태: {})",
                        savedState != null ? savedState.status : "없음");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[자동매매] 봇 상태 복구 중단됨");
        } catch (Exception e) {
            log.error("[자동매매] 봇 상태 복구 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 봇 상태를 파일에 저장
     */
    private void saveBotState(String status, TradingMode mode) {
        try {
            Path statusFile = Paths.get(BOT_STATUS_FILE);
            Properties props = new Properties();
            props.setProperty("status", status);
            props.setProperty("mode", mode.name());
            props.setProperty("updatedAt", LocalDateTime.now().toString());

            try (var writer = Files.newBufferedWriter(statusFile)) {
                props.store(writer, "Auto Trading Bot Status");
            }

            log.debug("[자동매매] 봇 상태 저장: status={}, mode={}", status, mode);

        } catch (IOException e) {
            log.warn("[자동매매] 봇 상태 저장 실패: {}", e.getMessage());
        }
    }

    /**
     * 저장된 봇 상태 로드
     */
    private BotState loadBotState() {
        try {
            Path statusFile = Paths.get(BOT_STATUS_FILE);
            if (!Files.exists(statusFile)) {
                return null;
            }

            Properties props = new Properties();
            try (var reader = Files.newBufferedReader(statusFile)) {
                props.load(reader);
            }

            String status = props.getProperty("status", STATUS_STOPPED);
            String mode = props.getProperty("mode", TradingMode.VIRTUAL.name());

            return new BotState(status, mode);

        } catch (IOException e) {
            log.warn("[자동매매] 봇 상태 로드 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 봇 상태 저장용 내부 클래스
     */
    private static class BotState {
        final String status;
        final String mode;

        BotState(String status, String mode) {
            this.status = status;
            this.mode = mode;
        }
    }

    // 손절/익절 기준
    private static final BigDecimal STOP_LOSS_RATE = new BigDecimal("-3"); // -3%
    private static final BigDecimal TAKE_PROFIT_RATE = new BigDecimal("5"); // +5%
    private static final BigDecimal MAX_INVESTMENT_RATIO = new BigDecimal("0.2"); // 종목당 최대 20%

    // 봇 상태 저장 파일 (서버 재시작 시 복구용)
    private static final String BOT_STATUS_FILE = "bot.status";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_STOPPED = "STOPPED";

    /**
     * 봇 시작 (모드 지정)
     */
    public BotStatusDto startBot(TradingMode mode) {
        if (botActive.get()) {
            log.info("자동매매 봇이 이미 실행 중입니다. 현재 모드: {}", currentMode.getDisplayName());
            return getBotStatus();
        }

        // 모드 설정
        currentMode = mode != null ? mode : TradingMode.VIRTUAL;
        activeTradeService = (currentMode == TradingMode.REAL) ? realTradeService : virtualTradeService;

        botActive.set(true);
        resetDailyCounters();

        // 상태 파일에 저장 (서버 재시작 시 복구용)
        saveBotState(STATUS_RUNNING, currentMode);

        log.info("자동매매 봇 시작됨 - 모드: {}", currentMode.getDisplayName());

        // 텔레그램 알림
        if (telegramService.isEnabled()) {
            String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
            String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

            telegramService.sendMessage(
                    String.format("<b>%s [%s] 자동매매 봇 시작!</b>\n\n", modeEmoji, modeTag) +
                    "✅ 봇이 활성화되었습니다.\n" +
                    "📌 모드: <b>" + currentMode.getDisplayName() + "</b>\n" +
                    "⏰ 매수: 평일 09:30\n" +
                    "⏰ 손절/익절 체크: 매분\n" +
                    "⏰ 장 마감 청산: 평일 15:20\n\n" +
                    "━━━━━━━━━━━━━━━━\n" +
                    modeEmoji + " MyPlatform " + modeTag
            );
        }

        return getBotStatus();
    }

    /**
     * 봇 시작 (기본: 모의투자)
     */
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

        // 상태 파일에 저장 (서버 재시작 시 복구하지 않음)
        saveBotState(STATUS_STOPPED, currentMode);

        log.info("자동매매 봇 중지됨 - 모드: {}", currentMode.getDisplayName());

        // 텔레그램 알림
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

    /**
     * 현재 매매 모드 조회
     */
    public TradingMode getCurrentMode() {
        return currentMode;
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

        log.info("[자동매매] 매수 로직 실행 시작 - 모드: {}", currentMode.getDisplayName());
        resetDailyCounters();

        try {
            // 계좌 정보 조회 (모드에 따라 다른 서비스 사용)
            AccountSummaryDto accountSummary = activeTradeService.getAccountSummary();

            // 종목당 최대 투자금액 계산
            BigDecimal totalAsset = accountSummary.getCurrentBalance().add(
                    accountSummary.getTotalEvaluation() != null ? accountSummary.getTotalEvaluation() : BigDecimal.ZERO);
            BigDecimal maxPerStock = totalAsset.multiply(MAX_INVESTMENT_RATIO);

            // 마법의 공식 상위 종목 조회
            List<ScreenerResultDto> magicFormulaStocks = quantScreenerService.getMagicFormulaStocks(10, null);

            if (magicFormulaStocks.isEmpty()) {
                log.warn("[자동매매] 마법의 공식 종목이 없습니다. DB에 재무 데이터가 있는지 확인 필요!");
                return;
            }

            log.info("[자동매매] 마법의 공식 후보 종목: {}",
                    magicFormulaStocks.stream()
                            .map(s -> s.getStockName() + "(" + s.getStockCode() + ")")
                            .collect(Collectors.joining(", ")));

            // 외국인/기관 수급 확인
            Map<String, List<InvestorSurgeDto>> surgeStocks = null;
            try {
                surgeStocks = investorSurgeService.getAllSurgeStocks(new BigDecimal("30"));
            } catch (Exception e) {
                log.warn("[자동매매] 수급 데이터 조회 실패: {}", e.getMessage());
            }

            // 이미 보유 중인 종목 코드
            List<PortfolioItemDto> portfolioList = activeTradeService.getPortfolio();
            List<String> holdingCodes = portfolioList.stream()
                    .map(PortfolioItemDto::getStockCode)
                    .collect(Collectors.toList());

            BigDecimal currentBalance = accountSummary.getCurrentBalance();
            int buyCount = 0;

            for (ScreenerResultDto stock : magicFormulaStocks) {
                // 잔액 확인
                if (currentBalance.compareTo(new BigDecimal("100000")) < 0) {
                    log.info("[자동매매] 잔액 부족으로 매수 중단");
                    break;
                }

                // 이미 보유 중인 종목 제외
                if (holdingCodes.contains(stock.getStockCode())) {
                    continue;
                }

                // 수급 신호 확인 (외국인 또는 기관 순매수)
                // 수급 데이터 없으면 매수하지 않음 (보수적 접근)
                boolean hasSurgeSignal = checkSurgeSignal(stock.getStockCode(), surgeStocks);
                if (!hasSurgeSignal) {
                    log.debug("[자동매매] {} - 수급 신호 없음 또는 데이터 없음, 스킵", stock.getStockName());
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
                BigDecimal investAmount = currentBalance.compareTo(maxPerStock) < 0
                        ? currentBalance
                        : maxPerStock;
                int quantity = investAmount.divide(currentPrice, 0, RoundingMode.DOWN).intValue();

                if (quantity <= 0) {
                    continue;
                }

                // 매수 실행 (activeTradeService 사용)
                try {
                    activeTradeService.buy(stock.getStockCode(), currentPrice, quantity, "AUTO_BUY");
                    lastTradeTime = LocalDateTime.now();
                    todayBuyCount.incrementAndGet();
                    buyCount++;

                    log.info("[자동매매-{}] 매수 완료: {} x {} @ {}원",
                            currentMode.name(), stock.getStockName(), quantity, currentPrice);

                    // 계좌 정보 갱신
                    AccountSummaryDto refreshedAccount = activeTradeService.getAccountSummary();
                    currentBalance = refreshedAccount.getCurrentBalance();
                    holdingCodes.add(stock.getStockCode());

                    // 최대 3종목까지만 매수
                    if (buyCount >= 3) {
                        log.info("[자동매매] 일일 최대 매수 종목 수 도달");
                        break;
                    }

                    // API 호출 제한 방지
                    Thread.sleep(currentMode == TradingMode.REAL ? 1000 : 500);

                } catch (Exception e) {
                    log.error("[자동매매] 매수 실패: {} - {}", stock.getStockName(), e.getMessage());
                }
            }

            log.info("[자동매매-{}] 매수 로직 완료 - {}종목 매수", currentMode.name(), buyCount);

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
            // 포트폴리오 조회 (모드에 따라 다른 서비스 사용)
            List<PortfolioItemDto> portfolios = activeTradeService.getPortfolio();

            if (portfolios.isEmpty()) {
                return;
            }

            // 종목코드 리스트
            List<String> stockCodes = portfolios.stream()
                    .map(PortfolioItemDto::getStockCode)
                    .collect(Collectors.toList());

            // 일괄 시세 조회
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

                // 손절 체크 (-3% 이하)
                if (profitRate.compareTo(STOP_LOSS_RATE) <= 0) {
                    reason = "STOP_LOSS";
                    log.info("[자동매매-{}] 손절 조건 충족: {} - 손익률 {}%",
                            currentMode.name(), portfolio.getStockName(), profitRate);
                }
                // 익절 체크 (+5% 이상)
                else if (profitRate.compareTo(TAKE_PROFIT_RATE) >= 0) {
                    reason = "TAKE_PROFIT";
                    log.info("[자동매매-{}] 익절 조건 충족: {} - 손익률 {}%",
                            currentMode.name(), portfolio.getStockName(), profitRate);
                }

                if (reason != null) {
                    try {
                        activeTradeService.sell(
                                portfolio.getStockCode(),
                                currentPrice,
                                portfolio.getQuantity(),
                                reason
                        );
                        lastTradeTime = LocalDateTime.now();
                        todaySellCount.incrementAndGet();

                        log.info("[자동매매-{}] {} 완료: {} x {} @ {}원",
                                currentMode.name(), reason, portfolio.getStockName(),
                                portfolio.getQuantity(), currentPrice);

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
     * - 수급 데이터가 없으면 true 반환 (마법의 공식만으로 매수)
     * - 수급 데이터가 있으면 외국인/기관 순매도가 아닌 경우 매수 허용
     */
    private boolean checkSurgeSignal(String stockCode, Map<String, List<InvestorSurgeDto>> surgeStocks) {
        // 수급 데이터 없으면 마법의 공식만으로 매수 허용
        if (surgeStocks == null || surgeStocks.isEmpty()) {
            log.debug("[자동매매] 수급 데이터 없음 - 마법의 공식만으로 매수 진행");
            return true;
        }

        // 외국인 순매수 확인
        List<InvestorSurgeDto> foreignStocks = surgeStocks.get("FOREIGN");
        boolean foreignBuying = false;
        boolean foreignSelling = false;
        if (foreignStocks != null) {
            for (InvestorSurgeDto s : foreignStocks) {
                if (s.getStockCode().equals(stockCode) && s.getNetBuyAmount() != null) {
                    if (s.getNetBuyAmount().compareTo(BigDecimal.ZERO) > 0) {
                        foreignBuying = true;
                    } else if (s.getNetBuyAmount().compareTo(new BigDecimal("-50")) < 0) {
                        // 50억 이상 순매도면 매수 제외
                        foreignSelling = true;
                    }
                    break;
                }
            }
        }

        // 기관 순매수 확인
        List<InvestorSurgeDto> instStocks = surgeStocks.get("INSTITUTION");
        boolean instBuying = false;
        boolean instSelling = false;
        if (instStocks != null) {
            for (InvestorSurgeDto s : instStocks) {
                if (s.getStockCode().equals(stockCode) && s.getNetBuyAmount() != null) {
                    if (s.getNetBuyAmount().compareTo(BigDecimal.ZERO) > 0) {
                        instBuying = true;
                    } else if (s.getNetBuyAmount().compareTo(new BigDecimal("-50")) < 0) {
                        // 50억 이상 순매도면 매수 제외
                        instSelling = true;
                    }
                    break;
                }
            }
        }

        // 외국인+기관 모두 대규모 순매도면 매수 제외
        if (foreignSelling && instSelling) {
            log.info("[자동매매] {} - 외국인/기관 쌍끌이 매도, 매수 제외", stockCode);
            return false;
        }

        // 외국인 또는 기관이 순매수 중이면 추가 가점
        if (foreignBuying || instBuying) {
            log.info("[자동매매] {} - 수급 신호 양호 (외국인: {}, 기관: {})",
                    stockCode, foreignBuying ? "매수" : "-", instBuying ? "매수" : "-");
        }

        // 대규모 순매도가 아니면 매수 허용
        return true;
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

        log.info("[자동매매-{}] 장 마감 청산(Time-Cut) 실행 시작", currentMode.name());

        try {
            TimeCutResult result = sellAllPortfolio();

            // 텔레그램 알림
            if (telegramService.isEnabled()) {
                sendTimeCutReport(result);
            }

            log.info("[자동매매-{}] 장 마감 청산 완료 - {}종목 매도, 총 손익: {}원",
                    currentMode.name(), result.getSoldCount(), result.getTotalProfitLoss());

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now();
            log.error("[자동매매] 장 마감 청산 오류", e);

            String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
            String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

            if (telegramService.isEnabled()) {
                telegramService.sendMessage(
                        String.format("<b>⚠️ [%s 장 마감] 청산 오류 발생!</b>\n\n", modeTag) +
                        "❌ 에러: " + e.getMessage() + "\n\n" +
                        "━━━━━━━━━━━━━━━━\n" +
                        modeEmoji + " MyPlatform " + modeTag
                );
            }
        }
    }

    /**
     * 전체 포트폴리오 청산 (Time-Cut)
     * @return 청산 결과 (매도 종목 수, 총 손익)
     */
    public TimeCutResult sellAllPortfolio() {
        // 포트폴리오 조회 (모드에 따라 다른 서비스 사용)
        List<PortfolioItemDto> portfolios = activeTradeService.getPortfolio();

        if (portfolios.isEmpty()) {
            log.info("[자동매매-{}] 보유 종목이 없어 장 마감 청산을 건너뜁니다.", currentMode.name());
            return new TimeCutResult(0, BigDecimal.ZERO, List.of());
        }

        // 종목코드 리스트
        List<String> stockCodes = portfolios.stream()
                .map(PortfolioItemDto::getStockCode)
                .collect(Collectors.toList());

        // 일괄 시세 조회
        Map<String, StockPriceDto> prices = stockPriceService.getStockPrices(stockCodes);

        int soldCount = 0;
        BigDecimal totalProfitLoss = BigDecimal.ZERO;
        List<TimeCutItem> soldItems = new java.util.ArrayList<>();

        for (PortfolioItemDto portfolio : portfolios) {
            StockPriceDto priceDto = prices.get(portfolio.getStockCode());
            if (priceDto == null || priceDto.getCurrentPrice() == null) {
                log.warn("[자동매매] {} 시세 조회 실패, 스킵", portfolio.getStockCode());
                continue;
            }

            BigDecimal currentPrice = priceDto.getCurrentPrice();

            try {
                // 전량 매도 (사유: TIME_CUT) - activeTradeService 사용
                TradeHistoryDto result = activeTradeService.sell(
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
                BigDecimal avgPrice = portfolio.getAveragePrice();
                BigDecimal profitRate = BigDecimal.ZERO;
                if (avgPrice != null && avgPrice.compareTo(BigDecimal.ZERO) > 0) {
                    profitRate = currentPrice.subtract(avgPrice)
                            .divide(avgPrice, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));
                }

                soldItems.add(new TimeCutItem(
                        portfolio.getStockName(),
                        portfolio.getStockCode(),
                        portfolio.getQuantity(),
                        currentPrice,
                        profitLoss,
                        profitRate
                ));

                log.info("[자동매매-{}] TIME_CUT 매도: {} x {} @ {}원, 손익: {}원",
                        currentMode.name(), portfolio.getStockName(), portfolio.getQuantity(),
                        currentPrice, profitLoss);

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

        String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
        String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";
        String profitEmoji = result.getTotalProfitLoss().compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
        String profitSign = result.getTotalProfitLoss().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";

        message.append(String.format("<b>🔔 [%s 장 마감] 금일 매매 종료!</b>\n\n", modeTag));
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
        message.append(modeEmoji).append(" MyPlatform ").append(modeTag);

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
