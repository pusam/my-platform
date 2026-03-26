package com.myplatform.backend.service;

import com.myplatform.backend.dto.ConsecutiveBuyDto;
import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.dto.PaperTradingDto.AccountSummaryDto;
import com.myplatform.backend.dto.PaperTradingDto.BotStatusDto;
import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.dto.PaperTradingDto.TradeHistoryDto;
import com.myplatform.backend.dto.ScalpingAnalysisDto;
import com.myplatform.backend.dto.SectorRotationDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.dto.TechnicalIndicatorsDto;
import com.myplatform.backend.entity.BotConfig;
import com.myplatform.backend.repository.BotConfigRepository;
import com.myplatform.backend.repository.VirtualPortfolioRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 자동 매매 봇 서비스 (3전략: 스캘핑(모의만) + 스윙 + 종가매수)
 *
 * ========================================
 * [전략 A] 스캘핑 — ★ 모의투자 전용 (실전 비활성)
 * ========================================
 *    지연 데이터 기반 단타는 구조적 열위 → 실전에서 OFF
 *
 * ========================================
 * [전략 B] 스윙 (수급 추종, 2~5일 보유)
 * ========================================
 *    매수: 외국인/기관 3일+ 연속매수 + MA20 지지 + RSI < 65
 *    매도: 익절 +5%, 손절 -3%, 최대 5일 보유
 *    시간: 14:00 체크
 *
 * ========================================
 * [전략 C] 종가 매수 (확정 수급, 1~2일 보유)
 * ========================================
 *    매수: 15:15 장 마감 직전, 당일 외국인+기관 동시 순매수 상위
 *         + 등락률 양봉 + RSI < 70
 *    매도: 다음날 갭업 시 익절 +2%, 손절 -2%, 최대 2일 보유
 *    최대 2종목
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
    private final GlobalFuturesService globalFuturesService;
    private final SectorTradingService sectorTradingService;
    private final ShortSellingService shortSellingService;
    private final StockStatusService stockStatusService;
    private final InvestorTradeService investorTradeService;

    // ========== [A] 스캘핑 전략 상수 (고확률 제한) ==========
    private static final BigDecimal STOP_LOSS_RATE = new BigDecimal("-1.5");     // 손절: -1.5%
    private static final BigDecimal TAKE_PROFIT_FIRST = new BigDecimal("1.2");   // 익절 1차: +1.2% (절반)
    private static final BigDecimal TRAILING_STOP_RATE = new BigDecimal("-0.8"); // 트레일링: 고점 대비 -0.8%
    private static final BigDecimal MIN_VOLUME_POWER = new BigDecimal("130");    // 최소 체결강도: 130% (고확률: 115→130)
    private static final BigDecimal MIN_NET_BUY_AMOUNT = new BigDecimal("10");   // 최소 순매수금액: 10억 (대폭 상향: 3→10)
    private static final int TIME_CUT_MINUTES = 15;                               // 타임컷: 15분
    private static final int TIME_CUT_EXTENDED_MINUTES = 20;                       // 동적 연장 최대 20분
    private static final BigDecimal TIME_EXTEND_MIN_PROFIT = new BigDecimal("0.3"); // 연장 조건: +0.3%
    private static final long MIN_TRADING_VALUE = 50_000_000_000L;               // 최소 거래대금: 500억원
    private static final int MIN_VOLUME_RATIO = 200;                              // 전일 대비 거래량: 200%
    private static final BigDecimal MAX_INVESTMENT_RATIO = new BigDecimal("0.15"); // 종목당 최대 15%
    private static final int MAX_HOLDING_STOCKS = 3;                              // 스캘핑 최대 보유: 3종목
    private static final int MAX_SCALPING_TRADES_PER_DAY = 2;                    // ★ 하루 최대 스캘핑 매수: 2건
    private static final BigDecimal KILL_SWITCH_RATE = new BigDecimal("-1.5");   // 킬 스위치: -1.5%
    private static final int SELL_COOLDOWN_MINUTES = 30;                        // 재매수 쿨다운: 30분
    private static final BigDecimal RSI_ENTRY_LIMIT = new BigDecimal("55");      // RSI 상한: 55
    private static final BigDecimal DISPARITY_20MA_LIMIT = new BigDecimal("3");  // 이격도 상한: 3%
    private static final BigDecimal GAP_UP_LIMIT = new BigDecimal("5");          // 갭상승 상한: 5%
    private static final BigDecimal MIN_INTRADAY_RANGE = new BigDecimal("1.5");  // 최소 변동폭: 1.5%
    private static final double VIX_PAUSE_THRESHOLD = 30.0;                    // VIX 임계치
    private static final BigDecimal KOSPI_DROP_LIMIT = new BigDecimal("-1.5");   // KOSPI 하락 한도
    private static final int CONSECUTIVE_STOP_LOSS_LIMIT = 3;                   // 연속 손절 한도: 3회
    private static final LocalTime MORNING_ENTRY_START = LocalTime.of(9, 45);   // 스캘핑 시작: 09:45
    private static final LocalTime MORNING_ENTRY_END = LocalTime.of(10, 30);    // ★ 스캘핑 종료: 10:30 (골든타임만)

    // ========== [B] 스윙 전략 상수 (수급 추종, 2~5일 보유) ==========
    private static final BigDecimal SWING_STOP_LOSS = new BigDecimal("-3.0");    // 스윙 손절: -3%
    private static final BigDecimal SWING_TAKE_PROFIT = new BigDecimal("5.0");   // 스윙 익절: +5%
    private static final BigDecimal SWING_TRAILING_STOP = new BigDecimal("-2.0"); // 스윙 트레일링: 고점 -2%
    private static final int SWING_MAX_HOLD_DAYS = 5;                            // 최대 보유: 5일
    private static final int SWING_MIN_CONSEC_DAYS = 3;                          // 최소 연속매수: 3일
    private static final BigDecimal SWING_MIN_AVG_AMOUNT = new BigDecimal("10"); // 일평균 순매수 ≥ 10억
    private static final BigDecimal SWING_RSI_LIMIT = new BigDecimal("65");      // RSI 상한: 65 (과열 미진입)
    private static final int SWING_MAX_HOLDING = 2;                              // 스윙 최대 보유: 2종목
    private static final BigDecimal SWING_INVESTMENT_RATIO = new BigDecimal("0.20"); // 종목당 20%

    // ========== [C] 종가 매수 전략 (확정 수급, 1~2일 보유) ==========
    private static final BigDecimal CLOSING_STOP_LOSS = new BigDecimal("-2.0");      // 종가매수 손절: -2%
    private static final BigDecimal CLOSING_TAKE_PROFIT = new BigDecimal("2.0");     // 종가매수 익절: +2% (갭업 타겟)
    private static final BigDecimal CLOSING_TRAILING_STOP = new BigDecimal("-1.0");  // 종가매수 트레일링: 고점 -1%
    private static final int CLOSING_MAX_HOLD_DAYS = 2;                              // 최대 보유: 2일
    private static final BigDecimal CLOSING_MIN_NET_BUY = new BigDecimal("50");      // 외국인+기관 합산 순매수 ≥ 50억
    private static final BigDecimal CLOSING_RSI_LIMIT = new BigDecimal("70");        // RSI 상한: 70
    private static final int CLOSING_MAX_HOLDING = 2;                                // 종가매수 최대 보유: 2종목
    private static final BigDecimal CLOSING_INVESTMENT_RATIO = new BigDecimal("0.15"); // 종목당 15%

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
    // ========== 스윙 전용 상태 ==========
    private final Map<String, SwingPosition> swingPositions = new ConcurrentHashMap<>();
    // ========== 종가매수 전용 상태 ==========
    private final Map<String, SwingPosition> closingPositions = new ConcurrentHashMap<>(); // SwingPosition 재사용
    // 종목별 마지막 매도 시간 (재매수 쿨다운용)
    private final Map<String, LocalDateTime> sellCooldownMap = new ConcurrentHashMap<>();
    // 당일 시작 자산 (킬 스위치용)
    private volatile BigDecimal dailyStartAsset = BigDecimal.ZERO;
    // 킬 스위치 발동 여부
    private final AtomicBoolean killSwitchTriggered = new AtomicBoolean(false);
    // VIX 기반 일시정지
    private final AtomicBoolean vixPaused = new AtomicBoolean(false);
    private volatile LocalDateTime lastVixAlertTime = null;
    // 수급 급증 데이터 캐시 (10분마다 갱신되므로 매초 DB 조회 불필요)
    private volatile Map<String, List<InvestorSurgeDto>> cachedSurgeStocks = null;
    private volatile LocalDateTime surgeStocksCacheTime = null;
    private static final long SURGE_CACHE_SECONDS = 30; // 30초 캐시
    // 연속 손절 카운터 (3회 연속 시 당일 정지)
    private final AtomicInteger consecutiveStopLossCount = new AtomicInteger(0);
    private final AtomicBoolean consecutiveStopLossPaused = new AtomicBoolean(false);
    // KOSPI 하락 기반 매수 일시정지
    private final AtomicBoolean kospiDropPaused = new AtomicBoolean(false);
    private volatile LocalDateTime lastKospiCheckTime = null;
    // 섹터 OUTFLOW 캐시 (5분마다 갱신)
    private volatile Set<String> outflowSectorStocks = ConcurrentHashMap.newKeySet();
    private volatile LocalDateTime outflowCacheTime = null;
    private static final long OUTFLOW_CACHE_SECONDS = 300; // 5분 캐시

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
        boolean timeExtended;          // 타임컷 동적 연장 사용 여부
        int originalQuantity;          // 원래 수량

        ScalpingPosition(String stockCode, String stockName, BigDecimal buyPrice, int quantity) {
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.buyPrice = buyPrice;
            this.buyTime = LocalDateTime.now();
            this.highPrice = buyPrice;
            this.halfSold = false;
            this.timeExtended = false;
            this.originalQuantity = quantity;
        }

        void updateHighPrice(BigDecimal currentPrice) {
            if (currentPrice.compareTo(highPrice) > 0) {
                highPrice = currentPrice;
            }
        }
    }

    /**
     * 스윙 포지션 정보
     */
    private static class SwingPosition {
        String stockCode;
        String stockName;
        BigDecimal buyPrice;
        LocalDateTime buyTime;
        BigDecimal highPrice;
        String buyReason; // "외국인3일연속" 등

        SwingPosition(String stockCode, String stockName, BigDecimal buyPrice, String reason) {
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.buyPrice = buyPrice;
            this.buyTime = LocalDateTime.now();
            this.highPrice = buyPrice;
            this.buyReason = reason;
        }

        void updateHighPrice(BigDecimal currentPrice) {
            if (currentPrice.compareTo(highPrice) > 0) {
                highPrice = currentPrice;
            }
        }

        long holdDays() {
            return java.time.Duration.between(buyTime, LocalDateTime.now()).toDays();
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
            KoreaInvestmentService kisService,
            GlobalFuturesService globalFuturesService,
            SectorTradingService sectorTradingService,
            ShortSellingService shortSellingService,
            StockStatusService stockStatusService,
            InvestorTradeService investorTradeService) {
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
        this.globalFuturesService = globalFuturesService;
        this.sectorTradingService = sectorTradingService;
        this.shortSellingService = shortSellingService;
        this.stockStatusService = stockStatusService;
        this.investorTradeService = investorTradeService;
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

                    telegramService.sendSignal(
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

            telegramService.sendSignal(
                    String.format("<b>%s [%s] 스캘핑 스나이퍼 봇 시작!</b>\n\n", modeEmoji, modeTag) +
                    "✅ 봇이 활성화되었습니다.\n" +
                    "🎯 전략: <b>스캘핑 스나이퍼 모드 v2</b>\n\n" +
                    "━━━ 매수 조건 (필수3 + 보조4중2) ━━━\n" +
                    "🔒 필수: 순매수≥1억, 현재가OK, 양봉\n" +
                    "📊 보조(4중2): 체결강도≥110%, RSI<60,\n" +
                    "  이격도<5%, 갭<8%\n" +
                    "⛔ 조회실패=차단 (안전 우선)\n\n" +
                    "━━━ 매도 조건 (3초 감시) ━━━\n" +
                    "🟢 익절 1차: +2.0% → 절반 매도\n" +
                    "🟢 트레일링: 고점 -0.5% → 전량\n" +
                    "🔴 손절: -1.0% → 전량 손절\n" +
                    "⏰ 타임컷: 10분 초과 → 전량 매도\n\n" +
                    "━━━ 리스크 관리 (강화) ━━━\n" +
                    "🛑 킬 스위치: 일일 -1.5% 초과 시 봇 종료\n" +
                    "🛑 연속 손절 3회 시 당일 매수 정지\n" +
                    "📉 KOSPI -1.5% 하락 시 진입 차단\n" +
                    "🔄 섹터 OUTFLOW 종목 진입 차단\n" +
                    "⏰ 09:30부터 매수 (장 초반 30분 회피)\n" +
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
            telegramService.sendSignal(
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
        } else if (consecutiveStopLossPaused.get()) {
            status = "STOP_LOSS_PAUSED";
        } else if (!botActive.get()) {
            status = "STOPPED";
        } else if (vixPaused.get()) {
            status = "VIX_PAUSED";
        } else if (kospiDropPaused.get()) {
            status = "KOSPI_DROP_PAUSED";
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

    // ==================== VIX 기반 매수 일시정지 ====================

    /**
     * VIX 30 이상이면 매수 일시정지 + 텔레그램 알림 (1시간에 1번만)
     * VIX 30 미만으로 회복되면 자동 해제 + 알림
     */
    private boolean checkVixPause() {
        try {
            GlobalFuturesService.FuturesQuote vixData = globalFuturesService.getFuturesQuote("^VIX");
            if (vixData == null || !vixData.isSuccess() || vixData.getCurrentPrice() == null) return false;

            double vixPrice = vixData.getCurrentPrice().doubleValue();

            if (vixPrice >= VIX_PAUSE_THRESHOLD) {
                if (!vixPaused.getAndSet(true)) {
                    // 최초 VIX 일시정지 발동
                    log.warn("[스캘핑봇] ⚠️ VIX {} → 매수 일시정지!", vixPrice);
                    sendVixAlert(vixPrice, true);
                } else if (lastVixAlertTime == null ||
                        lastVixAlertTime.isBefore(LocalDateTime.now().minusHours(1))) {
                    // 1시간마다 반복 알림
                    sendVixAlert(vixPrice, false);
                }
                return true;
            } else if (vixPaused.getAndSet(false)) {
                // VIX 정상 복귀
                log.info("[스캘핑봇] ✅ VIX {} → 매수 재개", vixPrice);
                if (telegramService.isEnabled()) {
                    telegramService.sendSignal(
                        String.format("<b>✅ VIX 정상 복귀 → 매수 재개</b>\n\n" +
                            "📊 VIX: <b>%.1f</b> (임계치: %.0f)\n" +
                            "🤖 스캘핑봇 매수 자동 재개\n\n" +
                            "━━━━━━━━━━━━━━━━", vixPrice, VIX_PAUSE_THRESHOLD)
                    );
                }
            }
        } catch (Exception e) {
            log.debug("[스캘핑봇] VIX 조회 실패 (무시): {}", e.getMessage());
        }
        return false;
    }

    private void sendVixAlert(double vixPrice, boolean isFirstAlert) {
        lastVixAlertTime = LocalDateTime.now();
        if (telegramService.isEnabled()) {
            String prefix = isFirstAlert ? "🚨 VIX 급등 — 매수 일시정지!" : "⚠️ VIX 고공 유지 중";
            telegramService.sendSignal(
                String.format("<b>%s</b>\n\n" +
                    "📊 VIX: <b>%.1f</b> (임계치: %.0f)\n" +
                    "⏸️ 스캘핑봇 매수 자동 중단\n" +
                    "📉 매도/손절은 정상 작동 중\n\n" +
                    "VIX %.0f 미만 회복 시 자동 재개\n" +
                    "━━━━━━━━━━━━━━━━", prefix, vixPrice, VIX_PAUSE_THRESHOLD, VIX_PAUSE_THRESHOLD)
            );
        }
    }

    // ==================== KOSPI 하락 체크 ====================

    /**
     * KOSPI -1.5% 이상 하락 시 신규 매수 차단
     * - 시장 전체가 빠지는 날 스캘핑은 역풍
     * - 1분마다 체크 (API 부하 방지)
     */
    private boolean checkKospiDrop() {
        try {
            // 1분마다만 체크 (API 부하 방지)
            if (lastKospiCheckTime != null &&
                    java.time.Duration.between(lastKospiCheckTime, LocalDateTime.now()).getSeconds() < 60) {
                return kospiDropPaused.get();
            }
            lastKospiCheckTime = LocalDateTime.now();

            // KOSPI 지수 조회 (코스피: 0001)
            StockPriceDto kospiDto = stockPriceService.getStockPrice("0001");
            if (kospiDto == null || kospiDto.getChangeRate() == null) {
                return kospiDropPaused.get();
            }

            BigDecimal kospiChangeRate = kospiDto.getChangeRate();

            if (kospiChangeRate.compareTo(KOSPI_DROP_LIMIT) <= 0) {
                if (!kospiDropPaused.getAndSet(true)) {
                    log.warn("[스캘핑봇] ⚠️ KOSPI {}% 하락 — 신규 매수 차단!", kospiChangeRate);
                    if (telegramService.isEnabled()) {
                        telegramService.sendSignal(
                                String.format("<b>⚠️ KOSPI 급락 — 매수 차단!</b>\n\n" +
                                        "📉 KOSPI: <b>%+.2f%%</b> (임계치: %.1f%%)\n" +
                                        "⏸️ 스캘핑봇 신규 매수 중단\n" +
                                        "📉 매도/손절은 정상 작동 중\n\n" +
                                        "━━━━━━━━━━━━━━━━", kospiChangeRate, KOSPI_DROP_LIMIT)
                        );
                    }
                }
                return true;
            } else if (kospiDropPaused.getAndSet(false)) {
                log.info("[스캘핑봇] ✅ KOSPI {}% — 매수 재개", kospiChangeRate);
                if (telegramService.isEnabled()) {
                    telegramService.sendSignal(
                            String.format("<b>✅ KOSPI 회복 — 매수 재개</b>\n\n" +
                                    "📊 KOSPI: <b>%+.2f%%</b>\n" +
                                    "🤖 스캘핑봇 매수 자동 재개\n\n" +
                                    "━━━━━━━━━━━━━━━━", kospiChangeRate)
                    );
                }
            }
        } catch (Exception e) {
            log.debug("[스캘핑봇] KOSPI 조회 실패 (무시): {}", e.getMessage());
        }
        return kospiDropPaused.get();
    }

    // ==================== 섹터 OUTFLOW 체크 ====================

    /**
     * 섹터 OUTFLOW 종목인지 확인
     * - getSectorRotation()에서 OUTFLOW로 분류된 섹터의 종목은 진입 차단
     * - 5분 캐시로 API 부하 방지
     */
    private boolean isOutflowSectorStock(String stockCode) {
        try {
            // 5분 캐시
            if (outflowCacheTime != null &&
                    java.time.Duration.between(outflowCacheTime, LocalDateTime.now()).getSeconds() < OUTFLOW_CACHE_SECONDS) {
                return outflowSectorStocks.contains(stockCode);
            }

            // 섹터 로테이션 데이터 조회
            List<SectorRotationDto> rotations = sectorTradingService.getSectorRotation();
            Set<String> newOutflowStocks = ConcurrentHashMap.newKeySet();

            for (SectorRotationDto rotation : rotations) {
                if ("OUTFLOW".equals(rotation.getFlowDirection())) {
                    // OUTFLOW 섹터의 모든 종목 코드 수집
                    var sectorInfo = sectorTradingService.getSectorConfig().getSector(rotation.getSectorCode());
                    if (sectorInfo != null) {
                        newOutflowStocks.addAll(sectorInfo.getStockCodes());
                    }
                }
            }

            outflowSectorStocks = newOutflowStocks;
            outflowCacheTime = LocalDateTime.now();

            if (!newOutflowStocks.isEmpty()) {
                log.debug("[스캘핑봇] OUTFLOW 섹터 종목 {}개 차단 중", newOutflowStocks.size());
            }

            return outflowSectorStocks.contains(stockCode);
        } catch (Exception e) {
            log.debug("[스캘핑봇] 섹터 OUTFLOW 체크 실패 (무시): {}", e.getMessage());
            return false;
        }
    }

    // ==================== 공매도 잔고 체크 ====================

    /**
     * 고공매도 종목인지 확인
     * - 공매도 비율 5% 이상이면 진입 차단
     */
    private boolean isHighShortSellingStock(String stockCode) {
        try {
            return shortSellingService.isHighShortSellingStock(stockCode);
        } catch (Exception e) {
            log.debug("[스캘핑봇] 공매도 비율 체크 실패 (무시): {}", e.getMessage());
            return false;
        }
    }

    // ==================== 매수 로직 (스캘핑) ====================

    /**
     * 스캘핑 매수 로직
     * - 실행 시간: 09:00~11:30 (장 전반부, 5초 간격, 평일만)
     * - 수급 급증 종목 중 스캘핑 조건 충족 시 진입
     */
    @Scheduled(cron = "*/5 * 9-11 * * MON-FRI", zone = "Asia/Seoul")
    public void executeScalpingBuyLogic() {
        if (!botActive.get() || killSwitchTriggered.get()) {
            return;
        }

        // ★ 실전투자 모드에서 스캘핑 비활성 (지연 데이터 기반 단타는 구조적 열위)
        if (currentMode == TradingMode.REAL) {
            return;
        }

        // 연속 손절 3회 시 당일 정지
        if (consecutiveStopLossPaused.get()) {
            return;
        }

        // 09:45~10:30만 스캘핑 매수 허용 (골든타임 집중)
        LocalTime now = LocalTime.now();
        if (now.isBefore(MORNING_ENTRY_START) || now.isAfter(MORNING_ENTRY_END)) {
            return;
        }

        // ★ 하루 최대 스캘핑 매수 제한
        if (todayBuyCount.get() >= MAX_SCALPING_TRADES_PER_DAY) {
            return;
        }

        // ★ VIX 30 이상 → 매수 일시정지
        if (checkVixPause()) {
            return;
        }

        // ★ KOSPI -1.5% 이상 하락 → 신규 진입 차단
        if (checkKospiDrop()) {
            return;
        }

        if (isMarketClosed()) {
            return;
        }

        log.debug("[스캘핑봇] ===== 매수 로직 시작 ({}) =====", LocalTime.now());
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

            // 수급 급증 종목 조회 (10분마다 갱신되므로 캐시 활용)
            Map<String, List<InvestorSurgeDto>> surgeStocks = getCachedSurgeStocks();
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

            log.debug("[스캘핑봇] 수급 급증 후보: {}종목", targetStocks.size());

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

                // ★ 섹터 OUTFLOW 종목 진입 차단 ★
                if (isOutflowSectorStock(surge.getStockCode())) {
                    log.debug("[스캘핑봇] Skip [{}({})] 섹터 OUTFLOW — 자금 유출 섹터 진입 차단",
                            surge.getStockName(), surge.getStockCode());
                    continue;
                }

                // ★ 거래정지/상폐 종목 진입 차단 ★
                if (!stockStatusService.isActive(surge.getStockCode())) {
                    log.debug("[스캘핑봇] Skip [{}({})] 거래정지/상폐 종목",
                            surge.getStockName(), surge.getStockCode());
                    continue;
                }

                // ★ 고공매도 종목 진입 차단 ★
                if (isHighShortSellingStock(surge.getStockCode())) {
                    log.debug("[스캘핑봇] Skip [{}({})] 공매도 비율 5% 이상 — 고공매도 종목 진입 차단",
                            surge.getStockName(), surge.getStockCode());
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
     * 수급 급증 데이터 캐시 조회 (30초 TTL)
     * 스냅샷은 10분마다 갱신되므로 매 사이클(5초)마다 DB 조회할 필요 없음
     */
    private synchronized Map<String, List<InvestorSurgeDto>> getCachedSurgeStocks() {
        LocalDateTime now = LocalDateTime.now();
        if (cachedSurgeStocks != null && surgeStocksCacheTime != null
                && java.time.Duration.between(surgeStocksCacheTime, now).getSeconds() < SURGE_CACHE_SECONDS) {
            return cachedSurgeStocks;
        }
        cachedSurgeStocks = investorSurgeService.getAllSurgeStocks(BigDecimal.ZERO);
        surgeStocksCacheTime = now;
        return cachedSurgeStocks;
    }

    /**
     * 스캘핑 진입 조건 체크 (스코어링 방식)
     *
     * [필수 조건 - 3개 전부 충족 필요]
     *  1. 순매수금액 ≥ 1억
     *  2. 현재가 조회 성공
     *  3. 양봉 (현재가 > 시초가)
     *
     * [보조 조건 - 4개 중 2개 이상 충족 필요]
     *  A. 체결강도 ≥ 110%
     *  B. RSI(14) < 70 (과열 아님)
     *  C. 20MA 이격도 < 15%
     *  D. 갭상승 < 8%
     */
    private ScalpingEntryResult checkScalpingEntry(InvestorSurgeDto surge) {
        String stockName = surge.getStockName();
        String stockCode = surge.getStockCode();

        try {
            // ==================== 필수 조건 1: 순매수금액 ≥ 1억 ====================
            BigDecimal netBuyAmount = surge.getNetBuyAmount();
            if (netBuyAmount == null || netBuyAmount.compareTo(MIN_NET_BUY_AMOUNT) < 0) {
                log.debug("[스캘핑봇] Skip [{}({})] 순매수 부족 (현재: {}억 < 기준: {}억)",
                        stockName, stockCode, netBuyAmount, MIN_NET_BUY_AMOUNT);
                return ScalpingEntryResult.fail("순매수 부족: " + netBuyAmount + "억");
            }

            // ==================== 필수 조건 2: 현재가 조회 ====================
            StockPriceDto priceDto = stockPriceService.getStockPrice(stockCode);
            if (priceDto == null || priceDto.getCurrentPrice() == null) {
                log.debug("[스캘핑봇] Skip [{}({})] 현재가 조회 실패", stockName, stockCode);
                return ScalpingEntryResult.fail("현재가 조회 실패");
            }

            BigDecimal currentPrice = priceDto.getCurrentPrice();
            BigDecimal openPrice = priceDto.getOpenPrice();

            // ===== 거래대금/거래량 기본 필터 (유동성 확인) =====
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

            // ==================== 변동성 필터: 저변동성 종목 제외 ====================
            BigDecimal highPrice = priceDto.getHighPrice();
            BigDecimal lowPrice = priceDto.getLowPrice();
            if (highPrice != null && lowPrice != null && lowPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal intradayRange = highPrice.subtract(lowPrice)
                        .divide(lowPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                if (intradayRange.compareTo(MIN_INTRADAY_RANGE) < 0) {
                    log.debug("[스캘핑봇] Skip [{}({})] 저변동성 종목 (일중변동폭: {}% < 기준: {}%)",
                            stockName, stockCode, intradayRange.setScale(1, RoundingMode.HALF_UP), MIN_INTRADAY_RANGE);
                    return ScalpingEntryResult.fail("저변동성 종목: 일중변동폭 " + intradayRange.setScale(1, RoundingMode.HALF_UP) + "%");
                }
            }

            // ==================== 필수 조건 3: 양봉 ====================
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

            // 시초가 대비 상승률
            BigDecimal openChangeRate = BigDecimal.ZERO;
            if (openPrice != null && openPrice.compareTo(BigDecimal.ZERO) > 0) {
                openChangeRate = currentPrice.subtract(openPrice)
                        .divide(openPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }

            // ==================== 보조 조건 스코어링 (4개 중 2개 이상) ====================
            int subScore = 0;
            int subTotal = 4;
            List<String> passedSubs = new java.util.ArrayList<>();
            List<String> failedSubs = new java.util.ArrayList<>();

            // ===== 보조 A: 체결강도 ≥ 110% =====
            BigDecimal volumePower = null;
            try {
                ScalpingAnalysisDto scalpingData = scalpingAnalysisService.getVolumePowerRefresh(stockCode);
                if (scalpingData != null && scalpingData.getVolumePower() != null) {
                    volumePower = scalpingData.getVolumePower();
                    if (volumePower.compareTo(MIN_VOLUME_POWER) >= 0) {
                        subScore++;
                        passedSubs.add("체결강도 " + volumePower + "%");
                    } else {
                        failedSubs.add("체결강도 " + volumePower + "% < " + MIN_VOLUME_POWER + "%");
                    }
                } else {
                    failedSubs.add("체결강도 데이터 없음");
                }
            } catch (Exception e) {
                failedSubs.add("체결강도 조회 실패");
            }

            // ===== 보조 B: RSI(14) < 60 (강화) =====
            try {
                boolean rsiOk = false; // 조회 실패 시 실패 처리 (안전 우선)
                boolean rsiChecked = false;

                // 분봉 RSI 시도
                try {
                    JsonNode minuteData = kisService.getStockMinuteChartWithPriority(stockCode, KisApiRateLimiter.Priority.HIGH);
                    if (minuteData != null) {
                        JsonNode output2 = minuteData.get("output2");
                        if (output2 != null && output2.isArray() && output2.size() >= 15) {
                            List<BigDecimal> minuteClosePrices = new java.util.ArrayList<>();
                            for (int i = 0; i < Math.min(output2.size(), 30); i++) {
                                JsonNode bar = output2.get(i);
                                String closeStr = bar.has("stck_prpr") ? bar.get("stck_prpr").asText() : null;
                                if (closeStr != null && !closeStr.isEmpty()) {
                                    minuteClosePrices.add(new BigDecimal(closeStr));
                                }
                            }
                            if (minuteClosePrices.size() >= 15) {
                                TechnicalIndicatorsDto minuteIndicators = technicalIndicatorService.calculateSimple(minuteClosePrices);
                                if (minuteIndicators.getRsi14() != null) {
                                    rsiOk = minuteIndicators.getRsi14().compareTo(RSI_ENTRY_LIMIT) < 0;
                                    rsiChecked = true;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[스캘핑봇] 분봉 RSI 조회 실패 (일봉 폴백): {} - {}", stockCode, e.getMessage());
                }

                // 분봉 실패 시 일봉 폴백
                if (!rsiChecked) {
                    List<BigDecimal> closePrices = kisService.getDailyClosePricesWithPriority(stockCode, 30, KisApiRateLimiter.Priority.HIGH);
                    if (closePrices != null && closePrices.size() >= 14) {
                        TechnicalIndicatorsDto indicators = technicalIndicatorService.calculate(closePrices);
                        if (indicators.getRsi14() != null) {
                            rsiOk = indicators.getRsi14().compareTo(RSI_ENTRY_LIMIT) < 0;
                            rsiChecked = true;
                        }
                    }
                }

                if (rsiOk) {
                    subScore++;
                    passedSubs.add("RSI 양호");
                } else {
                    failedSubs.add("RSI 과열 ≥ " + RSI_ENTRY_LIMIT);
                }
            } catch (Exception e) {
                // RSI 체크 실패 시 실패 처리 (안전 우선)
                failedSubs.add("RSI 체크 실패(차단)");
            }

            // ===== 보조 C: 20MA 이격도 < 5% (강화) =====
            try {
                List<BigDecimal> dailyPrices = kisService.getDailyClosePricesWithPriority(stockCode, 30, KisApiRateLimiter.Priority.HIGH);
                if (dailyPrices != null && dailyPrices.size() >= 20) {
                    TechnicalIndicatorsDto dailyIndicators = technicalIndicatorService.calculate(dailyPrices);
                    if (dailyIndicators.getMa20() != null && dailyIndicators.getMa20().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal disparity = currentPrice.subtract(dailyIndicators.getMa20())
                                .divide(dailyIndicators.getMa20(), 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"));
                        if (disparity.compareTo(DISPARITY_20MA_LIMIT) < 0) {
                            subScore++;
                            passedSubs.add("이격도 " + disparity.setScale(1, RoundingMode.HALF_UP) + "%");
                        } else {
                            failedSubs.add("이격도 " + disparity.setScale(1, RoundingMode.HALF_UP) + "% ≥ " + DISPARITY_20MA_LIMIT + "%");
                        }
                    } else {
                        failedSubs.add("이격도 데이터 없음(차단)");
                    }
                } else {
                    failedSubs.add("이격도 데이터 부족(차단)");
                }
            } catch (Exception e) {
                failedSubs.add("이격도 체크 실패(차단)");
            }

            // ===== 보조 D: 갭상승 < 8% =====
            boolean gapCheckDone = false;
            if (openPrice != null && openPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal changePrice = priceDto.getChangePrice();
                if (changePrice != null) {
                    BigDecimal prevClose = currentPrice.subtract(changePrice);
                    if (prevClose.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal gapRate = openPrice.subtract(prevClose)
                                .divide(prevClose, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"));
                        gapCheckDone = true;
                        if (gapRate.compareTo(GAP_UP_LIMIT) < 0) {
                            subScore++;
                            passedSubs.add("갭 " + gapRate.setScale(1, RoundingMode.HALF_UP) + "%");
                        } else {
                            failedSubs.add("갭상승 " + gapRate.setScale(1, RoundingMode.HALF_UP) + "% ≥ " + GAP_UP_LIMIT + "%");
                        }
                    }
                }
            }
            if (!gapCheckDone) {
                failedSubs.add("갭 데이터 없음(차단)");
            }

            // ==================== 최종 판정: 보조 조건 2개 이상 충족 ====================
            int requiredSubScore = 2;
            if (subScore < requiredSubScore) {
                log.info("[스캘핑봇] ✗ 보조조건 미달 [{}({})] {}/{} (통과: {} / 실패: {})",
                        stockName, stockCode, subScore, subTotal,
                        String.join(", ", passedSubs), String.join(", ", failedSubs));
                return ScalpingEntryResult.fail("보조조건 " + subScore + "/" + subTotal + " (최소 " + requiredSubScore + "개 필요)");
            }

            log.info("[스캘핑봇] ✓ 진입 조건 충족 [{}({})] 순매수: {}억, 체결강도: {}%, 보조: {}/{}, 시초가대비: +{}%",
                    stockName, stockCode, netBuyAmount,
                    volumePower != null ? volumePower : "N/A",
                    subScore, subTotal,
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
     * - 3초 간격으로 실행 (09:00~15:20, 점심시간 포함, API 블락 방지)
     */
    @Scheduled(cron = "*/3 * 9-15 * * MON-FRI", zone = "Asia/Seoul")
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

                // 1. 손절 체크 (-1.5%)
                if (profitRate.compareTo(STOP_LOSS_RATE) <= 0) {
                    sellReason = "STOP_LOSS";
                    log.info("[스캘핑봇] 손절 조건: {} - 손익률 {}%", portfolio.getStockName(), profitRate);
                }
                // 2. 익절 1차 체크 (+1.2% 절반 매도)
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
                // 3. 트레일링 스탑 체크 (고점 대비 -1.0%)
                else if (position.halfSold && highDropRate.compareTo(TRAILING_STOP_RATE) <= 0) {
                    sellReason = "TRAILING_STOP";
                    log.info("[스캘핑봇] 트레일링 스탑: {} - 고점대비 {}%", portfolio.getStockName(), highDropRate);
                }
                // 4. 타임컷 체크 (동적 연장 포함)
                else if (minutesElapsed >= TIME_CUT_EXTENDED_MINUTES) {
                    // 15분 → 무조건 청산
                    sellReason = "TIME_CUT";
                    log.info("[스캘핑봇] 타임컷(최대): {} - {}분 경과, 손익률 {}%",
                            portfolio.getStockName(), minutesElapsed, profitRate);
                }
                else if (minutesElapsed >= TIME_CUT_MINUTES && !position.timeExtended) {
                    // 10분 경과 → 연장 조건 체크
                    boolean canExtend = profitRate.compareTo(TIME_EXTEND_MIN_PROFIT) >= 0
                            && isVolumeIncreasing(portfolio.getStockCode());

                    if (canExtend) {
                        position.timeExtended = true;
                        log.info("[스캘핑봇] ⏰ 타임컷 5분 연장: {} - 수익 {}%, 거래량 증가 중 → {}분까지",
                                portfolio.getStockName(), profitRate, TIME_CUT_EXTENDED_MINUTES);
                    } else {
                        sellReason = "TIME_CUT";
                        log.info("[스캘핑봇] 타임컷: {} - {}분 경과, 손익률 {}% (연장 조건 미충족)",
                                portfolio.getStockName(), minutesElapsed, profitRate);
                    }
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

                // 연속 손절 카운터 관리
                if ("STOP_LOSS".equals(reason)) {
                    int stopCount = consecutiveStopLossCount.incrementAndGet();
                    log.warn("[스캘핑봇] 연속 손절 {}회 (한도: {}회)", stopCount, CONSECUTIVE_STOP_LOSS_LIMIT);
                    if (stopCount >= CONSECUTIVE_STOP_LOSS_LIMIT) {
                        consecutiveStopLossPaused.set(true);
                        log.warn("[스캘핑봇] 🛑 연속 손절 {}회 — 당일 매수 정지!", stopCount);
                        if (telegramService.isEnabled()) {
                            telegramService.sendSignal(
                                    "<b>🛑 연속 손절 " + stopCount + "회 — 당일 매수 정지!</b>\n\n" +
                                    "⚠️ 연속 손절이 " + CONSECUTIVE_STOP_LOSS_LIMIT + "회에 도달하여\n" +
                                    "금일 신규 매수를 중단합니다.\n" +
                                    "📉 매도/손절은 정상 작동 중\n\n" +
                                    "━━━━━━━━━━━━━━━━"
                            );
                        }
                    }
                } else {
                    // 손절이 아닌 매도 시 연속 카운터 리셋
                    consecutiveStopLossCount.set(0);
                }
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

        log.info("[스캘핑봇] ===== 장 마감 청산 시작 (스캘핑만, 스윙/종가매수 유지) =====");

        try {
            // 스윙 + 종가매수 포지션은 보유 유지
            Set<String> keepCodes = new java.util.HashSet<>();
            keepCodes.addAll(swingPositions.keySet());
            keepCodes.addAll(closingPositions.keySet());
            TimeCutResult result = sellPortfolioExcept(keepCodes);

            if (telegramService.isEnabled()) {
                sendEndOfDayReport(result);
            }

            // 스캘핑 포지션만 정리
            scalpingPositions.clear();

            log.info("[스캘핑봇] 장 마감 청산 완료 - {}종목 매도(스윙 {}종목 유지), 총 손익: {}원",
                    result.soldCount, keepCodes.size(), formatNumber(result.totalProfitLoss));

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

    /**
     * 스윙 포지션 제외하고 포트폴리오 청산
     */
    private TimeCutResult sellPortfolioExcept(Set<String> excludeCodes) {
        List<PortfolioItemDto> portfolios = activeTradeService.getPortfolio();
        List<PortfolioItemDto> toSell = portfolios.stream()
                .filter(p -> !excludeCodes.contains(p.getStockCode()))
                .collect(Collectors.toList());

        if (toSell.isEmpty()) {
            return new TimeCutResult(0, BigDecimal.ZERO, List.of());
        }

        List<String> codes = toSell.stream().map(PortfolioItemDto::getStockCode).collect(Collectors.toList());
        Map<String, StockPriceDto> prices = stockPriceService.getStockPrices(codes);

        int soldCount = 0;
        BigDecimal totalProfitLoss = BigDecimal.ZERO;
        List<TimeCutItem> soldItems = new java.util.ArrayList<>();

        for (PortfolioItemDto portfolio : toSell) {
            StockPriceDto priceDto = prices.get(portfolio.getStockCode());
            if (priceDto == null || priceDto.getCurrentPrice() == null) continue;
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
                BigDecimal profitRate = (avgPrice != null && avgPrice.compareTo(BigDecimal.ZERO) > 0)
                        ? currentPrice.subtract(avgPrice).divide(avgPrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                        : BigDecimal.ZERO;
                soldItems.add(new TimeCutItem(portfolio.getStockName(), portfolio.getStockCode(),
                        portfolio.getQuantity(), currentPrice, profitLoss, profitRate));
                Thread.sleep(300);
            } catch (Exception e) {
                log.error("[스캘핑봇] 장마감 청산 실패: {} - {}", portfolio.getStockName(), e.getMessage());
            }
        }
        return new TimeCutResult(soldCount, totalProfitLoss, soldItems);
    }

    // ==================== [전략 B] 스윙 매수 (14:00, 평일) ====================

    /**
     * 스윙 진입: 외국인/기관 연속매수 종목 중 기술적 조건 충족 시 매수
     * - 14:00 실행 (장 마감 전 수급 확정)
     * - 3일+ 연속매수 + 일평균 10억+ + MA20 지지 + RSI < 65
     */
    @Scheduled(cron = "0 0 14 * * MON-FRI", zone = "Asia/Seoul")
    public void executeSwingBuyLogic() {
        if (!botActive.get() || killSwitchTriggered.get()) return;
        if (isMarketClosed()) return;

        // 스윙 보유 한도 체크
        if (swingPositions.size() >= SWING_MAX_HOLDING) {
            log.debug("[스윙봇] 최대 보유 {}종목 도달 — 신규 진입 스킵", SWING_MAX_HOLDING);
            return;
        }

        log.info("[스윙봇] ===== 스윙 진입 체크 시작 =====");

        try {
            // 외국인 + 기관 연속매수 조회
            List<ConsecutiveBuyDto> foreignConsec = getConsecutiveBuyData("FOREIGN");
            List<ConsecutiveBuyDto> instConsec = getConsecutiveBuyData("INSTITUTION");

            if (foreignConsec.isEmpty() && instConsec.isEmpty()) {
                log.info("[스윙봇] 연속매수 데이터 없음 — 스킵");
                return;
            }

            // 외국인/기관 모두 합쳐서 후보 선정
            Map<String, ConsecutiveBuyDto> candidates = new java.util.LinkedHashMap<>();
            for (ConsecutiveBuyDto cb : foreignConsec) {
                if (cb.getConsecutiveDays() >= SWING_MIN_CONSEC_DAYS
                        && cb.getAvgDailyAmount() != null
                        && cb.getAvgDailyAmount().compareTo(SWING_MIN_AVG_AMOUNT) >= 0) {
                    candidates.put(cb.getStockCode(), cb);
                }
            }
            // 기관 데이터로 보강 (이미 있으면 스킵)
            for (ConsecutiveBuyDto cb : instConsec) {
                if (!candidates.containsKey(cb.getStockCode())
                        && cb.getConsecutiveDays() >= SWING_MIN_CONSEC_DAYS
                        && cb.getAvgDailyAmount() != null
                        && cb.getAvgDailyAmount().compareTo(SWING_MIN_AVG_AMOUNT) >= 0) {
                    candidates.put(cb.getStockCode(), cb);
                }
            }

            log.info("[스윙봇] 연속매수 후보: {}건 (외국인 {}건, 기관 {}건)",
                    candidates.size(), foreignConsec.size(), instConsec.size());

            if (candidates.isEmpty()) return;

            // 잔고 확인
            AccountSummaryDto account = activeTradeService.getAccountSummary();
            BigDecimal totalAsset = account.getCurrentBalance().add(
                    account.getTotalEvaluation() != null ? account.getTotalEvaluation() : BigDecimal.ZERO);
            BigDecimal maxPerStock = totalAsset.multiply(SWING_INVESTMENT_RATIO);

            // 이미 보유 중인 종목 제외
            Set<String> holdingCodes = activeTradeService.getPortfolio().stream()
                    .map(PortfolioItemDto::getStockCode).collect(Collectors.toSet());

            for (ConsecutiveBuyDto candidate : candidates.values()) {
                if (swingPositions.size() >= SWING_MAX_HOLDING) break;
                if (holdingCodes.contains(candidate.getStockCode())) continue;
                if (swingPositions.containsKey(candidate.getStockCode())) continue;
                if (sellCooldownMap.containsKey(candidate.getStockCode())) continue;

                // 기술적 조건 체크
                if (!checkSwingTechnical(candidate.getStockCode())) continue;

                // 현재가 조회
                StockPriceDto priceDto = stockPriceService.getStockPrice(candidate.getStockCode());
                if (priceDto == null || priceDto.getCurrentPrice() == null) continue;

                BigDecimal currentPrice = priceDto.getCurrentPrice();
                int quantity = maxPerStock.divide(currentPrice, 0, RoundingMode.DOWN).intValue();
                if (quantity <= 0) continue;

                // 매수 실행
                try {
                    String investorLabel = candidate.getInvestorType().equals("FOREIGN") ? "외국인" : "기관";
                    String reason = "SWING_" + candidate.getInvestorType();

                    activeTradeService.buy(candidate.getStockCode(), candidate.getStockName(),
                            currentPrice, quantity, reason);

                    swingPositions.put(candidate.getStockCode(),
                            new SwingPosition(candidate.getStockCode(), candidate.getStockName(),
                                    currentPrice, investorLabel + candidate.getConsecutiveDays() + "일연속"));

                    lastTradeTime = LocalDateTime.now();
                    todayBuyCount.incrementAndGet();

                    log.info("[스윙봇-{}] ★ 스윙 진입 ★ {} ({}) {}원 x {}주 | {}",
                            currentMode.name(), candidate.getStockName(), candidate.getStockCode(),
                            formatNumber(currentPrice), quantity, investorLabel + candidate.getConsecutiveDays() + "일연속");

                    // 텔레그램 알림
                    if (telegramService.isEnabled()) {
                        String modeTag = currentMode == TradingMode.REAL ? "실전" : "모의";
                        telegramService.sendSignal(String.format(
                                "<b>📈 [%s] 스윙 진입</b>\n\n🎯 <b>%s</b> (%s)\n💰 %s원 x %d주\n\n📊 %s %d일 연속매수 (일평균 %s억)\n🔴 손절: %s%% | 🟢 익절: +%s%%\n⏰ 최대 보유: %d일\n\n%s MyPlatform %s",
                                modeTag, candidate.getStockName(), candidate.getStockCode(),
                                formatNumber(currentPrice), quantity,
                                investorLabel, candidate.getConsecutiveDays(),
                                candidate.getAvgDailyAmount().setScale(0, RoundingMode.HALF_UP),
                                SWING_STOP_LOSS, SWING_TAKE_PROFIT, SWING_MAX_HOLD_DAYS,
                                currentMode == TradingMode.REAL ? "🔴" : "🟡", modeTag));
                    }

                    Thread.sleep(500);
                } catch (Exception e) {
                    log.error("[스윙봇] 매수 실패: {} - {}", candidate.getStockName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[스윙봇] 스윙 매수 로직 오류", e);
        }
    }

    /**
     * 연속매수 데이터 조회
     */
    private List<ConsecutiveBuyDto> getConsecutiveBuyData(String investorType) {
        try {
            List<ConsecutiveBuyDto> result = investorTradeService.getConsecutiveBuyStocks(investorType, SWING_MIN_CONSEC_DAYS);
            return result != null ? result : List.of();
        } catch (Exception e) {
            log.debug("[스윙봇] 연속매수 조회 실패 ({}): {}", investorType, e.getMessage());
            return List.of();
        }
    }

    /**
     * 스윙 기술적 조건 체크: MA20 지지 + RSI < 65
     */
    private boolean checkSwingTechnical(String stockCode) {
        try {
            List<BigDecimal> closePrices = kisService.getDailyClosePricesWithPriority(
                    stockCode, 30, KisApiRateLimiter.Priority.LOW);
            if (closePrices == null || closePrices.size() < 20) return false;

            TechnicalIndicatorsDto indicators = technicalIndicatorService.calculate(closePrices);
            if (indicators == null) return false;

            // RSI 과열 체크
            if (indicators.getRsi14() != null && indicators.getRsi14().compareTo(SWING_RSI_LIMIT) >= 0) {
                log.debug("[스윙봇] RSI 과열: {} ({})", stockCode, indicators.getRsi14());
                return false;
            }

            // MA20 지지 체크: 현재가가 MA20 근처 또는 상회
            if (indicators.getMa20() != null) {
                BigDecimal latestPrice = closePrices.get(closePrices.size() - 1);
                BigDecimal ma20Floor = indicators.getMa20().multiply(new BigDecimal("0.97")); // MA20 -3% 이내
                if (latestPrice.compareTo(ma20Floor) < 0) {
                    log.debug("[스윙봇] MA20 하회 심화: {} (가격:{} < MA20×0.97:{})",
                            stockCode, latestPrice, ma20Floor);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.debug("[스윙봇] 기술 체크 실패: {} - {}", stockCode, e.getMessage());
            return false;
        }
    }

    // ==================== [전략 B] 스윙 매도 (30초 간격) ====================

    /**
     * 스윙 포지션 감시: 익절/손절/트레일링/일수 타임컷
     */
    @Scheduled(cron = "*/30 * 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void executeSwingSellLogic() {
        if (!botActive.get() || swingPositions.isEmpty()) return;
        if (isMarketClosed()) return;

        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 5)) || now.isAfter(LocalTime.of(15, 20))) return;

        try {
            List<PortfolioItemDto> portfolios = activeTradeService.getPortfolio();
            Map<String, PortfolioItemDto> portfolioMap = portfolios.stream()
                    .collect(Collectors.toMap(PortfolioItemDto::getStockCode, p -> p, (a, b) -> a));

            for (SwingPosition position : new java.util.ArrayList<>(swingPositions.values())) {
                PortfolioItemDto portfolio = portfolioMap.get(position.stockCode);
                if (portfolio == null) {
                    swingPositions.remove(position.stockCode);
                    continue;
                }

                StockPriceDto priceDto = stockPriceService.getStockPrice(position.stockCode);
                if (priceDto == null || priceDto.getCurrentPrice() == null) continue;

                BigDecimal currentPrice = priceDto.getCurrentPrice();
                position.updateHighPrice(currentPrice);

                BigDecimal profitRate = currentPrice.subtract(position.buyPrice)
                        .divide(position.buyPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                BigDecimal highDropRate = currentPrice.subtract(position.highPrice)
                        .divide(position.highPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                long holdDays = position.holdDays();
                String sellReason = null;

                // 1. 손절 -3%
                if (profitRate.compareTo(SWING_STOP_LOSS) <= 0) {
                    sellReason = "STOP_LOSS";
                    log.info("[스윙봇] 손절: {} 손익률 {}%", position.stockName, profitRate);
                }
                // 2. 익절 +5%
                else if (profitRate.compareTo(SWING_TAKE_PROFIT) >= 0) {
                    sellReason = "TAKE_PROFIT";
                    log.info("[스윙봇] 익절: {} 손익률 {}%", position.stockName, profitRate);
                }
                // 3. 트레일링 (고점 대비 -2%, 수익 구간에서만)
                else if (profitRate.compareTo(new BigDecimal("2.0")) > 0
                        && highDropRate.compareTo(SWING_TRAILING_STOP) <= 0) {
                    sellReason = "TRAILING_STOP";
                    log.info("[스윙봇] 트레일링: {} 고점대비 {}%", position.stockName, highDropRate);
                }
                // 4. 일수 타임컷 (5일 초과)
                else if (holdDays >= SWING_MAX_HOLD_DAYS) {
                    sellReason = "TIME_CUT";
                    log.info("[스윙봇] 일수 타임컷: {} {}일 보유, 손익률 {}%", position.stockName, holdDays, profitRate);
                }

                if (sellReason != null) {
                    try {
                        activeTradeService.sell(portfolio.getStockCode(), currentPrice,
                                portfolio.getQuantity(), sellReason);
                        lastTradeTime = LocalDateTime.now();
                        todaySellCount.incrementAndGet();

                        BigDecimal profitLoss = currentPrice.subtract(position.buyPrice)
                                .multiply(BigDecimal.valueOf(portfolio.getQuantity()));

                        log.info("[스윙봇-{}] {} 완료: {} x {} @ {}원, 손익: {}원 ({}일 보유)",
                                currentMode.name(), sellReason, position.stockName,
                                portfolio.getQuantity(), formatNumber(currentPrice),
                                formatNumber(profitLoss), holdDays);

                        if (telegramService.isEnabled()) {
                            String emoji = profitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "🟢" : "🔴";
                            telegramService.sendSignal(String.format(
                                    "%s <b>스윙 %s</b>: %s\n💰 %s원 (손익률 %s%%)\n⏰ %d일 보유 | 사유: %s",
                                    emoji, sellReason, position.stockName,
                                    formatNumber(profitLoss), profitRate.setScale(2, RoundingMode.HALF_UP),
                                    holdDays, sellReason));
                        }

                        swingPositions.remove(position.stockCode);
                        sellCooldownMap.put(position.stockCode, LocalDateTime.now());
                    } catch (Exception e) {
                        log.error("[스윙봇] 매도 실패: {} - {}", position.stockName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[스윙봇] 스윙 매도 로직 오류", e);
        }
    }

    // ==================== [전략 C] 종가 매수 (15:15, 평일) ====================

    /**
     * 종가 매수: 장 마감 직전 확정 수급 데이터로 매수
     * - 15:15 실행 (장 마감 5분 전, 수급 거의 확정)
     * - 외국인+기관 동시 순매수 상위 종목
     * - 다음날 갭업 노림 (익절 +2%, 손절 -2%)
     */
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Seoul")
    public void executeClosingBuyLogic() {
        if (!botActive.get() || killSwitchTriggered.get()) return;
        if (isMarketClosed()) return;

        if (closingPositions.size() >= CLOSING_MAX_HOLDING) {
            log.debug("[종가매수] 최대 보유 {}종목 도달 — 스킵", CLOSING_MAX_HOLDING);
            return;
        }

        log.info("[종가매수] ===== 종가 매수 체크 시작 =====");

        try {
            // 외국인+기관 동시 순매수 종목 조회 (수급급증 데이터 활용)
            Map<String, List<InvestorSurgeDto>> surgeMap = getCachedSurgeStocks();
            if (surgeMap == null || surgeMap.isEmpty()) {
                log.info("[종가매수] 수급 데이터 없음 — 스킵");
                return;
            }

            // 외국인 + 기관 데이터 합치기
            Map<String, BigDecimal> foreignBuy = new LinkedHashMap<>();
            Map<String, BigDecimal> instBuy = new LinkedHashMap<>();
            Map<String, InvestorSurgeDto> surgeInfo = new LinkedHashMap<>();

            for (var entry : surgeMap.entrySet()) {
                for (InvestorSurgeDto surge : entry.getValue()) {
                    if (surge.getNetBuyAmount() == null || surge.getNetBuyAmount().compareTo(BigDecimal.ZERO) <= 0) continue;
                    surgeInfo.putIfAbsent(surge.getStockCode(), surge);
                    String investorType = surge.getInvestorType();
                    if ("FOREIGN".equals(investorType)) {
                        foreignBuy.merge(surge.getStockCode(), surge.getNetBuyAmount(), BigDecimal::add);
                    } else if ("INSTITUTION".equals(investorType)) {
                        instBuy.merge(surge.getStockCode(), surge.getNetBuyAmount(), BigDecimal::add);
                    }
                }
            }

            // 외국인+기관 동시 순매수 종목 필터
            List<String> candidates = foreignBuy.keySet().stream()
                    .filter(instBuy::containsKey)
                    .filter(code -> {
                        BigDecimal total = foreignBuy.get(code).add(instBuy.get(code));
                        return total.compareTo(CLOSING_MIN_NET_BUY) >= 0;
                    })
                    .sorted((a, b) -> {
                        BigDecimal totalA = foreignBuy.get(a).add(instBuy.get(a));
                        BigDecimal totalB = foreignBuy.get(b).add(instBuy.get(b));
                        return totalB.compareTo(totalA);
                    })
                    .collect(Collectors.toList());

            log.info("[종가매수] 외국인+기관 동시매수 후보: {}건", candidates.size());
            if (candidates.isEmpty()) return;

            // 잔고 확인
            AccountSummaryDto account = activeTradeService.getAccountSummary();
            BigDecimal totalAsset = account.getCurrentBalance().add(
                    account.getTotalEvaluation() != null ? account.getTotalEvaluation() : BigDecimal.ZERO);
            BigDecimal maxPerStock = totalAsset.multiply(CLOSING_INVESTMENT_RATIO);

            // 이미 보유 중인 종목 제외
            Set<String> holdingCodes = activeTradeService.getPortfolio().stream()
                    .map(PortfolioItemDto::getStockCode).collect(Collectors.toSet());

            for (String code : candidates) {
                if (closingPositions.size() >= CLOSING_MAX_HOLDING) break;
                if (holdingCodes.contains(code)) continue;
                if (closingPositions.containsKey(code)) continue;
                if (swingPositions.containsKey(code)) continue;

                InvestorSurgeDto surge = surgeInfo.get(code);
                if (surge == null) continue;

                // 양봉 확인 (등락률 > 0)
                if (surge.getChangeRate() == null || surge.getChangeRate().doubleValue() <= 0) {
                    log.debug("[종가매수] Skip {} — 음봉", surge.getStockName());
                    continue;
                }

                // RSI 과열 체크
                try {
                    List<BigDecimal> closePrices = kisService.getDailyClosePricesWithPriority(
                            code, 20, KisApiRateLimiter.Priority.LOW);
                    if (closePrices != null && closePrices.size() >= 14) {
                        TechnicalIndicatorsDto ind = technicalIndicatorService.calculate(closePrices);
                        if (ind != null && ind.getRsi14() != null && ind.getRsi14().compareTo(CLOSING_RSI_LIMIT) >= 0) {
                            log.debug("[종가매수] Skip {} — RSI 과열 ({})", surge.getStockName(), ind.getRsi14());
                            continue;
                        }
                    }
                } catch (Exception e) {
                    log.debug("[종가매수] RSI 체크 실패 {} — 진입 허용", code);
                }

                // 현재가 조회
                StockPriceDto priceDto = stockPriceService.getStockPrice(code);
                if (priceDto == null || priceDto.getCurrentPrice() == null) continue;
                BigDecimal currentPrice = priceDto.getCurrentPrice();

                int quantity = maxPerStock.divide(currentPrice, 0, RoundingMode.DOWN).intValue();
                if (quantity <= 0) continue;

                // 매수 실행
                try {
                    BigDecimal fAmt = foreignBuy.get(code);
                    BigDecimal iAmt = instBuy.get(code);

                    activeTradeService.buy(code, surge.getStockName(), currentPrice, quantity, "CLOSING_BUY");

                    closingPositions.put(code, new SwingPosition(code, surge.getStockName(),
                            currentPrice, String.format("외국인%.0f억+기관%.0f억", fAmt, iAmt)));

                    lastTradeTime = LocalDateTime.now();
                    todayBuyCount.incrementAndGet();

                    log.info("[종가매수-{}] ★ 진입 ★ {} ({}) {}원 x {}주 | 외국인 {}억 + 기관 {}억",
                            currentMode.name(), surge.getStockName(), code,
                            formatNumber(currentPrice), quantity, fAmt, iAmt);

                    // 텔레그램 알림
                    if (telegramService.isEnabled()) {
                        String modeTag = currentMode == TradingMode.REAL ? "실전" : "모의";
                        telegramService.sendSignal(String.format(
                                "<b>🌆 [%s] 종가 매수</b>\n\n🎯 <b>%s</b> (%s)\n💰 %s원 x %d주\n\n📊 외국인 %s억 + 기관 %s억 (동시 매수)\n📈 등락률: %s%%\n🔴 손절: %s%% | 🟢 익절: +%s%%\n⏰ 최대 보유: %d일\n\n%s MyPlatform %s",
                                modeTag, surge.getStockName(), code,
                                formatNumber(currentPrice), quantity,
                                fAmt.setScale(0, RoundingMode.HALF_UP),
                                iAmt.setScale(0, RoundingMode.HALF_UP),
                                surge.getChangeRate(),
                                CLOSING_STOP_LOSS, CLOSING_TAKE_PROFIT, CLOSING_MAX_HOLD_DAYS,
                                currentMode == TradingMode.REAL ? "🔴" : "🟡", modeTag));
                    }

                    holdingCodes.add(code);
                    Thread.sleep(500);
                } catch (Exception e) {
                    log.error("[종가매수] 매수 실패: {} - {}", surge.getStockName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[종가매수] 종가 매수 로직 오류", e);
        }
    }

    // ==================== [전략 C] 종가 매수 포지션 감시 (30초 간격) ====================

    /**
     * 종가 매수 포지션 감시: 익절/손절/트레일링/일수 타임컷
     */
    @Scheduled(cron = "*/30 * 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void executeClosingSellLogic() {
        if (!botActive.get() || closingPositions.isEmpty()) return;
        if (isMarketClosed()) return;

        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 5)) || now.isAfter(LocalTime.of(15, 20))) return;

        try {
            List<PortfolioItemDto> portfolios = activeTradeService.getPortfolio();
            Map<String, PortfolioItemDto> portfolioMap = portfolios.stream()
                    .collect(Collectors.toMap(PortfolioItemDto::getStockCode, p -> p, (a, b) -> a));

            for (SwingPosition position : new java.util.ArrayList<>(closingPositions.values())) {
                PortfolioItemDto portfolio = portfolioMap.get(position.stockCode);
                if (portfolio == null) {
                    closingPositions.remove(position.stockCode);
                    continue;
                }

                StockPriceDto priceDto = stockPriceService.getStockPrice(position.stockCode);
                if (priceDto == null || priceDto.getCurrentPrice() == null) continue;

                BigDecimal currentPrice = priceDto.getCurrentPrice();
                position.updateHighPrice(currentPrice);

                BigDecimal profitRate = currentPrice.subtract(position.buyPrice)
                        .divide(position.buyPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                BigDecimal highDropRate = currentPrice.subtract(position.highPrice)
                        .divide(position.highPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                long holdDays = position.holdDays();
                String sellReason = null;

                // 1. 손절 -2%
                if (profitRate.compareTo(CLOSING_STOP_LOSS) <= 0) {
                    sellReason = "STOP_LOSS";
                }
                // 2. 익절 +2%
                else if (profitRate.compareTo(CLOSING_TAKE_PROFIT) >= 0) {
                    sellReason = "TAKE_PROFIT";
                }
                // 3. 트레일링 (수익 +1% 이후 고점 대비 -1%)
                else if (profitRate.compareTo(new BigDecimal("1.0")) > 0
                        && highDropRate.compareTo(CLOSING_TRAILING_STOP) <= 0) {
                    sellReason = "TRAILING_STOP";
                }
                // 4. 일수 타임컷 (2일 초과)
                else if (holdDays >= CLOSING_MAX_HOLD_DAYS) {
                    sellReason = "TIME_CUT";
                }

                if (sellReason != null) {
                    try {
                        activeTradeService.sell(portfolio.getStockCode(), currentPrice,
                                portfolio.getQuantity(), sellReason);
                        lastTradeTime = LocalDateTime.now();
                        todaySellCount.incrementAndGet();

                        BigDecimal profitLoss = currentPrice.subtract(position.buyPrice)
                                .multiply(BigDecimal.valueOf(portfolio.getQuantity()));

                        log.info("[종가매수-{}] {} 완료: {} 손익: {}원 ({}%, {}일 보유)",
                                currentMode.name(), sellReason, position.stockName,
                                formatNumber(profitLoss), profitRate.setScale(2, RoundingMode.HALF_UP), holdDays);

                        if (telegramService.isEnabled()) {
                            String emoji = profitLoss.compareTo(BigDecimal.ZERO) >= 0 ? "🟢" : "🔴";
                            telegramService.sendSignal(String.format(
                                    "%s <b>종가매수 %s</b>: %s\n💰 %s원 (%s%%)\n⏰ %d일 보유",
                                    emoji, sellReason, position.stockName,
                                    formatNumber(profitLoss), profitRate.setScale(2, RoundingMode.HALF_UP), holdDays));
                        }

                        closingPositions.remove(position.stockCode);
                        sellCooldownMap.put(position.stockCode, LocalDateTime.now());
                    } catch (Exception e) {
                        log.error("[종가매수] 매도 실패: {} - {}", position.stockName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[종가매수] 매도 로직 오류", e);
        }
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

                    telegramService.sendRisk(
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

    /**
     * 거래량 증가 추세 확인 (타임컷 동적 연장용)
     * 현재 체결강도(거래량 파워)가 110% 이상이면 거래량 증가로 판단
     */
    private boolean isVolumeIncreasing(String stockCode) {
        try {
            var analysis = scalpingAnalysisService.getScalpingAnalysis(stockCode);
            if (analysis == null || analysis.getVolumePower() == null) return false;
            // 체결강도 110% 이상이면 매수세 유입 중
            return analysis.getVolumePower().compareTo(new BigDecimal("110")) >= 0;
        } catch (Exception e) {
            log.debug("[스캘핑봇] 거래량 추세 확인 실패 [{}]: {}", stockCode, e.getMessage());
            return false;
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

    private synchronized void resetDailyCounters() {
        LocalDate today = LocalDate.now();
        if (lastResetDate == null || !lastResetDate.equals(today)) {
            todayBuyCount.set(0);
            todaySellCount.set(0);
            killSwitchTriggered.set(false);
            consecutiveStopLossCount.set(0);
            consecutiveStopLossPaused.set(false);
            kospiDropPaused.set(false);
            scalpingPositions.clear();
            sellCooldownMap.clear();
            outflowSectorStocks.clear();
            outflowCacheTime = null;
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

        telegramService.sendSignal(
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

        telegramService.sendSignal(
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

        telegramService.sendSignal(message.toString());
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
