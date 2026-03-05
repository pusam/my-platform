package com.myplatform.backend.service;

import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.dto.PaperTradingDto.AccountSummaryDto;
import com.myplatform.backend.dto.PaperTradingDto.BotStatusDto;
import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.dto.PaperTradingDto.TradeHistoryDto;
import com.myplatform.backend.dto.ScalpingAnalysisDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.dto.TechnicalIndicatorsDto;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 자동 매매 봇 서비스 (스캘핑 스나이퍼 모드)
 *
 * ========================================
 * [전략 명세서] - SCALPING SNIPER MODE
 * ========================================
 *
 * 1. 매수 조건 (Strict Entry) - 6가지 모두 만족 시 시장가 진입
 *    A. 실시간 체결강도 100% 이상 유지 중
 *    B. 순매수금액 3억 이상 (수급 급증)
 *    C. 현재가 > 시초가 (양봉 상태)
 *    D. RSI(14) < 80 (과열 구간 진입 금지)
 *    E. 20MA 이격도 < +15% (급등 구간 진입 금지)
 *    F. 갭상승 < +8% (갭상승 과다 진입 금지)
 *
 * 2. 매도 조건 (Auto Exit) - 1초 간격 감시
 *    A. 익절 1차: +1.5% 도달 시 절반 매도
 *    B. 익절 2차: 트레일링 스탑 (고점 대비 -0.5% 하락 시 전량 매도)
 *    C. 손절: -1.2% 터치 시 즉시 전량 손절
 *    D. 타임컷: 매수 후 5분 초과 시 무조건 전량 매도
 *    E. 재매수 쿨다운: 매도 후 30분간 같은 종목 재매수 금지
 *
 * 3. 운용 설정
 *    A. 감시 대상: 수급 급증 탭에 잡힌 종목들만
 *    B. 킬 스위치: 하루 최대 손실 -3% 초과 시 봇 자동 종료
 *    C. 운용 시간: 09:05~15:00 (점심 휴식 없이 연속)
 *
 * ========================================
 */
@Service
@Slf4j
public class AutoTradingBotService {

    private final VirtualTradeService virtualTradeService;
    private final RealTradeService realTradeService;
    private final VirtualPortfolioRepository portfolioRepository;
    private final InvestorSurgeService investorSurgeService;
    private final ScalpingAnalysisService scalpingAnalysisService;
    private final StockPriceService stockPriceService;
    private final TelegramNotificationService telegramService;
    private final BotConfigRepository botConfigRepository;
    private final TechnicalIndicatorService technicalIndicatorService;
    private final KoreaInvestmentService kisService;

    // ========== 스캘핑 전략 상수 ==========
    private static final BigDecimal STOP_LOSS_RATE = new BigDecimal("-1.2");     // 손절: -1.2%
    private static final BigDecimal TAKE_PROFIT_FIRST = new BigDecimal("1.5");   // 익절 1차: +1.5% (절반 매도)
    private static final BigDecimal TRAILING_STOP_RATE = new BigDecimal("-0.5"); // 트레일링: 고점 대비 -0.5%
    private static final BigDecimal MIN_VOLUME_POWER = new BigDecimal("120");    // 최소 체결강도: 120% (STRONG_BUY 기준)
    private static final BigDecimal MIN_NET_BUY_AMOUNT = new BigDecimal("3");    // 최소 순매수금액: 3억
    private static final int TIME_CUT_MINUTES = 5;                                // 타임컷: 5분
    private static final long MIN_TRADING_VALUE = 50_000_000_000L;               // 최소 거래대금: 500억원
    private static final int MIN_VOLUME_RATIO = 200;                              // 전일 대비 거래량: 200%
    private static final BigDecimal MAX_INVESTMENT_RATIO = new BigDecimal("0.15"); // 종목당 최대 15%
    private static final int MAX_HOLDING_STOCKS = 3;                              // 최대 보유 종목 수
    private static final BigDecimal KILL_SWITCH_RATE = new BigDecimal("-3.0");   // 킬 스위치: -3%
    private static final int SELL_COOLDOWN_MINUTES = 30;                        // 매도 후 재매수 쿨다운: 30분
    private static final BigDecimal RSI_ENTRY_LIMIT = new BigDecimal("80");      // RSI 진입 상한
    private static final BigDecimal DISPARITY_20MA_LIMIT = new BigDecimal("15"); // 20MA 이격도 상한 (%)
    private static final BigDecimal GAP_UP_LIMIT = new BigDecimal("8");          // 갭상승 상한 (%)

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

    // ========== 스캘핑 전용 상태 ==========
    // 종목별 매수 정보 (고점 추적, 매수 시간, 절반 익절 여부)
    private final Map<String, ScalpingPosition> scalpingPositions = new ConcurrentHashMap<>();
    // 종목별 마지막 매도 시간 (재매수 쿨다운용)
    private final Map<String, LocalDateTime> sellCooldownMap = new ConcurrentHashMap<>();
    // 당일 시작 자산 (킬 스위치용)
    private volatile BigDecimal dailyStartAsset = BigDecimal.ZERO;
    // 킬 스위치 발동 여부
    private final AtomicBoolean killSwitchTriggered = new AtomicBoolean(false);

    /**
     * 스캘핑 포지션 정보
     */
    private static class ScalpingPosition {
        String stockCode;
        String stockName;
        BigDecimal buyPrice;           // 매수가
        LocalDateTime buyTime;         // 매수 시간
        BigDecimal highPrice;          // 최고가 (트레일링용)
        boolean halfSold;              // 절반 익절 완료 여부
        int originalQuantity;          // 원래 수량

        ScalpingPosition(String stockCode, String stockName, BigDecimal buyPrice, int quantity) {
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.buyPrice = buyPrice;
            this.buyTime = LocalDateTime.now();
            this.highPrice = buyPrice;
            this.halfSold = false;
            this.originalQuantity = quantity;
        }

        void updateHighPrice(BigDecimal currentPrice) {
            if (currentPrice.compareTo(highPrice) > 0) {
                highPrice = currentPrice;
            }
        }
    }

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
            InvestorSurgeService investorSurgeService,
            ScalpingAnalysisService scalpingAnalysisService,
            StockPriceService stockPriceService,
            TelegramNotificationService telegramService,
            BotConfigRepository botConfigRepository,
            TechnicalIndicatorService technicalIndicatorService,
            KoreaInvestmentService kisService) {
        this.virtualTradeService = virtualTradeService;
        this.realTradeService = realTradeService;
        this.portfolioRepository = portfolioRepository;
        this.investorSurgeService = investorSurgeService;
        this.scalpingAnalysisService = scalpingAnalysisService;
        this.stockPriceService = stockPriceService;
        this.telegramService = telegramService;
        this.botConfigRepository = botConfigRepository;
        this.technicalIndicatorService = technicalIndicatorService;
        this.kisService = kisService;
        this.activeTradeService = virtualTradeService;
    }

    // ==================== 봇 상태 관리 ====================

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void restoreBotStateOnStartup() {
        try {
            Thread.sleep(5000);

            BotState savedState = loadBotState();
            if (savedState != null && STATUS_RUNNING.equals(savedState.status)) {
                log.info("[스캘핑봇] 서버 재시작 감지 - 이전 상태 복구 중... (모드: {})", savedState.mode);

                TradingMode mode = TradingMode.valueOf(savedState.mode);
                currentMode = mode;
                activeTradeService = (currentMode == TradingMode.REAL) ? realTradeService : virtualTradeService;
                botActive.set(true);
                resetDailyCounters();
                initializeDailyAsset();

                log.info("[스캘핑봇] 봇 자동 재시작 완료 - 모드: {}", currentMode.getDisplayName());

                if (telegramService.isEnabled()) {
                    String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
                    String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

                    telegramService.sendMessage(
                            String.format("<b>🔄 [%s] 서버 재시작 - 스캘핑봇 자동 복구!</b>\n\n", modeTag) +
                            "✅ 서버 재시작으로 봇을 자동 재실행했습니다.\n" +
                            "🎯 전략: <b>스캘핑 스나이퍼 모드</b>\n" +
                            "📌 모드: <b>" + currentMode.getDisplayName() + "</b>\n\n" +
                            "━━━━━━━━━━━━━━━━\n" +
                            modeEmoji + " MyPlatform " + modeTag
                    );
                }
            } else {
                log.info("[스캘핑봇] 서버 시작 - 봇 비활성화 상태 유지");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[스캘핑봇] 봇 상태 복구 중단됨");
        } catch (Exception e) {
            log.error("[스캘핑봇] 봇 상태 복구 실패: {}", e.getMessage(), e);
        }
    }

    @Transactional
    protected void saveBotState(String status, TradingMode mode) {
        try {
            BotConfig config = botConfigRepository.findByConfigKey(BOT_CONFIG_KEY)
                    .orElse(BotConfig.builder().configKey(BOT_CONFIG_KEY).build());

            config.setIsActive(STATUS_RUNNING.equals(status));
            config.setTradingMode(mode.name());
            config.setLastStatusChange(LocalDateTime.now());

            botConfigRepository.save(config);
            log.debug("[스캘핑봇] 봇 상태 DB 저장: status={}, mode={}", status, mode);

        } catch (Exception e) {
            log.warn("[스캘핑봇] 봇 상태 DB 저장 실패: {}", e.getMessage());
        }
    }

    private BotState loadBotState() {
        try {
            return botConfigRepository.findByConfigKey(BOT_CONFIG_KEY)
                    .map(config -> new BotState(
                            config.getIsActive() ? STATUS_RUNNING : STATUS_STOPPED,
                            config.getTradingMode() != null ? config.getTradingMode() : TradingMode.VIRTUAL.name()
                    ))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[스캘핑봇] 봇 상태 DB 로드 실패: {}", e.getMessage());
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

    public BotStatusDto startBot(TradingMode mode) {
        if (botActive.get()) {
            log.info("[스캘핑봇] 이미 실행 중입니다. 현재 모드: {}", currentMode.getDisplayName());
            return getBotStatus();
        }

        currentMode = mode != null ? mode : TradingMode.VIRTUAL;
        activeTradeService = (currentMode == TradingMode.REAL) ? realTradeService : virtualTradeService;

        botActive.set(true);
        killSwitchTriggered.set(false);
        scalpingPositions.clear();
        resetDailyCounters();
        initializeDailyAsset();
        saveBotState(STATUS_RUNNING, currentMode);

        log.info("[스캘핑봇] 시작됨 - 모드: {}", currentMode.getDisplayName());

        if (telegramService.isEnabled()) {
            String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
            String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

            telegramService.sendMessage(
                    String.format("<b>%s [%s] 스캘핑 스나이퍼 봇 시작!</b>\n\n", modeEmoji, modeTag) +
                    "✅ 봇이 활성화되었습니다.\n" +
                    "🎯 전략: <b>스캘핑 스나이퍼 모드 v2</b>\n\n" +
                    "━━━ 매수 조건 (6가지 동시 충족) ━━━\n" +
                    "1️⃣ 체결강도 100% 이상\n" +
                    "2️⃣ 순매수금액 3억↑ (수급 급증)\n" +
                    "3️⃣ 현재가 > 시초가 (양봉)\n" +
                    "4️⃣ RSI(14) < 80 (과열 금지)\n" +
                    "5️⃣ 20MA 이격도 < +15%\n" +
                    "6️⃣ 갭상승 < +8%\n\n" +
                    "━━━ 매도 조건 (3초 감시) ━━━\n" +
                    "🟢 익절 1차: +2.5% → 절반 매도\n" +
                    "🟢 익절 2차: 고점 -1% → 전량 매도\n" +
                    "🔴 손절: -1.5% → 전량 손절\n" +
                    "⏰ 타임컷: 10분 초과 → 전량 매도\n\n" +
                    "━━━ 리스크 관리 ━━━\n" +
                    "🛑 킬 스위치: 일일 손실 -3% 초과 시 봇 종료\n" +
                    "📦 최대 보유: 3종목\n" +
                    "🕐 오전장: 09:05~11:00 / 오후장: 13:30~15:00\n\n" +
                    "━━━━━━━━━━━━━━━━\n" +
                    modeEmoji + " MyPlatform " + modeTag
            );
        }

        return getBotStatus();
    }

    public BotStatusDto startBot() {
        return startBot(TradingMode.VIRTUAL);
    }

    public BotStatusDto stopBot() {
        if (!botActive.get()) {
            log.info("[스캘핑봇] 이미 중지 상태입니다.");
            return getBotStatus();
        }

        String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
        String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

        botActive.set(false);
        saveBotState(STATUS_STOPPED, currentMode);

        log.info("[스캘핑봇] 중지됨 - 모드: {}", currentMode.getDisplayName());

        if (telegramService.isEnabled()) {
            telegramService.sendMessage(
                    String.format("<b>%s [%s] 스캘핑봇 중지!</b>\n\n", modeEmoji, modeTag) +
                    "⏸️ 봇이 비활성화되었습니다.\n\n" +
                    "━━━━━━━━━━━━━━━━\n" +
                    modeEmoji + " MyPlatform " + modeTag
            );
        }

        return getBotStatus();
    }

    public BotStatusDto getBotStatus() {
        resetDailyCounters();

        String status;
        if (killSwitchTriggered.get()) {
            status = "KILL_SWITCH";
        } else if (!botActive.get()) {
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

    // ==================== 매수 로직 (스캘핑) ====================

    /**
     * 스캘핑 매수 로직
     * - 실행 시간: 09:00~10:30 (장 초반 변동성 집중, 매초, 평일만)
     * - 수급 급증 종목 중 스캘핑 조건 충족 시 진입
     */
    @Scheduled(cron = "*/1 * 9-10 * * MON-FRI", zone = "Asia/Seoul")
    public void executeScalpingBuyLogic() {
        if (!botActive.get() || killSwitchTriggered.get()) {
            return;
        }

        // 09:00~10:30만 매수 허용
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 0)) || now.isAfter(LocalTime.of(10, 30))) {
            return;
        }

        if (isMarketClosed()) {
            return;
        }

        log.info("[스캘핑봇] ===== 매수 로직 시작 ({}) =====", LocalTime.now());
        resetDailyCounters();

        try {
            // 킬 스위치 체크
            if (checkKillSwitch()) {
                return;
            }

            // 현재 보유 종목 수 확인
            List<PortfolioItemDto> currentPortfolio = activeTradeService.getPortfolio();
            if (currentPortfolio.size() >= MAX_HOLDING_STOCKS) {
                log.debug("[스캘핑봇] 최대 보유 종목 수({}) 도달", MAX_HOLDING_STOCKS);
                return;
            }

            // 수급 급증 종목 조회
            Map<String, List<InvestorSurgeDto>> surgeStocks = investorSurgeService.getAllSurgeStocks(BigDecimal.ZERO);
            if (surgeStocks == null || surgeStocks.isEmpty()) {
                log.info("[스캘핑봇] 수급 급증 데이터 없음 — 스냅샷 미수집 상태");
                return;
            }

            // 외국인 + 기관 순매수 종목 합치기 (중복 제거)
            List<InvestorSurgeDto> targetStocks = mergeAndFilterSurgeStocks(surgeStocks);
            if (targetStocks.isEmpty()) {
                log.info("[스캘핑봇] 수급 급증 후보 0종목 — 외국인/기관 순매수 종목 없음");
                return;
            }

            log.info("[스캘핑봇] 수급 급증 후보: {}종목", targetStocks.size());

            // 계좌 정보 조회
            AccountSummaryDto accountSummary = activeTradeService.getAccountSummary();
            BigDecimal totalAsset = accountSummary.getCurrentBalance().add(
                    accountSummary.getTotalEvaluation() != null ? accountSummary.getTotalEvaluation() : BigDecimal.ZERO);
            BigDecimal maxPerStock = totalAsset.multiply(MAX_INVESTMENT_RATIO);

            // 이미 보유 중인 종목 코드
            List<String> holdingCodes = currentPortfolio.stream()
                    .map(PortfolioItemDto::getStockCode)
                    .collect(Collectors.toList());

            BigDecimal currentBalance = accountSummary.getCurrentBalance();

            for (InvestorSurgeDto surge : targetStocks) {
                // 잔액 확인
                if (currentBalance.compareTo(new BigDecimal("100000")) < 0) {
                    log.info("[스캘핑봇] 잔액 부족");
                    break;
                }

                // 이미 보유 중이거나 매수 진행 중인 종목 스킵
                if (holdingCodes.contains(surge.getStockCode()) || scalpingPositions.containsKey(surge.getStockCode())) {
                    continue;
                }

                // 쿨다운: 최근 30분 이내 매도한 종목 재매수 금지
                LocalDateTime lastSell = sellCooldownMap.get(surge.getStockCode());
                if (lastSell != null && java.time.Duration.between(lastSell, LocalDateTime.now()).toMinutes() < SELL_COOLDOWN_MINUTES) {
                    log.debug("[스캘핑봇] 쿨다운: {} - 매도 후 {}분 경과 (기준: {}분)",
                            surge.getStockName(), java.time.Duration.between(lastSell, LocalDateTime.now()).toMinutes(), SELL_COOLDOWN_MINUTES);
                    continue;
                }

                // ★ 스캘핑 진입 조건 체크 ★
                ScalpingEntryResult entryResult = checkScalpingEntry(surge);
                if (!entryResult.canEnter) {
                    continue;
                }

                // 매수 수량 계산
                BigDecimal investAmount = currentBalance.compareTo(maxPerStock) < 0 ? currentBalance : maxPerStock;
                int quantity = investAmount.divide(entryResult.currentPrice, 0, RoundingMode.DOWN).intValue();

                if (quantity <= 0) {
                    continue;
                }

                // 매수 실행
                try {
                    activeTradeService.buy(surge.getStockCode(), surge.getStockName(),
                            entryResult.currentPrice, quantity, "SCALPING_ENTRY");
                    lastTradeTime = LocalDateTime.now();
                    todayBuyCount.incrementAndGet();

                    // 스캘핑 포지션 등록
                    scalpingPositions.put(surge.getStockCode(),
                            new ScalpingPosition(surge.getStockCode(), surge.getStockName(),
                                    entryResult.currentPrice, quantity));

                    log.info("[스캘핑봇-{}] ★ 진입 완료 ★", currentMode.name());
                    log.info("  종목: {} ({})", surge.getStockName(), surge.getStockCode());
                    log.info("  매수가: {}원 x {}주 = {}원",
                            formatNumber(entryResult.currentPrice), quantity,
                            formatNumber(entryResult.currentPrice.multiply(BigDecimal.valueOf(quantity))));
                    log.info("  체결강도: {}% | 거래대금: {}억 | 시초가대비: +{}%",
                            entryResult.volumePower, entryResult.tradeAmount,
                            entryResult.openChangeRate);

                    // 텔레그램 알림
                    sendScalpingBuyNotification(surge, entryResult, quantity);

                    // 잔고 갱신
                    AccountSummaryDto refreshed = activeTradeService.getAccountSummary();
                    currentBalance = refreshed.getCurrentBalance();
                    holdingCodes.add(surge.getStockCode());

                    // 최대 종목 수 체크
                    if (holdingCodes.size() >= MAX_HOLDING_STOCKS) {
                        log.info("[스캘핑봇] 최대 보유 종목 수 도달");
                        break;
                    }

                    Thread.sleep(currentMode == TradingMode.REAL ? 1000 : 300);

                } catch (Exception e) {
                    log.error("[스캘핑봇] 매수 실패: {} - {}", surge.getStockName(), e.getMessage());
                }
            }

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now();
            log.error("[스캘핑봇] 매수 로직 오류", e);
        }
    }

    /**
     * 스캘핑 진입 조건 체크
     * 1. 체결강도 100% 이상
     * 2. 순매수금액 3억 이상
     * 3. 현재가 > 시초가 (양봉)
     * 4. RSI(14) < 80 (과열 금지)
     * 5. 20MA 이격도 < +15% (급등 금지)
     * 6. 갭상승 < +8% (갭상승 과다 금지)
     */
    private ScalpingEntryResult checkScalpingEntry(InvestorSurgeDto surge) {
        String stockName = surge.getStockName();
        String stockCode = surge.getStockCode();

        try {
            // ===== 조건 1: 순매수금액 3억 이상 (DB 데이터, API 호출 불필요 → 최우선 체크) =====
            BigDecimal netBuyAmount = surge.getNetBuyAmount();
            if (netBuyAmount == null || netBuyAmount.compareTo(MIN_NET_BUY_AMOUNT) < 0) {
                log.debug("[스캘핑봇] Skip [{}({})] 순매수 부족 (현재: {}억 < 기준: {}억)",
                        stockName, stockCode, netBuyAmount, MIN_NET_BUY_AMOUNT);
                return ScalpingEntryResult.fail("순매수 부족: " + netBuyAmount + "억");
            }

            // ===== 현재가 조회 (이후 조건들에 필요) =====
            StockPriceDto priceDto = stockPriceService.getStockPrice(stockCode);
            if (priceDto == null || priceDto.getCurrentPrice() == null) {
                log.debug("[스캘핑봇] Skip [{}({})] 현재가 조회 실패", stockName, stockCode);
                return ScalpingEntryResult.fail("현재가 조회 실패");
            }

            BigDecimal currentPrice = priceDto.getCurrentPrice();
            BigDecimal openPrice = priceDto.getOpenPrice();

            // ===== 조건: 거래대금 > 500억 OR 전일 대비 거래량 > 200% =====
            BigDecimal tradingValue = priceDto.getAccumulatedTradingValue();
            boolean tradingValueOk = tradingValue != null
                    && tradingValue.compareTo(new BigDecimal(MIN_TRADING_VALUE)) > 0;

            boolean volumeRatioOk = false;
            if (priceDto.getPreviousDayVolume() != null
                    && priceDto.getPreviousDayVolume().compareTo(BigDecimal.ZERO) > 0
                    && priceDto.getVolume() != null) {
                BigDecimal ratio = priceDto.getVolume()
                        .divide(priceDto.getPreviousDayVolume(), 2, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                volumeRatioOk = ratio.compareTo(new BigDecimal(MIN_VOLUME_RATIO)) >= 0;
            }

            if (!tradingValueOk && !volumeRatioOk) {
                log.debug("[스캘핑봇] Skip [{}({})] 거래대금/거래량 부족 (거래대금: {}, 전일거래량비율 충족: {})",
                        stockName, stockCode, tradingValue, volumeRatioOk);
                return ScalpingEntryResult.fail("거래대금/거래량 부족");
            }

            // ===== 조건 2: 체결강도 100% 이상 (데이터 없으면 진입 불가) =====
            BigDecimal volumePower = null;
            try {
                ScalpingAnalysisDto scalpingData = scalpingAnalysisService.getVolumePowerRefresh(stockCode);
                if (scalpingData != null && scalpingData.getVolumePower() != null) {
                    volumePower = scalpingData.getVolumePower();
                    if (volumePower.compareTo(MIN_VOLUME_POWER) < 0) {
                        log.debug("[스캘핑봇] Skip [{}({})] 체결강도 부족 (현재: {}% < 기준: {}%)",
                                stockName, stockCode, volumePower, MIN_VOLUME_POWER);
                        return ScalpingEntryResult.fail("체결강도 부족: " + volumePower + "%");
                    }
                } else {
                    log.debug("[스캘핑봇] Skip [{}({})] 체결강도 데이터 없음 — 진입 불가", stockName, stockCode);
                    return ScalpingEntryResult.fail("체결강도 데이터 없음");
                }
            } catch (Exception e) {
                log.debug("[스캘핑봇] Skip [{}({})] 체결강도 조회 실패 — 진입 불가: {}", stockName, stockCode, e.getMessage());
                return ScalpingEntryResult.fail("체결강도 조회 실패");
            }

            // ===== 조건 3: 현재가 > 시초가 (양봉) =====
            if (openPrice == null || openPrice.compareTo(BigDecimal.ZERO) <= 0) {
                BigDecimal changeRate = priceDto.getChangeRate();
                if (changeRate == null || changeRate.compareTo(BigDecimal.ZERO) <= 0) {
                    log.debug("[스캘핑봇] Skip [{}({})] 양봉 조건 미충족 (등락률: {}%)",
                            stockName, stockCode, changeRate);
                    return ScalpingEntryResult.fail("양봉 조건 미충족");
                }
            } else if (currentPrice.compareTo(openPrice) <= 0) {
                log.debug("[스캘핑봇] Skip [{}({})] 음봉 (현재가: {} <= 시초가: {})",
                        stockName, stockCode, currentPrice, openPrice);
                return ScalpingEntryResult.fail("현재가 <= 시초가");
            }

            // 시초가 대비 상승률 계산
            BigDecimal openChangeRate = BigDecimal.ZERO;
            if (openPrice != null && openPrice.compareTo(BigDecimal.ZERO) > 0) {
                openChangeRate = currentPrice.subtract(openPrice)
                        .divide(openPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }

            // ===== 조건 4: RSI / 이격도 (실패 시 진입 허용) =====
            try {
                List<BigDecimal> closePrices = kisService.getDailyClosePrices(stockCode, 30);
                if (closePrices != null && closePrices.size() >= 14) {
                    TechnicalIndicatorsDto indicators = technicalIndicatorService.calculate(closePrices);

                    if (indicators.getRsi14() != null && indicators.getRsi14().compareTo(RSI_ENTRY_LIMIT) >= 0) {
                        log.debug("[스캘핑봇] Skip [{}({})] RSI 과열 (현재: {} >= 기준: {})",
                                stockName, stockCode, indicators.getRsi14(), RSI_ENTRY_LIMIT);
                        return ScalpingEntryResult.fail("RSI 과열: " + indicators.getRsi14());
                    }

                    if (indicators.getMa20() != null && indicators.getMa20().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal disparity = currentPrice.subtract(indicators.getMa20())
                                .divide(indicators.getMa20(), 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"));
                        if (disparity.compareTo(DISPARITY_20MA_LIMIT) >= 0) {
                            log.debug("[스캘핑봇] Skip [{}({})] 이격도 과다 (현재: {}% >= 기준: {}%)",
                                    stockName, stockCode, disparity.setScale(2, RoundingMode.HALF_UP), DISPARITY_20MA_LIMIT);
                            return ScalpingEntryResult.fail("이격도 과다: " + disparity.setScale(2, RoundingMode.HALF_UP) + "%");
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[스캘핑봇] RSI/이격도 체크 실패 (진입 허용): {} - {}", stockCode, e.getMessage());
            }

            // ===== 조건 5: 갭상승 +8% 이상 → 매수 금지 =====
            if (openPrice != null && openPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal changePrice = priceDto.getChangePrice();
                if (changePrice != null) {
                    BigDecimal prevClose = currentPrice.subtract(changePrice);
                    if (prevClose.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal gapRate = openPrice.subtract(prevClose)
                                .divide(prevClose, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"));
                        if (gapRate.compareTo(GAP_UP_LIMIT) >= 0) {
                            log.debug("[스캘핑봇] Skip [{}({})] 갭상승 과다 (현재: {}% >= 기준: {}%)",
                                    stockName, stockCode, gapRate.setScale(2, RoundingMode.HALF_UP), GAP_UP_LIMIT);
                            return ScalpingEntryResult.fail("갭상승 과다: " + gapRate.setScale(2, RoundingMode.HALF_UP) + "%");
                        }
                    }
                }
            }

            log.info("[스캘핑봇] ✓ 진입 조건 충족 [{}({})] 체결강도: {}%, 순매수: {}억, 시초가대비: +{}%",
                    stockName, stockCode, volumePower != null ? volumePower : "N/A", netBuyAmount,
                    openChangeRate.setScale(2, RoundingMode.HALF_UP));

            return ScalpingEntryResult.success(currentPrice,
                    volumePower != null ? volumePower : BigDecimal.ZERO, netBuyAmount, openChangeRate);

        } catch (Exception e) {
            log.error("[스캘핑봇] 진입 조건 체크 실패: {}({}) - {}", stockName, stockCode, e.getMessage());
            return ScalpingEntryResult.fail("체크 오류");
        }
    }

    private static class ScalpingEntryResult {
        boolean canEnter;
        String reason;
        BigDecimal currentPrice;
        BigDecimal volumePower;
        BigDecimal tradeAmount;
        BigDecimal openChangeRate;

        static ScalpingEntryResult fail(String reason) {
            ScalpingEntryResult r = new ScalpingEntryResult();
            r.canEnter = false;
            r.reason = reason;
            return r;
        }

        static ScalpingEntryResult success(BigDecimal price, BigDecimal volume, BigDecimal trade, BigDecimal change) {
            ScalpingEntryResult r = new ScalpingEntryResult();
            r.canEnter = true;
            r.currentPrice = price;
            r.volumePower = volume;
            r.tradeAmount = trade;
            r.openChangeRate = change;
            return r;
        }
    }

    // ==================== 매도 로직 (스캘핑) ====================

    /**
     * 스캘핑 매도 로직
     * - 익절/손절/트레일링/타임컷 체크
     * - 1초 간격으로 실행 (09:00~15:20, 점심시간 포함)
     */
    @Scheduled(cron = "*/1 * 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void executeScalpingSellLogic() {
        if (!botActive.get()) {
            return;
        }

        if (isMarketClosed()) {
            return;
        }

        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 0)) || now.isAfter(LocalTime.of(15, 20))) {
            return;
        }

        try {
            // 킬 스위치 체크
            if (checkKillSwitch()) {
                return;
            }

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
                ScalpingPosition position = scalpingPositions.get(portfolio.getStockCode());

                if (position == null) {
                    // 포지션 정보 없으면 새로 생성 (기존 보유 종목)
                    position = new ScalpingPosition(portfolio.getStockCode(), portfolio.getStockName(),
                            portfolio.getAveragePrice(), portfolio.getQuantity());
                    scalpingPositions.put(portfolio.getStockCode(), position);
                }

                // 고점 갱신
                position.updateHighPrice(currentPrice);

                // 손익률 계산
                BigDecimal profitRate = currentPrice.subtract(position.buyPrice)
                        .divide(position.buyPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                // 고점 대비 하락률 계산
                BigDecimal highDropRate = currentPrice.subtract(position.highPrice)
                        .divide(position.highPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                // 매수 후 경과 시간
                long minutesElapsed = java.time.Duration.between(position.buyTime, LocalDateTime.now()).toMinutes();

                String sellReason = null;
                int sellQuantity = portfolio.getQuantity();
                boolean isPartialSell = false;

                // 1. 손절 체크 (-1.2%)
                if (profitRate.compareTo(STOP_LOSS_RATE) <= 0) {
                    sellReason = "STOP_LOSS";
                    log.info("[스캘핑봇] 손절 조건: {} - 손익률 {}%", portfolio.getStockName(), profitRate);
                }
                // 2. 익절 1차 체크 (+1.3% 절반 매도)
                else if (!position.halfSold && profitRate.compareTo(TAKE_PROFIT_FIRST) >= 0) {
                    sellReason = "TAKE_PROFIT_HALF";
                    sellQuantity = portfolio.getQuantity() / 2;
                    if (sellQuantity > 0) {
                        isPartialSell = true;
                        position.halfSold = true;
                        log.info("[스캘핑봇] 1차 익절: {} - 손익률 {}%, 절반({}) 매도",
                                portfolio.getStockName(), profitRate, sellQuantity);
                    }
                }
                // 3. 트레일링 스탑 체크 (고점 대비 -0.5%)
                else if (position.halfSold && highDropRate.compareTo(TRAILING_STOP_RATE) <= 0) {
                    sellReason = "TRAILING_STOP";
                    log.info("[스캘핑봇] 트레일링 스탑: {} - 고점대비 {}%", portfolio.getStockName(), highDropRate);
                }
                // 4. 타임컷 체크 (5분 경과 → 무조건 전량 매도)
                else if (minutesElapsed >= TIME_CUT_MINUTES) {
                    sellReason = "TIME_CUT";
                    log.info("[스캘핑봇] 타임컷: {} - {}분 경과, 손익률 {}%",
                            portfolio.getStockName(), minutesElapsed, profitRate);
                }

                if (sellReason != null && sellQuantity > 0) {
                    executeScalpingSell(portfolio, currentPrice, position.buyPrice, profitRate,
                            sellQuantity, sellReason, isPartialSell);
                }
            }

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now();
            log.error("[스캘핑봇] 매도 로직 오류", e);
        }
    }

    /**
     * 스캘핑 매도 실행
     */
    private void executeScalpingSell(PortfolioItemDto portfolio, BigDecimal currentPrice,
                                     BigDecimal buyPrice, BigDecimal profitRate,
                                     int quantity, String reason, boolean isPartialSell) {
        try {
            activeTradeService.sell(portfolio.getStockCode(), currentPrice, quantity, reason);
            lastTradeTime = LocalDateTime.now();
            todaySellCount.incrementAndGet();

            BigDecimal profitLoss = currentPrice.subtract(buyPrice).multiply(BigDecimal.valueOf(quantity));

            log.info("[스캘핑봇-{}] {} 완료: {} x {} @ {}원, 손익: {}원",
                    currentMode.name(), reason, portfolio.getStockName(),
                    quantity, formatNumber(currentPrice), formatNumber(profitLoss));

            // 전량 매도 시 포지션 정리 + 쿨다운 기록
            if (!isPartialSell) {
                scalpingPositions.remove(portfolio.getStockCode());
                sellCooldownMap.put(portfolio.getStockCode(), LocalDateTime.now());
            }

            // 텔레그램 알림
            sendScalpingSellNotification(portfolio, currentPrice, buyPrice, profitRate,
                    profitLoss, quantity, reason, isPartialSell);

        } catch (Exception e) {
            log.error("[스캘핑봇] 매도 실패: {} - {}", portfolio.getStockName(), e.getMessage());
        }
    }

    // ==================== 장 마감 청산 ====================

    @Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Seoul")
    public void executeEndOfDayClearance() {
        if (!botActive.get()) {
            return;
        }

        if (isMarketClosed()) {
            return;
        }

        log.info("[스캘핑봇] ===== 장 마감 청산 시작 =====");

        try {
            TimeCutResult result = sellAllPortfolio();

            if (telegramService.isEnabled()) {
                sendEndOfDayReport(result);
            }

            // 포지션 정리
            scalpingPositions.clear();

            log.info("[스캘핑봇] 장 마감 청산 완료 - {}종목 매도, 총 손익: {}원",
                    result.soldCount, formatNumber(result.totalProfitLoss));

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now();
            log.error("[스캘핑봇] 장 마감 청산 오류", e);
        }
    }

    public TimeCutResult sellAllPortfolio() {
        List<PortfolioItemDto> portfolios = activeTradeService.getPortfolio();

        if (portfolios.isEmpty()) {
            log.info("[스캘핑봇] 보유 종목 없음 - 청산 스킵");
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
                        portfolio.getStockCode(), currentPrice, portfolio.getQuantity(), "END_OF_DAY");

                lastTradeTime = LocalDateTime.now();
                todaySellCount.incrementAndGet();
                soldCount++;
                sellCooldownMap.put(portfolio.getStockCode(), LocalDateTime.now());

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

                log.info("[스캘핑봇] 장마감 청산: {} x {} @ {}원, 손익: {}원",
                        portfolio.getStockName(), portfolio.getQuantity(),
                        formatNumber(currentPrice), formatNumber(profitLoss));

                Thread.sleep(300);

            } catch (Exception e) {
                log.error("[스캘핑봇] 장마감 청산 실패: {} - {}", portfolio.getStockName(), e.getMessage());
            }
        }

        return new TimeCutResult(soldCount, totalProfitLoss, soldItems);
    }

    // ==================== 킬 스위치 ====================

    /**
     * 킬 스위치 체크
     * - 하루 손실이 시작 자산의 -3% 초과 시 봇 종료
     */
    private boolean checkKillSwitch() {
        if (killSwitchTriggered.get()) {
            return true;
        }

        try {
            AccountSummaryDto account = activeTradeService.getAccountSummary();
            BigDecimal currentAsset = account.getCurrentBalance().add(
                    account.getTotalEvaluation() != null ? account.getTotalEvaluation() : BigDecimal.ZERO);

            if (dailyStartAsset.compareTo(BigDecimal.ZERO) <= 0) {
                dailyStartAsset = currentAsset;
                return false;
            }

            BigDecimal dailyProfitRate = currentAsset.subtract(dailyStartAsset)
                    .divide(dailyStartAsset, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

            if (dailyProfitRate.compareTo(KILL_SWITCH_RATE) <= 0) {
                killSwitchTriggered.set(true);
                botActive.set(false);
                saveBotState(STATUS_STOPPED, currentMode);

                log.warn("[스캘핑봇] 🛑 킬 스위치 발동! 일일 손실: {}%", dailyProfitRate);

                if (telegramService.isEnabled()) {
                    String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
                    String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

                    telegramService.sendMessage(
                            String.format("<b>🛑 [%s] 킬 스위치 발동!</b>\n\n", modeTag) +
                            "⚠️ 일일 최대 손실 한도 초과로 봇을 자동 종료합니다.\n\n" +
                            "📊 시작 자산: " + formatNumber(dailyStartAsset) + "원\n" +
                            "📊 현재 자산: " + formatNumber(currentAsset) + "원\n" +
                            "📉 일일 손익: " + String.format("%.2f", dailyProfitRate) + "%\n\n" +
                            "━━━━━━━━━━━━━━━━\n" +
                            modeEmoji + " MyPlatform " + modeTag
                    );
                }

                return true;
            }

        } catch (Exception e) {
            log.error("[스캘핑봇] 킬 스위치 체크 오류: {}", e.getMessage());
        }

        return false;
    }

    private void initializeDailyAsset() {
        try {
            AccountSummaryDto account = activeTradeService.getAccountSummary();
            dailyStartAsset = account.getCurrentBalance().add(
                    account.getTotalEvaluation() != null ? account.getTotalEvaluation() : BigDecimal.ZERO);
            log.info("[스캘핑봇] 일일 시작 자산: {}원", formatNumber(dailyStartAsset));
        } catch (Exception e) {
            log.error("[스캘핑봇] 시작 자산 초기화 실패: {}", e.getMessage());
            dailyStartAsset = BigDecimal.ZERO;
        }
    }

    // ==================== 유틸리티 ====================

    /**
     * 수급 급증 종목 병합 (필터링은 checkScalpingEntry에서 일원화)
     */
    private List<InvestorSurgeDto> mergeAndFilterSurgeStocks(Map<String, List<InvestorSurgeDto>> surgeStocks) {
        Map<String, InvestorSurgeDto> merged = new java.util.HashMap<>();

        // 외국인 순매수 종목
        List<InvestorSurgeDto> foreignStocks = surgeStocks.get("FOREIGN");
        if (foreignStocks != null) {
            for (InvestorSurgeDto s : foreignStocks) {
                merged.put(s.getStockCode(), s);
            }
        }

        // 기관 순매수 종목
        List<InvestorSurgeDto> instStocks = surgeStocks.get("INSTITUTION");
        if (instStocks != null) {
            for (InvestorSurgeDto s : instStocks) {
                merged.putIfAbsent(s.getStockCode(), s);
            }
        }

        // 쌍끌이 종목 (우선순위)
        List<InvestorSurgeDto> commonStocks = surgeStocks.get("COMMON");
        if (commonStocks != null) {
            for (InvestorSurgeDto s : commonStocks) {
                merged.put(s.getStockCode(), s);  // 쌍끌이는 덮어쓰기
            }
        }

        return new java.util.ArrayList<>(merged.values());
    }

    private void resetDailyCounters() {
        LocalDate today = LocalDate.now();
        if (lastResetDate == null || !lastResetDate.equals(today)) {
            todayBuyCount.set(0);
            todaySellCount.set(0);
            killSwitchTriggered.set(false);
            scalpingPositions.clear();
            sellCooldownMap.clear();
            lastResetDate = today;
            initializeDailyAsset();
        }
    }

    // ==================== 텔레그램 알림 ====================

    private void sendScalpingBuyNotification(InvestorSurgeDto surge, ScalpingEntryResult entry, int quantity) {
        if (!telegramService.isEnabled()) return;

        String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
        String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";
        BigDecimal totalAmount = entry.currentPrice.multiply(BigDecimal.valueOf(quantity));

        telegramService.sendMessage(
                String.format("<b>%s [%s] 스캘핑 진입</b>\n\n", modeEmoji, modeTag) +
                "🎯 <b>" + surge.getStockName() + "</b> (" + surge.getStockCode() + ")\n" +
                "💰 " + formatNumber(entry.currentPrice) + "원 x " + quantity + "주\n" +
                "💵 매수금액: " + formatNumber(totalAmount) + "원\n\n" +
                "📊 체결강도: <b>" + entry.volumePower + "%</b>\n" +
                "📊 거래대금: " + entry.tradeAmount + "억\n" +
                "📊 시초가대비: +" + String.format("%.2f", entry.openChangeRate) + "%\n\n" +
                "━━━ 목표가 ━━━\n" +
                "🟢 익절 1차: +" + TAKE_PROFIT_FIRST + "% (절반)\n" +
                "🟢 트레일링: 고점 " + TRAILING_STOP_RATE + "%\n" +
                "🔴 손절: " + STOP_LOSS_RATE + "%\n" +
                "⏰ 타임컷: " + TIME_CUT_MINUTES + "분\n\n" +
                "━━━━━━━━━━━━━━━━\n" +
                modeEmoji + " MyPlatform " + modeTag
        );
    }

    private void sendScalpingSellNotification(PortfolioItemDto portfolio, BigDecimal currentPrice,
                                              BigDecimal buyPrice, BigDecimal profitRate,
                                              BigDecimal profitLoss, int quantity, String reason,
                                              boolean isPartialSell) {
        if (!telegramService.isEnabled()) return;

        String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
        String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

        String reasonEmoji;
        String reasonText;
        switch (reason) {
            case "STOP_LOSS":
                reasonEmoji = "🔻";
                reasonText = "손절";
                break;
            case "TAKE_PROFIT_HALF":
                reasonEmoji = "🟢";
                reasonText = "1차 익절 (절반)";
                break;
            case "TRAILING_STOP":
                reasonEmoji = "🔺";
                reasonText = "트레일링 익절";
                break;
            case "TIME_CUT":
                reasonEmoji = "⏰";
                reasonText = "타임컷";
                break;
            default:
                reasonEmoji = "📤";
                reasonText = reason;
        }

        String partialText = isPartialSell ? " (절반 매도)" : "";

        telegramService.sendMessage(
                String.format("<b>%s [%s] %s%s</b>\n\n", modeEmoji, modeTag, reasonText, partialText) +
                reasonEmoji + " <b>" + portfolio.getStockName() + "</b> (" + portfolio.getStockCode() + ")\n" +
                "💰 매도가: " + formatNumber(currentPrice) + "원\n" +
                "📊 매수가: " + formatNumber(buyPrice) + "원\n" +
                "📦 수량: " + quantity + "주\n" +
                "📈 손익률: " + String.format("%+.2f", profitRate) + "%\n" +
                "💵 손익금액: " + String.format("%+,d", profitLoss.intValue()) + "원\n\n" +
                "━━━━━━━━━━━━━━━━\n" +
                modeEmoji + " MyPlatform " + modeTag
        );
    }

    private void sendEndOfDayReport(TimeCutResult result) {
        String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
        String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";
        String profitEmoji = result.totalProfitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
        String profitSign = result.totalProfitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";

        StringBuilder message = new StringBuilder();
        message.append(String.format("<b>🔔 [%s] 스캘핑 장마감 청산</b>\n\n", modeTag));
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

    // ==================== 휴장일 체크 ====================

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

    private static final Set<LocalDate> KOREA_HOLIDAYS_2025 = Set.of(
            LocalDate.of(2025, 1, 28), LocalDate.of(2025, 1, 29), LocalDate.of(2025, 1, 30),
            LocalDate.of(2025, 5, 6),
            LocalDate.of(2025, 10, 6), LocalDate.of(2025, 10, 7), LocalDate.of(2025, 10, 8)
    );

    private static final Set<LocalDate> KOREA_HOLIDAYS_2026 = Set.of(
            LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 18),  // 설날
            LocalDate.of(2026, 3, 2),   // 삼일절 대체휴일 (3/1 일요일)
            LocalDate.of(2026, 5, 24),  // 부처님오신날
            LocalDate.of(2026, 8, 17),  // 광복절 대체휴일 (8/15 토요일)
            LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26),  // 추석
            LocalDate.of(2026, 10, 5)   // 개천절 대체휴일 (10/3 토요일)
    );

    private boolean isMarketClosed() {
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return true;
        }

        MonthDay monthDay = MonthDay.from(today);
        if (KOREA_FIXED_HOLIDAYS.contains(monthDay)) {
            return true;
        }

        int year = today.getYear();
        if (year == 2025 && KOREA_HOLIDAYS_2025.contains(today)) {
            return true;
        }
        if (year == 2026 && KOREA_HOLIDAYS_2026.contains(today)) {
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
