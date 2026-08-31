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
import com.myplatform.backend.entity.BotTradingPosition;
import com.myplatform.backend.entity.BotTradingPosition.Strategy;
import com.myplatform.backend.repository.BotConfigRepository;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.VirtualPortfolioRepository;
import com.myplatform.backend.util.AtrCalculator;
import com.myplatform.backend.util.AtrExitRule;
import com.myplatform.backend.util.OrderSession;
import com.myplatform.backend.util.PositionSizer;
import com.myplatform.backend.util.PriceSanityGuard;
import com.myplatform.core.util.DateTimeUtil;
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
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 자동 매매 봇 서비스 (2전략: 스캘핑(모의만) + 스윙)
 *
 * ⚠ [동시성 전제] 이 봇은 <b>단일 인스턴스 전제</b>로 SchedulerLockService(분산락)를 사용하지 않는다.
 *   중복 진입 방지는 JVM 내 가드(scalpingPositions.putIfAbsent · 보유종목 체크 · 매도 쿨다운)뿐 — 인스턴스 간엔 무력.
 *   멀티 인스턴스로 확장하면 봇 크론에 <b>fail-closed 락</b>이 필수다(현 SchedulerLockService 는 fail-open 이라
 *   Redis 장애 시 둘 다 통과 → 중복 주문을 못 막음). 확장 전 별도 설계 필요.
 *
 * ========================================
 * [전략 A] 스캘핑 — ★ 모의투자 전용 (실전 비활성)
 * ========================================
 *    지연 데이터 기반 단타는 구조적 열위 → 실전에서 OFF
 *
 * ========================================
 * [전략 B] 스윙 (수급 추종, 2~5일 보유)
 * ========================================
 *    매수: 외국인/기관 3일+ 연속매수 + MA20 지지 + RSI < 60
 *    매도: 익절 +5%, 손절 -3%, 최대 5일 보유
 *    시간: 14:00 체크
 *
 * ========================================
 * [전략 C] 종가 매수 — 비활성
 * ========================================
 *    1) 스캘핑 매도가 종가매수 포지션을 타임컷으로 잘못 청산하는 구조적 충돌
 *    2) 15:15 수급 데이터 신뢰성 문제 (장 마감까지 뒤집힐 수 있음)
 *    → 2026-09-14 거래시간 연장(애프터마켓 20시) 도입 후 전략 재설계 필요
 *
 * ========================================
 * [트랙 정합 — 코드 기준 확정] (P2-6, 가드: AutoTradingBotTrackTest)
 * ========================================
 *   ▶ 한 줄 요약: <b>전략 2 / 활성 크론 8 / cron 메서드 10</b> (= 활성 8 + 청산봇 2 비활성)
 *   ★ 활성 @Scheduled 8개 (cron 은 메서드 위 어노테이션 참조 — 주석엔 시각만.
 *      개수 가드 = AutoTradingBotTrackTest.exactlyEightScheduled — 켜/끄면 테스트+이 표 동시 갱신):
 *     1) executeScalpingBuyLogic   30초/9-11시   스캘핑 매수(모의 전용)
 *     2) executeScalpingSellLogic  15초/8-19시   스캘핑 매도
 *     3) executeScalpingClearance  15:10        스캘핑 청산
 *     4) executeSwingBuyLogic      14:00        스윙 매수
 *     5) executeSwingSellLogic     30초/8-19시   스윙 매도
 *     6) executeRegularSessionLiquidation 15:20~28 매분  정규장 마감 강제청산(윈도우 재시도)
 *     7) warnIfLiquidationMissed   15:29        청산 미완료 텔레그램 경고
 *     8) executeNxtLiquidationRetry 15:35~19:55 매5분  NXT 방어 청산 재시도(flag OFF 기본=선차단, 2026-09-14 대비)
 *   ☓ 비활성(@Scheduled 주석처리) 2개:
 *     9) executeClosingBuyLogic / 10) executeClosingSellLogic — 종가 전략(재설계 완료, 재활성 대기)
 *   ※ "전략" 2개(스캘핑·스윙) ≠ "크론 트랙" 8개 ≠ "cron 메서드" 10개(비활성 청산봇 2 포함).
 *      청산봇 재활성은 별도 판단 사항 — 15:10 스캘핑 청산은 청산봇 포지션을 보존(2021줄)하고,
 *      스캘핑/스윙 매도도 closingPositions 를 명시적으로 제외(1761줄 등)하므로 "중복 청산"은 아님.
 *      재활성의 실질 블로커는 15:15 수급 미확정 + 2026-09-14 거래시간 연장 재설계 대기(아래 [전략 C] 참조).
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
    private final GlobalMarketService globalMarketService;
    private final BotTradingPositionRepository positionRepository;
    // 가격 sanity 가드 앵커(전일 종가) — StockPriceHistory 는 J(KRX 단독) 소스라 UN 통합시세 오염과 독립.
    private final StockPriceHistoryRepository stockPriceHistoryRepository;
    // KIS WebSocket 실시간 시세 — kis.websocket.enabled=true 일 때만 빈이 등록되므로
    // ObjectProvider 로 optional 주입. 비활성 환경에서도 봇이 정상 동작.
    private final org.springframework.beans.factory.ObjectProvider<RealtimePriceBus> realtimePriceBusProvider;
    // KST Clock — 시간 의존 로직(킬스위치/시장시간)을 테스트 가능하게 분리.
    // 운영: ClockConfig#kstClock() → Clock.system(Asia/Seoul) 주입 → 기존 동작 동일.
    // 테스트: Clock.fixed(...) 로 결정론적 시각 주입.
    private final Clock clock;

    // 멀티 인스턴스 중복 주문 방지 — 봇 크론은 리더일 때만 실주문(fail-CLOSED). SchedulerLockService(fail-open)와 별개.
    private final BotLeaderElectionService botLeader;
    // 일일 손실 서킷브레이커(V38) — 신규 진입 게이트 전용(매도 경로 미사용). 미가용=통과.
    private final org.springframework.beans.factory.ObjectProvider<DailyLossBreakerService> dailyLossBreakerProvider;
    // ATR 세트(V42) 적용값 감사 스냅샷 — 미가용=로그만(주문 흐름 무영향).
    private final org.springframework.beans.factory.ObjectProvider<TradingAuditService> auditProvider;
    // VKOSPI 변동성 국면 게이트(V46) — 신규 진입 게이트 전용(매도 미관여). 미가용/mode OFF=통과(fail-open).
    private final org.springframework.beans.factory.ObjectProvider<VolatilityRegimeService> volRegimeProvider;

    // ── ATR 세트 flag (V42, docs/ATR_TRADING_SET.md) ──────────────────────────────
    // 기본 false — OFF 면 수량/청산 모두 바이트 단위 현행 동일. ON 이어도 REAL 은 무조건 현행(하드 가드,
    // isAtrSetActive). 진입 여부 판단(수급/기술/안전 게이트)엔 관여하지 않는다 — 수량·청산폭만.
    @org.springframework.beans.factory.annotation.Value("${bot.atr-trading.enabled:false}")
    private volatile boolean atrTradingEnabled;
    /** ATR 세트 riskBudget 설정 행 (bot_config 전용 행 — 'trading_bot'/'daily_loss_breaker' 행 분리 원칙). */
    static final String ATR_CONFIG_KEY = "atr_trading";

    // ── NXT 방어 청산 flag (2026-09-14 대비, docs/NXT_ROUTING_DESIGN.md §2) ────────────
    // 기본 false — OFF 면 executeNxtLiquidationRetry 는 early-return(현행 동일). 활성화는 9/14 이후.
    // nxt-liquidation.enabled(청산 재시도 스위치) AND nxt-routing.enabled(주문 라우팅, Phase 1) 둘 다 ON 이어야 동작.
    @org.springframework.beans.factory.annotation.Value("${bot.nxt-liquidation.enabled:false}")
    private volatile boolean nxtLiquidationEnabled;
    @org.springframework.beans.factory.annotation.Value("${bot.nxt-routing.enabled:false}")
    private volatile boolean nxtRoutingEnabled;
    // NXT 방어 청산 창: 정규장 청산(15:20~15:28)·15:29 경고 종료 후 ~ NXT 종료(20:00) 직전.
    private static final LocalTime NXT_LIQUIDATION_START = LocalTime.of(15, 35);
    private static final LocalTime NXT_LIQUIDATION_END = LocalTime.of(19, 55);

    // ── 종가 매수 진입 창 (재활성 대비, 설정 이동 가능 — 2026-09-14 종가단일가 시각 변경 대응) ──
    // ⚠ §16-2: 진입은 KRX 정규장(09:00~15:30) 내로만. isMarketClosed()(15:30) 하드 상한이 NXT 진입을 구조적으로 차단.
    // 잘못된 설정(파싱 실패)은 안전 폴백(15:15~15:20). @Scheduled 재활성 시 cron 도 이 창에 맞춰 조정.
    @org.springframework.beans.factory.annotation.Value("${bot.closing.entry-start:15:15}")
    private String closingEntryStartCfg;
    @org.springframework.beans.factory.annotation.Value("${bot.closing.entry-end:15:20}")
    private String closingEntryEndCfg;

    // ╔══════════════════════════════════════════════════════════════╗
    // ║  [A] 스캘핑 전략 (모의투자 전용, 09:45~10:30 골든타임)        ║
    // ╠══════════════════════════════════════════════════════════════╣
    // ║  매수: 순매수≥10억 + 양봉 + 변동폭≥1.5% + 보조 2/4          ║
    // ║  매도: 손절-1.2% / 익절+1.2%(반) / 트레일링-0.8% / 15~20분  ║
    // ║  청산: 15:10 장 마감 전 스캘핑 전량 청산 (종가매수 15:15 전)   ║
    // ╚══════════════════════════════════════════════════════════════╝

    // -- 필수 매수 조건 --
    private static final BigDecimal MIN_NET_BUY_AMOUNT = new BigDecimal("10");      // 순매수 ≥ 10억
    private static final long MIN_TRADING_VALUE = 20_000_000_000L;                  // 거래대금 ≥ 200억
    private static final int MIN_VOLUME_RATIO = 200;                                // 전일 대비 거래량 ≥ 200%
    private static final BigDecimal MIN_INTRADAY_RANGE = new BigDecimal("1.5");     // 장중 변동폭 ≥ 1.5%
    private static final BigDecimal MIN_VOLUME_POWER = new BigDecimal("120");       // 보조: 체결강도 ≥ 120% (null 시 스킵)
    private static final LocalTime MORNING_ENTRY_START = LocalTime.of(9, 10);       // 진입 시작 (테스트: 09:10~15:00)
    private static final LocalTime MORNING_ENTRY_END = LocalTime.of(15, 0);        // 진입 종료

    // -- 보조 매수 조건 (3개 중 2개 충족) --
    private static final BigDecimal RSI_ENTRY_LIMIT = new BigDecimal("55");         // 보조A: RSI < 55
    private static final BigDecimal DISPARITY_20MA_LIMIT = new BigDecimal("3");     // 보조B: 20MA 이격도 < 3%
    private static final BigDecimal GAP_UP_LIMIT = new BigDecimal("5");             // 보조C: 갭상승 < 5%
    private static final int REQUIRED_SUB_CONDITIONS = 2;                           // 보조 조건 최소 충족 수

    // -- 매도 조건 --
    private static final BigDecimal STOP_LOSS_RATE = new BigDecimal("-1.2");        // 손절: -1.2% (손익비 개선)
    private static final BigDecimal TAKE_PROFIT_FIRST = new BigDecimal("1.2");      // 1차 익절: +1.2% (절반)
    private static final BigDecimal TRAILING_STOP_RATE = new BigDecimal("-0.8");    // 트레일링: 고점 -0.8%
    private static final int TIME_CUT_MINUTES = 15;                                 // 타임컷: 15분
    private static final int TIME_CUT_EXTENDED_MINUTES = 20;                        // 동적 연장 최대: 20분
    private static final BigDecimal TIME_EXTEND_MIN_PROFIT = new BigDecimal("0.3"); // 연장 조건: +0.3% 이상
    private static final BigDecimal VOLUME_INCREASING_THRESHOLD = new BigDecimal("110"); // 거래량 증가 판단: 110%

    // -- 리스크 관리 --
    private static final BigDecimal MAX_INVESTMENT_RATIO = new BigDecimal("0.15");  // 종목당 최대 투자비율 15%
    private static final int MAX_HOLDING_STOCKS = 3;                                // 최대 보유 종목 수
    private static final int MAX_SCALPING_TRADES_PER_DAY = 10;                      // 일일 최대 매수 횟수 (테스트: 10회)
    private static final int SELL_COOLDOWN_MINUTES = 30;                            // 매도 후 재매수 쿨다운
    private static final BigDecimal MIN_BALANCE = new BigDecimal("100000");         // 최소 잔액: 10만원
    private static final int CONSECUTIVE_STOP_LOSS_LIMIT = 3;                       // 연속 손절 중지 한도

    // ╔══════════════════════════════════════════════════════════════╗
    // ║  [B] 스윙 전략 (수급 추종, 2~5일 보유, 14:00 체크)            ║
    // ╠══════════════════════════════════════════════════════════════╣
    // ║  매수: 외인/기관 3일+ 연속순매수 + MA20 지지 + RSI<65        ║
    // ║  매도: 손절-3% / 익절+5% / 트레일링-2%(+2%후) / 최대5일     ║
    // ║  2026-04-24 튜닝: 매수 기회 확대 + 분산 개선 (손절선은 유지)  ║
    // ║    - MAX_HOLDING      2 → 3     (슬롯 +1, 분산도 ↑)         ║
    // ║    - INVESTMENT_RATIO 20% → 25% (최대 노출 40% → 75%)      ║
    // ║    - MIN_AVG_AMOUNT   10억 → 7억 (후보 종목 풀 확대)        ║
    // ║    - RSI_LIMIT        60 → 65   (조금 늦은 진입도 허용)     ║
    // ╚══════════════════════════════════════════════════════════════╝
    private static final BigDecimal SWING_STOP_LOSS = new BigDecimal("-3.0");
    private static final BigDecimal SWING_TAKE_PROFIT = new BigDecimal("5.0");
    private static final BigDecimal SWING_TRAILING_STOP = new BigDecimal("-2.0");
    private static final BigDecimal SWING_TRAILING_MIN_PROFIT = new BigDecimal("2.0"); // 트레일링 발동 최소 수익률
    private static final int SWING_MAX_HOLD_DAYS = 5;
    private static final int SWING_MIN_CONSEC_DAYS = 3;                             // 최소 연속 순매수 일수
    private static final BigDecimal SWING_MIN_AVG_AMOUNT = new BigDecimal("7");     // 일평균 순매수 ≥ 7억 (10억 → 7억)
    private static final BigDecimal SWING_RSI_LIMIT = new BigDecimal("65");         // RSI 상한: 65 (60 → 65, 조금 늦은 진입 허용)
    private static final BigDecimal SWING_MA20_SUPPORT = new BigDecimal("0.97");    // MA20 × 0.97 (-3% 이내)
    private static final int SWING_MAX_HOLDING = 2;                                 // 최대 보유: 2종목 (3 → 2, 50만 소액 운용)
    private static final BigDecimal SWING_INVESTMENT_RATIO = new BigDecimal("0.50"); // 종목당 투자비율 50% (25% → 50%, 소액 운용 시 매수가능 종목 풀 확장)

    // [C] 종가매수 전략 — 비활성 (포지션 충돌 + 수급 미확정)
    // 2026-09-14 거래시간 연장 후 재설계 필요. 상수 및 로직 보존.
    private static final BigDecimal CLOSING_STOP_LOSS = new BigDecimal("-2.0");
    private static final BigDecimal CLOSING_TAKE_PROFIT = new BigDecimal("2.0");
    private static final BigDecimal CLOSING_TRAILING_STOP = new BigDecimal("-1.0");
    private static final BigDecimal CLOSING_TRAILING_MIN_PROFIT = new BigDecimal("1.0");
    private static final int CLOSING_MAX_HOLD_DAYS = 2;
    private static final BigDecimal CLOSING_MIN_NET_BUY = new BigDecimal("50");
    private static final BigDecimal CLOSING_RSI_LIMIT = new BigDecimal("70");
    private static final int CLOSING_MAX_HOLDING = 2;
    private static final BigDecimal CLOSING_INVESTMENT_RATIO = new BigDecimal("0.15");
    private static final BigDecimal CLOSING_EARLY_EXIT_MIN_PROFIT = new BigDecimal("0.5");
    private static final LocalTime CLOSING_EARLY_EXIT_TIME = LocalTime.of(10, 0);
    private static final BigDecimal CLOSING_GAP_DOWN_LIMIT = new BigDecimal("-1.0");
    private static final LocalTime CLOSING_GAP_DOWN_CHECK_TIME = LocalTime.of(9, 5);

    // ╔══════════════════════════════════════════════════════════════╗
    // ║  공통: 시장 시간 / 킬 스위치 / 글로벌 리스크                  ║
    // ╠══════════════════════════════════════════════════════════════╣
    // ║  킬스위치: 스캘핑 -1.5% / 전체(스윙 포함) -3%                ║
    // ║  나스닥 선물 ≤ -1% → 스캘핑 매수 보류                        ║
    // ╚══════════════════════════════════════════════════════════════╝
    // [매도/청산 시간 윈도우 — ⚠ 실제 binding 은 isMarketClosed()(15:30), 08:00~20:00 아님]
    //   진입(매수)은 KRX 중심(스캘핑 09~11, 스윙 14:00). 아래 매도/청산 상수 PRE_MARKET_START~
    //   AFTER_MARKET_END(08:00~20:00)는 넓게 잡혀 있으나, 두 매도 내부(executeScalpingSellLogicInternal·
    //   executeSwingSellLogicInternal)가 이 08~20 윈도우 체크보다 '먼저' isMarketClosed()(MARKET_CLOSE=15:30)를
    //   호출해 return 한다 → 실제 매도 가능 상한은 15:30, 08~20 윈도우는 15:30 이후 사실상 死코드.
    //   (당초 NXT 애프터마켓(2026-09-14) 확장 대비로 넓혀 둔 상수이나 isMarketClosed 15:30 이 그걸 선차단.)
    //   ※ NXT 방어 청산 배선(2026-07-08, flag OFF): 정규 매도/청산 경로는 여전히 isMarketClosed(15:30) 상한 유지.
    //     NXT 연장세션(15:30~20:00) 청산은 이 이중가드를 건드리지 않는 별도 경로 executeNxtLiquidationRetry 가
    //     담당한다(FORCE_LIQUIDATION 처럼 isMarketClosed 우회, OrderSession.NXT_EXTENDED 라우팅). 진입은 불변(§16-2).
    //     즉 08~20 윈도우 상수를 여기서 되살리지 말 것 — NXT 는 별도 함수·flag 로만. VERIFICATION_BACKLOG P2-13.
    //   ※ REGULAR_END(15:25)는 봇 자체 상수로, MarketCalendarService.MARKET_CLOSE(15:40, 종가단일가 버퍼)와 다름 — 봇은 별도 판정.
    //   ※ FORCE_LIQUIDATION(15:20~15:28)만 isMarketClosed 를 안 거치는 별도 경로(정규장 마감 강제청산, P2-13 진단 참고).
    //   ※ 15:10 스캘핑 청산(scalpingPositions.clear) 이후 매도 cron 이 (15:30까지) 돌아도, 스캘핑 포지션이 비어
    //     position==null 즉시 skip + 스윙/종가는 명시 제외라 무해.
    private static final LocalTime PRE_MARKET_START = LocalTime.of(8, 0);
    private static final LocalTime REGULAR_START = LocalTime.of(9, 0);
    private static final LocalTime REGULAR_END = LocalTime.of(15, 25);
    private static final LocalTime AFTER_MARKET_END = LocalTime.of(20, 0);
    private static final LocalTime SCALPING_CLEARANCE_TIME = LocalTime.of(15, 10);  // 스캘핑 장 마감 전 청산 (종가매수 15:15 전)
    private static final LocalTime FORCE_LIQUIDATION_TIME = LocalTime.of(15, 20);       // 정규장 마감 강제청산 윈도우 시작 — 연속세션 끝(체결 확실).
    private static final LocalTime FORCE_LIQUIDATION_WINDOW_END = LocalTime.of(15, 28); // 청산 윈도우 끝 — 매분 재시도(리더 페일오버 캐치업). 종가단일가(~15:30)/NXT 청산은 후속.
    private static final LocalTime EOD_CLEARANCE_TIME = LocalTime.of(19, 50);       // 스윙/종가 비상 청산
    private static final BigDecimal KILL_SWITCH_SCALPING_RATE = new BigDecimal("-1.5"); // 스캘핑 킬스위치: -1.5%
    private static final BigDecimal KILL_SWITCH_TOTAL_RATE = new BigDecimal("-3.0");    // 전체 킬스위치: -3%
    private static final double VIX_PAUSE_THRESHOLD = 30.0;                         // VIX 매수 중지
    private static final BigDecimal KOSPI_DROP_LIMIT = new BigDecimal("-1.5");      // KOSPI 하락 매수 중지
    private static final int BUY_DELAY_REAL_MS = 1000;                              // 실전 매수 간 딜레이
    private static final int BUY_DELAY_VIRTUAL_MS = 300;                            // 모의 매수 간 딜레이

    // ========== 봇 상태 상수 ==========
    private static final String BOT_CONFIG_KEY = "trading_bot";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_STOPPED = "STOPPED";

    // 이전 실행이 아직 돌고 있으면 다음 틱 스킵 — 스케줄러 스레드풀(24) 고갈 방지.
    // 2026-04-23 장애: 3초 주기 + EGW00201 백오프 6초 조합으로 스레드가 누적되어 호스트 다운.
    private final AtomicBoolean scalpingBuyRunning = new AtomicBoolean(false);
    private final AtomicBoolean scalpingSellRunning = new AtomicBoolean(false);
    private final AtomicBoolean swingSellRunning = new AtomicBoolean(false);

    // ========== 봇 상태 변수 ==========
    private volatile TradeService activeTradeService;
    private volatile TradingMode currentMode = TradingMode.VIRTUAL;
    private final AtomicBoolean botActive = new AtomicBoolean(false);

    /**
     * 스캘핑 SKIP 사유 — <b>바뀔 때만</b> INFO 로 남기기 위한 직전 값(사유+날짜).
     *
     * <p>이 크론은 09~11시에 30초마다 돈다. 봇을 꺼둔 날엔 "SKIP: botActive=false" 한 줄이
     * 하루 <b>수백 줄</b> 쌓이고, 그만큼 다른 로그가 뒤로 밀려 사고 조사 때 부팅 이후 흐름을
     * 찾기 어려워진다(2026-08-31 종목상태 진단에서 실제로 겪음). 상태가 바뀐 순간만 INFO 로
     * 남기고 나머지 틱은 DEBUG 로 내린다 — <b>정보량은 그대로고 반복만 사라진다.</b>
     * 날짜를 키에 넣어 하루 1회는 반드시 남긴다(꺼짐→켜짐→꺼짐이 무음이 되지 않게).
     */
    private final AtomicReference<String> lastScalpingSkipKey = new AtomicReference<>("");

    /**
     * fail-open 가드의 실패 가시화 — <b>행동 불변, 로그만</b> (봇 감사 2026-08-31).
     *
     * <p>나스닥·VIX·KOSPI·섹터OUTFLOW·공매도·sanity앵커 가드는 실패 시 <b>의도적으로</b>
     * 통과(fail-open)다 — 결측을 근거로 차단하지 않는다(§4c, PriceSanityGuard 트레이드오프와 동일).
     * 문제는 그 실패가 전부 log.debug 라 <b>소스가 영구 사망해도 가드가 꺼진 채 비가시</b>였다는 것
     * — KRX 상장목록이 4개월 죽어 있던 것과 정확히 같은 모양이다. 가드는 30초 틱마다 도니
     * 무제한 WARN 은 로그 잡음(§5 규칙)이라, <b>가드별 하루 첫 실패만 WARN</b> 하고 이후는 DEBUG.
     */
    private final ConcurrentHashMap<String, LocalDate> guardWarnedOn = new ConcurrentHashMap<>();

    private void warnGuardFailureDaily(String guard, String failOpenNote, Exception e) {
        LocalDate today = LocalDate.now(clock);
        if (today.equals(guardWarnedOn.put(guard, today))) {
            log.debug("[봇가드] {} 체크 실패 — {}: {}", guard, failOpenNote, e.getMessage());
        } else {
            log.warn("[봇가드] {} 체크 실패 — {} (계속 실패해도 오늘은 이 한 줄뿐, 지속되면 소스 사망 의심): {}",
                    guard, failOpenNote, e.getMessage());
        }
    }
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
    // 진행 중인 trade 메서드 카운터 — 모드 전환 시 in-flight 종료 대기용.
    // 매수/매도 로직 진입 시 increment, finally 에서 decrement.
    private final AtomicInteger inFlightTrades = new AtomicInteger(0);
    // 종목별 매도 실패 횟수 — 무한 재시도 방지 + 알림 폭주 방지
    private final Map<String, AtomicInteger> sellFailCount = new ConcurrentHashMap<>();
    // 종목별 마지막 매도 실패 알림 시간 — 알림 dedup (5분 cooldown)
    private final Map<String, LocalDateTime> sellFailLastAlert = new ConcurrentHashMap<>();
    private static final int SELL_FAIL_GIVE_UP = 5;          // 5회 실패 시 포기
    private static final long SELL_FAIL_ALERT_COOLDOWN_SEC = 300;  // 5분

    // 종목별 가격 sanity 차단 알림 시간 — 리스크 채널 스팸 방지 (10분 cooldown)
    private final Map<String, LocalDateTime> priceSanityAlertAt = new ConcurrentHashMap<>();
    private static final long PRICE_SANITY_ALERT_COOLDOWN_MIN = 10;
    // 당일 시작 자산 (킬 스위치용)
    private volatile BigDecimal dailyStartAsset = BigDecimal.ZERO;
    // 킬 스위치 발동 여부
    private final AtomicBoolean killSwitchTriggered = new AtomicBoolean(false);       // 전체 킬스위치 (-3%)
    /** 킬스위치 발동일 — startBot 이 <b>같은 날</b> 손실 한도를 리셋하지 못하게(2026-08-05 감사). */
    private volatile LocalDate killSwitchDate;
    private final AtomicBoolean scalpingKillSwitchTriggered = new AtomicBoolean(false); // 스캘핑 킬스위치 (-1.5%)
    // VIX 기반 일시정지
    private final AtomicBoolean vixPaused = new AtomicBoolean(false);
    private volatile LocalDateTime lastVixAlertTime = null;
    // 수급 급증 데이터 캐시 (10분마다 갱신되므로 매초 DB 조회 불필요)
    private volatile Map<String, List<InvestorSurgeDto>> cachedSurgeStocks = null;
    private volatile LocalDateTime surgeStocksCacheTime = null;
    private static final long SURGE_CACHE_SECONDS = 30; // 30초 캐시
    // 스냅샷 자체의 신선도 임계 — 정상 갱신 주기는 10분(InvestorSurgeService cron). 15분 넘으면 stale.
    // stale 상태로 매수 진입 시 의도하지 않은 시점의 시그널로 거래되어 손실 위험.
    private static final long SURGE_DATA_MAX_AGE_MINUTES = 15;
    private volatile LocalDateTime lastStaleSurgeAlertTime = null;
    private static final long STALE_DATA_ALERT_COOLDOWN_MINUTES = 30;
    // 매도 평가 가격 신선도 임계 — KIS 설정 시 stockPriceService 캐시 윈도우(1분)와 동일.
    // 이보다 오래된 가격은 익절/트레일링 임계값 통과 결정에 신뢰하지 않는다.
    private static final long PRICE_STALE_SECONDS = 60;
    // 연속 손절 카운터 (3회 연속 시 당일 정지)
    private final AtomicInteger consecutiveStopLossCount = new AtomicInteger(0);
    private final AtomicBoolean consecutiveStopLossPaused = new AtomicBoolean(false);
    // KOSPI 하락 기반 매수 일시정지
    private final AtomicBoolean kospiDropPaused = new AtomicBoolean(false);
    private volatile LocalDateTime lastKospiCheckTime = null;
    // 섹터 OUTFLOW 캐시 (5분마다 갱신) — AtomicReference로 Set 재할당 race 차단
    private final AtomicReference<Set<String>> outflowSectorStocks =
            new AtomicReference<>(ConcurrentHashMap.newKeySet());
    private volatile LocalDateTime outflowCacheTime = null;
    private static final long OUTFLOW_CACHE_SECONDS = 300; // 5분 캐시

    /**
     * 스캘핑 포지션 정보
     */
    private static class ScalpingPosition {
        // 멀티스레드 visibility 보장 — 매도 평가/persist/halfSold 갱신이
        // 다른 스레드에서 일어날 때 stale value 로 판단하지 않도록 volatile 표시.
        // (compound read-modify-write atomicity 는 scalpingSellRunning 가드로 보장)
        final String stockCode;
        final String stockName;
        volatile BigDecimal buyPrice;           // 매수가
        volatile LocalDateTime buyTime;         // 매수 시간
        volatile BigDecimal highPrice;          // 최고가 (트레일링용)
        volatile boolean halfSold;              // 절반 익절 완료 여부
        volatile boolean timeExtended;          // 타임컷 동적 연장 사용 여부
        volatile int originalQuantity;          // 원래 수량

        ScalpingPosition(String stockCode, String stockName, BigDecimal buyPrice, int quantity) {
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.buyPrice = buyPrice;
            // static nested class — outer instance clock 미접근. KST 명시.
            this.buyTime = LocalDateTime.now(DateTimeUtil.KST);
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
        final String stockCode;
        final String stockName;
        volatile BigDecimal buyPrice;
        volatile LocalDateTime buyTime;
        volatile BigDecimal highPrice;
        final String buyReason; // "외국인3일연속" 등
        // ATR 세트(V42) 진입 스냅샷 — 셋 다 null 이면 현행 고정 -3/+5 청산(사후 변동 반영 금지).
        volatile BigDecimal entryAtr;
        volatile BigDecimal atrStopPct;    // 음수 %
        volatile BigDecimal atrTargetPct;  // 양수 %
        /**
         * 봇이 진입한 수량 — 매도 시 <b>계좌 전량이 아니라 이 수량만</b> 판다(2026-08-05 감사).
         * 0/null 이면 미상(구버전 복원분) → 종전대로 계좌 수량 사용.
         */
        volatile Integer botQuantity;

        SwingPosition(String stockCode, String stockName, BigDecimal buyPrice, String reason) {
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.buyPrice = buyPrice;
            // static nested class — outer instance clock 미접근. KST 명시.
            this.buyTime = LocalDateTime.now(DateTimeUtil.KST);
            this.highPrice = buyPrice;
            this.buyReason = reason;
        }

        void updateHighPrice(BigDecimal currentPrice) {
            if (currentPrice.compareTo(highPrice) > 0) {
                highPrice = currentPrice;
            }
        }

        long holdDays() {
            // static nested class — outer instance clock 미접근. KST 명시.
            // 달력일 차이로 계산 — Duration.toDays()(24h 블록)는 15:15 종가매수가 익일 09:05에 0일이라
            // "익일 갭하락/조기청산(holdDays >= 1)" 게이트가 구조적으로 불발되던 원인.
            return java.time.temporal.ChronoUnit.DAYS.between(
                    buyTime.toLocalDate(), LocalDate.now(DateTimeUtil.KST));
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
            InvestorTradeService investorTradeService,
            GlobalMarketService globalMarketService,
            BotTradingPositionRepository positionRepository,
            StockPriceHistoryRepository stockPriceHistoryRepository,
            org.springframework.beans.factory.ObjectProvider<RealtimePriceBus> realtimePriceBusProvider,
            Clock clock,
            BotLeaderElectionService botLeader,
            // 일일 손실 서킷브레이커(V38) — ObjectProvider: 미가용 시 게이트 통과(fail-open, 기존 동작 보존)
            org.springframework.beans.factory.ObjectProvider<DailyLossBreakerService> dailyLossBreakerProvider,
            // ATR 세트(V42) 감사 스냅샷 — 미가용=로그만
            org.springframework.beans.factory.ObjectProvider<TradingAuditService> auditProvider,
            // 변동성 국면 게이트(V46) — 미가용 시 게이트 통과(fail-open, 기존 동작 보존)
            org.springframework.beans.factory.ObjectProvider<VolatilityRegimeService> volRegimeProvider) {
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
        this.globalMarketService = globalMarketService;
        this.positionRepository = positionRepository;
        this.stockPriceHistoryRepository = stockPriceHistoryRepository;
        this.realtimePriceBusProvider = realtimePriceBusProvider;
        this.clock = clock;
        this.botLeader = botLeader;
        this.dailyLossBreakerProvider = dailyLossBreakerProvider;
        this.auditProvider = auditProvider;
        this.volRegimeProvider = volRegimeProvider;
        this.activeTradeService = virtualTradeService;
    }

    // ==================== ATR 세트 (V42 · flag 가역 · VIRTUAL 전용) ====================

    /**
     * ATR 세트 활성 판정 — <b>REAL 하드 가드</b>: flag 무관 REAL 모드는 무조건 현행(수량·청산).
     * 진입 여부 판단엔 관여하지 않음 — 모든 안전 게이트 통과 후 수량/청산폭 결정에서만 참조.
     */
    boolean isAtrSetActive(TradingMode mode) {
        return atrTradingEnabled && mode == TradingMode.VIRTUAL;
    }

    /** 테스트 전용 — @Value flag 를 결정론적으로 제어. */
    void setAtrTradingEnabledForTest(boolean enabled) {
        this.atrTradingEnabled = enabled;
    }

    /**
     * 종목당 리스크 예산(원) — bot_config 'atr_trading' 행 오버라이드 → 없으면 일일 손실 브레이커
     * 한도 ÷ 6 (기본 30만÷6=5만: 스윙 2 + 스캘핑 3 슬롯 + 재진입 여유 = 최악 동시 6포지션 전 손절이어도
     * 브레이커 한도 내). 조회 실패 시 보수 기본값 5만.
     */
    BigDecimal resolveAtrRiskBudget() {
        try {
            BigDecimal configured = botConfigRepository.findByConfigKey(ATR_CONFIG_KEY)
                    .map(BotConfig::getAtrRiskBudgetKrw).orElse(null);
            if (configured != null && configured.signum() > 0) return configured;
            BigDecimal limit = botConfigRepository.findByConfigKey(DailyLossBreakerService.BREAKER_CONFIG_KEY)
                    .map(BotConfig::getDailyLossLimitKrw).orElse(null);
            if (limit == null || limit.signum() <= 0) limit = DailyLossBreakerService.DEFAULT_LIMIT_KRW;
            return limit.divide(BigDecimal.valueOf(6), 0, RoundingMode.DOWN);
        } catch (Exception e) {
            log.debug("[ATR세트] riskBudget 조회 실패 — 기본값 사용: {}", e.getMessage());
            return DailyLossBreakerService.DEFAULT_LIMIT_KRW.divide(BigDecimal.valueOf(6), 0, RoundingMode.DOWN);
        }
    }

    /** 스윙 청산 레벨. */
    record SwingExitLevels(BigDecimal stop, BigDecimal target) {}

    /**
     * 스윙 청산 레벨 결정 — <b>순수 함수</b>(테스트 대상).
     * ATR 스냅샷(진입 시점 고정)이 있고 <b>非REAL</b> 일 때만 동적 레벨, 그 외 현행 고정 -3/+5.
     * REAL 은 이중 하드 가드 — 진입에서 스냅샷이 생기지 않지만(isAtrSetActive) 방어적으로 한 번 더 차단.
     */
    static SwingExitLevels resolveSwingExitLevels(TradingMode mode,
                                                  BigDecimal atrStopPct, BigDecimal atrTargetPct) {
        if (mode != TradingMode.REAL && atrStopPct != null && atrTargetPct != null) {
            return new SwingExitLevels(atrStopPct, atrTargetPct);
        }
        return new SwingExitLevels(SWING_STOP_LOSS, SWING_TAKE_PROFIT);
    }

    /** 진입 시점 ATR14 — StockPriceHistory(J 소스, sanity 앵커와 동일). 실패/부족 = null(§4c → 현행 폴백). */
    private BigDecimal computeEntryAtrQuiet(String stockCode) {
        try {
            return AtrCalculator.atr14(stockPriceHistoryRepository.findByStockCodeOrderByTradeDateDesc(
                    stockCode, org.springframework.data.domain.PageRequest.of(0, 40)));
        } catch (Exception e) {
            log.debug("[ATR세트] ATR 산출 실패({}) — 현행 폴백: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /** ATR 세트 적용값 감사 스냅샷 — 주간 리포트 사후검증용(TradingAuditLog, triggeredBy=ATR_SIZING). best-effort. */
    private void auditAtrApplied(String strategyTag, String stockCode, String stockName, int quantity,
                                 BigDecimal price, BigDecimal atr, BigDecimal stopPct, BigDecimal targetPct,
                                 BigDecimal riskBudget) {
        String detail = String.format("strategy=%s atr=%s stopPct=%s targetPct=%s riskBudgetKrw=%s",
                strategyTag, atr == null ? "-" : atr.toPlainString(),
                stopPct == null ? "-" : stopPct.toPlainString(),
                targetPct == null ? "-" : targetPct.toPlainString(),
                riskBudget == null ? "-" : riskBudget.toPlainString());
        log.info("[ATR세트] 적용 스냅샷 — {}({}) {}주 @ {}원 | {}", stockName, stockCode, quantity,
                formatNumber(price), detail);
        try {
            TradingAuditService audit = auditProvider.getIfAvailable();
            if (audit != null) {
                audit.event(com.myplatform.backend.entity.TradingAuditLog.Action.BUY,
                        com.myplatform.backend.entity.TradingAuditLog.Mode.VIRTUAL,
                        stockCode, stockName, quantity, price, "ATR_SIZING", detail);
            }
        } catch (Exception e) {
            // 매수 이벤트당 1회뿐이라 스팸 없음. 이게 조용히 죽으면 P2-17(ATR 세트 판정) 표본이 사라진다.
            log.warn("[ATR세트] 감사 스냅샷 기록 실패 — P2-17 판정 표본 소실: {}", e.getMessage());
        }
    }

    /** 브레이커 오늘 발동 여부 — getBotStatus 표시용(trippedDate 경량 읽기, 합산 쿼리 없음). */
    private boolean isDailyLossBreakerTripped() {
        try {
            DailyLossBreakerService breaker = dailyLossBreakerProvider.getIfAvailable();
            return breaker != null && breaker.isTrippedToday();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 일일 손실 서킷브레이커 진입 게이트 (V38) — <b>신규 진입 경로에서만</b> 호출(비대칭:
     * 매도/포지션 모니터/강제청산은 호출 금지). 브레이커 미가용/오류 = fail-open(진입 허용).
     */
    private boolean entryBlockedByDailyLossBreaker(boolean realMode, String botLabel) {
        try {
            DailyLossBreakerService breaker = dailyLossBreakerProvider.getIfAvailable();
            if (breaker == null) return false;
            if (!breaker.allowEntry(realMode)) {
                log.info("[{}] SKIP: 일일 손실 서킷브레이커 발동 중 — 신규 진입 차단(기존 포지션 매도/청산은 계속)", botLabel);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("[{}] 손실브레이커 게이트 오류 (fail-open, 진입 허용): {}", botLabel, e.getMessage());
            return false;
        }
    }

    /**
     * 변동성 국면 진입 게이트 (V46) — 브레이커 <b>형제 레이어</b>(신규 진입에서만, 매도/청산 미관여).
     * 게이트 미가용/mode OFF/판정 오류 = fail-open(PROCEED, 기존 동작 보존). BLOCK=진입 취소, REDUCE=사이즈 축소.
     */
    private VolatilityRegimeService.Decision volRegimeEntryDecision(String botLabel) {
        try {
            VolatilityRegimeService svc = volRegimeProvider.getIfAvailable();
            if (svc == null) return VolatilityRegimeService.Decision.PROCEED;   // 미가용 → 통과(fail-open)
            return svc.evaluateEntry(botLabel);
        } catch (Exception e) {
            log.error("[{}] 변동성 게이트 오류 (fail-open, 진입 허용): {}", botLabel, e.getMessage());
            return VolatilityRegimeService.Decision.PROCEED;
        }
    }

    /** REDUCED 모드 사이즈 배수 — 게이트 미가용 시 폴백 0.5(호출부는 REDUCE 결정일 때만 사용). */
    private double volRegimeReducedFactor() {
        VolatilityRegimeService svc = volRegimeProvider.getIfAvailable();
        return svc != null ? svc.reducedFactor() : 0.5;
    }

    // ==================== 봇 상태 관리 ====================

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void restoreBotStateOnStartup() {
        try {
            Thread.sleep(5000);

            // 모드 먼저 복원 — 그래야 restorePositionsFromDb 가 현재 모드 포지션만 복원 가능.
            // (이전엔 default VIRTUAL 인 상태에서 복원해 다른 모드 포지션이 잘못 활성화될 수 있었음)
            BotState savedState = loadBotState();
            if (savedState != null && savedState.mode != null) {
                try {
                    currentMode = TradingMode.valueOf(savedState.mode);
                    activeTradeService = (currentMode == TradingMode.REAL) ? realTradeService : virtualTradeService;
                } catch (IllegalArgumentException ignore) { /* unknown mode → default VIRTUAL 유지 */ }
            }

            // 포지션 메타데이터 복구 (재시작 시 halfSold/highPrice/buyTime 유지) — 현재 모드만.
            restorePositionsFromDb();

            // 재시작 시 KIS 실잔고와 봇 추적 포지션 대조 — 불일치는 경고만(자동 매매 안 함).
            reconcilePositionsWithKis();

            if (savedState != null && STATUS_RUNNING.equals(savedState.status)) {
                log.info("[스캘핑봇] 서버 재시작 감지 - 이전 상태 복구 중... (모드: {})", savedState.mode);

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

    // ==================== 포지션 영속화 (재시작 복구용) ====================

    /**
     * DB에서 포지션 메타데이터를 읽어 in-memory Map 복구.
     * - halfSold, buyTime, highPrice 등 전략 상태를 재시작 후에도 유지
     * - 같은 종목을 두 번 "절반 익절"하거나, 타임컷 시계 리셋되는 버그 방지
     */
    private void restorePositionsFromDb() {
        try {
            int scalping = 0, swing = 0, closing = 0;
            // 현재 모드 포지션만 in-memory 로 복원.
            // 다른 모드 포지션은 DB 에 그대로 두고 무시 — 잘못된 자동 매도 방지.
            String mode = currentMode.name();
            List<BotTradingPosition> positions = positionRepository.findByTradingMode(mode);
            int skipped = (int) positionRepository.count() - positions.size();
            if (skipped > 0) {
                log.info("[봇 복구] 현재 모드({}) 외 포지션 {}건은 복원 스킵", mode, skipped);
            }
            for (BotTradingPosition p : positions) {
                switch (p.getStrategy()) {
                    case SCALPING -> {
                        ScalpingPosition sp = new ScalpingPosition(
                                p.getStockCode(), p.getStockName(), p.getBuyPrice(),
                                p.getOriginalQuantity() != null ? p.getOriginalQuantity() : 0);
                        sp.buyTime = p.getBuyTime();
                        sp.highPrice = p.getHighPrice();
                        sp.halfSold = Boolean.TRUE.equals(p.getHalfSold());
                        sp.timeExtended = Boolean.TRUE.equals(p.getTimeExtended());
                        scalpingPositions.put(p.getStockCode(), sp);
                        scalping++;
                    }
                    case SWING -> {
                        SwingPosition sw = new SwingPosition(p.getStockCode(), p.getStockName(),
                                p.getBuyPrice(), p.getBuyReason());
                        sw.buyTime = p.getBuyTime();
                        sw.highPrice = p.getHighPrice();
                        // ATR 세트(V42) 진입 스냅샷 복원 — 재시작 후에도 같은 레벨로 청산(재계산 금지)
                        sw.entryAtr = p.getEntryAtr();
                        sw.atrStopPct = p.getAtrStopPct();
                        sw.atrTargetPct = p.getAtrTargetPct();
                        sw.botQuantity = p.getOriginalQuantity();   // 없으면 null → 종전(계좌 수량)
                        swingPositions.put(p.getStockCode(), sw);
                        swing++;
                    }
                    case CLOSING -> {
                        SwingPosition cp = new SwingPosition(p.getStockCode(), p.getStockName(),
                                p.getBuyPrice(), p.getBuyReason());
                        cp.buyTime = p.getBuyTime();
                        cp.highPrice = p.getHighPrice();
                        cp.botQuantity = p.getOriginalQuantity();
                        closingPositions.put(p.getStockCode(), cp);
                        closing++;
                    }
                }
            }
            if (scalping + swing + closing > 0) {
                log.info("[봇 복구] 포지션 메타 복원 완료 — 스캘핑 {} / 스윙 {} / 종가 {}",
                        scalping, swing, closing);
                // 복원된 보유 종목 WebSocket 자동 구독 (활성 시) — 매도 평가 가격 즉시 신선화.
                RealtimePriceBus bus = getBus();
                if (bus != null) {
                    Set<String> codes = new java.util.HashSet<>();
                    codes.addAll(scalpingPositions.keySet());
                    codes.addAll(swingPositions.keySet());
                    codes.addAll(closingPositions.keySet());
                    for (String code : codes) {
                        try { bus.subscribe(code); } catch (Exception ignore) {}
                    }
                }
            }
        } catch (Exception e) {
            log.error("[봇 복구] 포지션 메타 복원 실패 (계속 진행): {}", e.getMessage(), e);
        }
    }

    /** 포지션-KIS 정합성 점검 결과. orphaned=봇 추적O/KIS X, untracked=KIS O/봇 추적X. */
    record ReconciliationResult(java.util.Set<String> orphaned, java.util.Set<String> untracked) {
        boolean isClean() { return orphaned.isEmpty() && untracked.isEmpty(); }
    }

    /**
     * KIS 실보유 종목과 봇이 추적하는 포지션의 차집합 계산 (순수 함수 — 테스트 대상).
     * @param kisCodes   KIS 실계좌 보유 종목코드
     * @param trackedCodes 봇 메모리 포지션(스캘핑+스윙+종가) 종목코드
     */
    static ReconciliationResult computeReconciliation(java.util.Set<String> kisCodes,
                                                      java.util.Set<String> trackedCodes) {
        java.util.Set<String> orphaned = new java.util.TreeSet<>(trackedCodes);
        orphaned.removeAll(kisCodes);                 // 봇은 추적하나 KIS 잔고에 없음
        java.util.Set<String> untracked = new java.util.TreeSet<>(kisCodes);
        untracked.removeAll(trackedCodes);            // KIS 잔고엔 있으나 봇이 추적 안 함
        return new ReconciliationResult(orphaned, untracked);
    }

    /**
     * 재시작 시 KIS 실잔고 ↔ 봇 추적 포지션 대조 (REAL 모드 한정, best-effort).
     * <p>불일치를 발견해도 <b>자동 매매/정정은 하지 않는다</b> — 로그 + 텔레그램 경고로 운영자 수동 확인 유도.
     * KIS 잔고는 봇 외 수동 매매와 공유될 수 있어 untracked 가 곧 버그는 아님(판단은 운영자 몫).
     */
    private void reconcilePositionsWithKis() {
        if (currentMode != TradingMode.REAL) return;  // 실계좌 대조는 실전 모드만 의미 있음
        try {
            java.util.Set<String> kisCodes = new java.util.TreeSet<>();
            for (PortfolioItemDto p : realTradeService.getPortfolio()) {
                if (p.getStockCode() != null) kisCodes.add(p.getStockCode());
            }
            java.util.Set<String> tracked = new java.util.TreeSet<>();
            tracked.addAll(scalpingPositions.keySet());
            tracked.addAll(swingPositions.keySet());
            tracked.addAll(closingPositions.keySet());

            ReconciliationResult r = computeReconciliation(kisCodes, tracked);
            if (r.isClean()) {
                log.info("[봇 복구] 포지션-KIS 정합성 OK (봇 추적 {}종목 / KIS {}종목)", tracked.size(), kisCodes.size());
                return;
            }
            log.warn("[봇 복구] ⚠ 포지션-KIS 불일치 — orphaned(봇O/KIS X)={}, untracked(KIS O/봇X)={}",
                    r.orphaned(), r.untracked());
            if (telegramService.isEnabled()) {
                StringBuilder sb = new StringBuilder("<b>⚠️ [봇 복구] 포지션 정합성 불일치</b>\n\n");
                if (!r.orphaned().isEmpty()) {
                    sb.append("🔸 <b>봇은 보유로 추적하나 KIS 잔고에 없음</b>\n")
                      .append("(이미 매도됐거나 부분체결 잔재 — 봇이 매도 시도 반복 실패 가능)\n")
                      .append(String.join(", ", r.orphaned())).append("\n\n");
                }
                if (!r.untracked().isEmpty()) {
                    sb.append("🔹 <b>KIS 잔고엔 있으나 봇이 추적 안 함</b>\n")
                      .append("(봇 매수의 DB 유실분이면 자동 매도 안 됨 — 수동 확인. 단순 수동 보유면 무시)\n")
                      .append(String.join(", ", r.untracked())).append("\n\n");
                }
                sb.append("⚠️ 자동 보정은 하지 않습니다 — KIS 화면 확인 후 수동 정리하세요.");
                try { telegramService.sendRisk(sb.toString()); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            // KIS 잔고 조회 실패 등 — 정합성 점검은 best-effort. 복구 흐름은 막지 않는다.
            log.error("[봇 복구] 포지션-KIS 정합성 점검 실패 (계속 진행): {}", e.getMessage(), e);
        }
    }

    /**
     * 매도가 부분/미체결로 <b>확정</b>됐는지 — 실전 모드에서 KIS 체결조회(confirmFill)로 확인 (B2-A).
     * <p>REAL 모드 + ODNO 있을 때만 실제 확인. 전량체결(FULL)·조회실패(UNKNOWN)는 false →
     * 호출측이 현행대로 포지션을 제거(조회 실패해도 최악이 현행과 동일). 확정 미달(부분/미체결)이면 true
     * → 호출측이 포지션을 유지해 다음 사이클에 재시도(잔량 KIS orphan 방지).
     */
    private boolean isSellConfirmedShort(String stockCode, TradeHistoryDto sellDto, int requestedQty) {
        if (currentMode != TradingMode.REAL) return false;          // 모의는 전량체결 가정
        if (sellDto == null || sellDto.getOrderNo() == null) return false;
        RealTradeService.FillResult f = realTradeService.confirmFill(stockCode, sellDto.getOrderNo(), requestedQty);
        if (f.isConfirmedShort()) {
            // 부분/미체결 확정 → DB 매도 기록을 실체결 수량으로 정정(요청수량 기록의 이중집계=실현손익 과대 방지). 집계/기록만.
            try { realTradeService.reconcileSellFill(sellDto.getId(), f.filledQty()); }
            catch (Exception e) { log.warn("[봇] 매도 기록 정정 실패(무시): {}", e.getMessage()); }
            log.warn("[봇] 매도 체결 미달 확인 — {} 체결 {}/{}주 → 포지션 유지", stockCode, f.filledQty(), requestedQty);
            return true;
        }
        return false;
    }

    /** 영속화 재시도 — DB 일시 장애 (커넥션 끊김 등) 대비. 3회 시도 후에도 실패면 호출자가 알림. */
    private <T> void retryPersist(java.util.function.Supplier<T> action) {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try { action.get(); return; }
            catch (Exception e) {
                last = e;
                try { Thread.sleep(200L * (i + 1)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (last instanceof RuntimeException re) throw re;
        throw new RuntimeException(last);
    }

    // 저장 실패 시 호출자가 알림/보상 처리를 할 수 있도록 예외를 던진다.
    // tradingMode 는 현재 봇 모드(REAL/VIRTUAL)로 기록 — 같은 종목 양쪽 모드 동시 보유 가능.
    private void persistScalpingPosition(ScalpingPosition sp) {
        retryPersist(() -> {
            String mode = currentMode.name();
            BotTradingPosition entity = positionRepository
                    .findByStrategyAndStockCodeAndTradingMode(Strategy.SCALPING, sp.stockCode, mode)
                    .orElseGet(() -> BotTradingPosition.builder()
                            .strategy(Strategy.SCALPING)
                            .stockCode(sp.stockCode)
                            .tradingMode(mode)
                            .build());
            entity.setStockName(sp.stockName);
            entity.setBuyPrice(sp.buyPrice);
            entity.setHighPrice(sp.highPrice);
            entity.setBuyTime(sp.buyTime);
            entity.setHalfSold(sp.halfSold);
            entity.setTimeExtended(sp.timeExtended);
            entity.setOriginalQuantity(sp.originalQuantity);
            entity.setTradingMode(mode);
            return positionRepository.save(entity);
        });
    }

    private void persistSwingPosition(SwingPosition sw, Strategy strategy) {
        retryPersist(() -> {
            String mode = currentMode.name();
            BotTradingPosition entity = positionRepository
                    .findByStrategyAndStockCodeAndTradingMode(strategy, sw.stockCode, mode)
                    .orElseGet(() -> BotTradingPosition.builder()
                            .strategy(strategy)
                            .stockCode(sw.stockCode)
                            .tradingMode(mode)
                            .build());
            entity.setStockName(sw.stockName);
            entity.setBuyPrice(sw.buyPrice);
            entity.setHighPrice(sw.highPrice);
            entity.setBuyTime(sw.buyTime);
            entity.setBuyReason(sw.buyReason);
            entity.setTradingMode(mode);
            // 봇 보유수량(2026-08-05 감사) — 재시작 후에도 계좌 전량이 아닌 봇 수량만 매도하도록.
            // 스캘핑과 같은 original_quantity 컬럼 재사용(SWING 은 그동안 NULL 이었다).
            if (sw.botQuantity != null && sw.botQuantity > 0) entity.setOriginalQuantity(sw.botQuantity);
            // ATR 세트(V42) 진입 스냅샷 — 재시작 복원용(없으면 null 유지 = 현행 청산)
            entity.setEntryAtr(sw.entryAtr);
            entity.setAtrStopPct(sw.atrStopPct);
            entity.setAtrTargetPct(sw.atrTargetPct);
            return positionRepository.save(entity);
        });
    }

    // 저장 실패 시 관리자에게 즉시 알림 — 재시작 시 메모리 포지션이 복원되지 않아
    // 자동 매도 로직이 해당 종목을 놓칠 수 있으므로 수동 대응이 필요함을 알린다.
    private void alertPersistFailure(String strategyTag, String stockCode, String stockName, Exception e) {
        log.error("[봇 영속화] {} 포지션 DB 저장 실패 {} ({}): {}", strategyTag, stockCode, stockName, e.getMessage(), e);
        try {
            if (telegramService.isEnabled()) {
                telegramService.sendRisk(String.format(
                        "<b>⚠️ [%s] 포지션 DB 저장 실패</b>\n\n🎯 %s (%s)\n\n현재 세션에서는 추적되지만 <b>재시작 시 포지션이 사라져 자동 매도가 중단</b>됩니다.\n수동 매도 또는 DB 점검이 필요합니다.\n\n오류: %s",
                        strategyTag, stockName == null ? "-" : stockName, stockCode,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        } catch (Exception alertEx) {
            log.warn("[봇 영속화] 텔레그램 실패 알림 전송 실패: {}", alertEx.getMessage());
        }
    }

    // 트랜잭션은 리포지토리 @Transactional 이 연다 — 여기의 self-invocation 은 프록시를 안 타서
    // 메서드 레벨 @Transactional 이 무효였음(삭제가 TransactionRequiredException 으로 전부 무산되던 원인).
    protected void deletePersistedPosition(Strategy strategy, String stockCode) {
        try {
            // 모드 한정 삭제(2026-08-05 감사) — 저장은 (strategy, stockCode, tradingMode) 3키인데
            // 삭제만 2키라 VIRTUAL 매도 1건이 같은 종목의 REAL 행까지 지웠다. 그러면 REAL 포지션이
            // 영구 untracked 가 되어 손절이 안 걸린다. 모드 한정 메서드는 이미 있었는데 호출부가 없었다.
            positionRepository.deleteByStrategyAndStockCodeAndTradingMode(
                    strategy, stockCode, currentMode.name());
        } catch (Exception e) {
            log.warn("[봇 영속화] {} 포지션 삭제 실패 {}: {}", strategy, stockCode, e.getMessage());
        }
    }

    /** 완청산 확인 후 현재 모드 포지션 행 전체 정리(고스트 포함) — 실패는 로그만(다음 재시작 reconcile 이 경고). */
    private void clearPersistedPositionsForCurrentMode(String pathLabel) {
        try {
            positionRepository.deleteByTradingMode(currentMode.name());
        } catch (Exception e) {
            log.warn("[봇] {} 후 포지션 DB 정리 실패: {}", pathLabel, e.getMessage());
        }
    }

    @Transactional
    protected void saveBotState(String status, TradingMode mode) {
        try {
            BotConfig config = botConfigRepository.findByConfigKey(BOT_CONFIG_KEY)
                    .orElse(BotConfig.builder().configKey(BOT_CONFIG_KEY).build());

            config.setIsActive(STATUS_RUNNING.equals(status));
            config.setTradingMode(mode.name());
            config.setLastStatusChange(LocalDateTime.now(clock));

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

    public synchronized BotStatusDto startBot(TradingMode mode) {
        if (botActive.get()) {
            log.info("[스캘핑봇] 이미 실행 중입니다. 현재 모드: {}", currentMode.getDisplayName());
            return getBotStatus();
        }

        TradingMode newMode = mode != null ? mode : TradingMode.VIRTUAL;

        // 모드 전환 시 in-memory 포지션만 정리한다. DB 행은 <b>모드별로 보존</b>.
        //
        // ⚠ 2026-08-05 감사: 예전 주석은 "BotTradingPosition 은 mode 컬럼이 없어 구분 불가"라며
        // positionRepository.deleteAll() 로 <b>전 모드 행을 지웠다</b>. 그 전제는 사실이 아니다 —
        // V23 에 trading_mode 컬럼이 있고 복원(findByTradingMode)·청산대상(botOwnedCodes) 모두
        // 이미 모드별로 동작한다. 전 모드 삭제는 REAL 실보유의 DB 행까지 지워 영구 untracked 로
        // 만들었고(손절 영영 미작동), 얻는 것은 없었다. in-memory 만 비우면 목적은 달성된다
        // ("이전 모드 포지션을 새 모드 계좌로 매도 시도" 방지).
        if (currentMode != newMode) {
            // 1) 진행 중인 trade 가 끝날 때까지 대기 (최대 10초).
            //    옛 sleep(2000) 은 in-flight 보장 못 함 — 카운터로 정확히 대기.
            int waited = 0;
            while (inFlightTrades.get() > 0 && waited < 100) {
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                waited++;
            }
            if (inFlightTrades.get() > 0) {
                log.warn("[봇] 모드 전환 — 10초 대기 후에도 in-flight {}건 남음, 강제 진행",
                        inFlightTrades.get());
            }

            int cleared = scalpingPositions.size() + swingPositions.size() + closingPositions.size();
            if (cleared > 0) {
                log.warn("[스캘핑봇] 모드 전환 감지 ({} → {}): 기존 포지션 {}개 정리",
                        currentMode.getDisplayName(), newMode.getDisplayName(), cleared);
                scalpingPositions.clear();
                swingPositions.clear();
                closingPositions.clear();
                // DB 행은 지우지 않는다 — 모드별로 보존해야 전환 후 복귀 시 복원되고,
                // REAL 실보유가 untracked 로 새지 않는다(위 주석 참조).
            }
        }

        currentMode = newMode;
        activeTradeService = (currentMode == TradingMode.REAL) ? realTradeService : virtualTradeService;

        botActive.set(true);
        // ⚠ 당일 킬스위치는 재개로 풀리지 않는다(2026-08-05 감사). 예전엔 무조건 해제 + 손실 후
        // 자산으로 dailyStartAsset 을 재설정해, 같은 날 -3% 사이클을 무한 반복할 수 있었다
        // (일일 손실 상한이 사실상 무제한). 다음 거래일에 자동 해제된다.
        boolean sameDayKill = !canClearKillSwitch(killSwitchTriggered.get(), killSwitchDate, LocalDate.now(clock));
        if (sameDayKill) {
            log.warn("[봇] 당일 킬스위치 발동 이력({}) — 재개하되 손실 한도는 유지합니다", killSwitchDate);
        } else {
            killSwitchTriggered.set(false);
            scalpingKillSwitchTriggered.set(false);
        }
        scalpingPositions.clear();
        resetDailyCounters();
        if (!sameDayKill) initializeDailyAsset();   // 손실 후 자산으로 기준을 낮추지 않는다
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
        } else if (scalpingKillSwitchTriggered.get()) {
            status = "SCALPING_KILL_SWITCH";
        } else if (isDailyLossBreakerTripped()) {
            status = "DAILY_LOSS_BREAKER";
        } else if (consecutiveStopLossPaused.get()) {
            status = "STOP_LOSS_PAUSED";
        } else if (!botActive.get()) {
            status = "STOPPED";
        } else if (vixPaused.get()) {
            status = "VIX_PAUSED";
        } else if (kospiDropPaused.get()) {
            status = "KOSPI_DROP_PAUSED";
        } else if (lastError != null && lastErrorTime != null &&
                   lastErrorTime.isAfter(LocalDateTime.now(clock).minusMinutes(30))) {
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

    // ==================== 나스닥 선물 기반 매수 보류 ====================

    /**
     * 나스닥 선물 -1% 이하 시 스캘핑 매수 보류
     * (장 시작 전 글로벌 리스크 사전 차단)
     */
    private boolean checkNasdaqHalt() {
        try {
            boolean shouldHalt = globalMarketService.shouldHaltBuying();
            if (shouldHalt) {
                log.info("[스캘핑봇] 🌐 나스닥 선물 급락 감지 — 매수 보류");
            }
            return shouldHalt;
        } catch (Exception e) {
            warnGuardFailureDaily("나스닥 급락", "fail-open(매수 허용)으로 진행", e);
            return false;
        }
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
                        lastVixAlertTime.isBefore(LocalDateTime.now(clock).minusHours(1))) {
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
            warnGuardFailureDaily("VIX 급등", "fail-open(매수 허용)으로 진행", e);
        }
        return false;
    }

    private void sendVixAlert(double vixPrice, boolean isFirstAlert) {
        lastVixAlertTime = LocalDateTime.now(clock);
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
     * - 네이버 모바일 API로 실시간 KOSPI 지수 조회 (장중 갱신)
     */
    private boolean checkKospiDrop() {
        try {
            // 1분마다만 체크 (API 부하 방지)
            if (lastKospiCheckTime != null &&
                    java.time.Duration.between(lastKospiCheckTime, LocalDateTime.now(clock)).getSeconds() < 60) {
                return kospiDropPaused.get();
            }
            lastKospiCheckTime = LocalDateTime.now(clock);

            // 네이버 모바일 API로 실시간 KOSPI 등락률 조회
            BigDecimal kospiChangeRate = fetchKospiChangeRate();
            if (kospiChangeRate == null) {
                return kospiDropPaused.get();
            }

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
            warnGuardFailureDaily("KOSPI 급락", "직전 상태 유지로 진행", e);
        }
        return kospiDropPaused.get();
    }

    /**
     * 네이버 모바일 API로 실시간 KOSPI 등락률 조회
     * (기존 stockPriceService.getStockPrice("0001")은 지수 코드라 조회 실패)
     */
    private BigDecimal fetchKospiChangeRate() {
        try {
            String url = "https://m.stock.naver.com/api/index/KOSPI/basic";
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
            headers.set("Referer", "https://m.stock.naver.com");
            headers.setAccept(List.of(org.springframework.http.MediaType.APPLICATION_JSON));

            // RestTemplate timeout 명시 — 무한 hang 방지. 봇 매매 thread 가 점유되면 매도 타이밍 놓침.
            org.springframework.http.client.SimpleClientHttpRequestFactory rtFactory =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            rtFactory.setConnectTimeout(3000);
            rtFactory.setReadTimeout(5000);
            org.springframework.http.ResponseEntity<String> response = new org.springframework.web.client.RestTemplate(rtFactory)
                    .exchange(url, org.springframework.http.HttpMethod.GET,
                            new org.springframework.http.HttpEntity<>(headers), String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(response.getBody());
                if (root.has("fluctuationsRatio")) {
                    String ratioStr = root.get("fluctuationsRatio").asText().replace(",", "");
                    BigDecimal rate = new BigDecimal(ratioStr);
                    log.debug("[스캘핑봇] KOSPI 실시간 등락률: {}%", rate);
                    return rate;
                }
            }
        } catch (Exception e) {
            warnGuardFailureDaily("KOSPI 실시간 등락률", "null 반환(가드가 판정 보류)", e);
        }
        return null;
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
                    java.time.Duration.between(outflowCacheTime, LocalDateTime.now(clock)).getSeconds() < OUTFLOW_CACHE_SECONDS) {
                return outflowSectorStocks.get().contains(stockCode);
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

            outflowSectorStocks.set(newOutflowStocks);
            outflowCacheTime = LocalDateTime.now(clock);

            if (!newOutflowStocks.isEmpty()) {
                log.debug("[스캘핑봇] OUTFLOW 섹터 종목 {}개 차단 중", newOutflowStocks.size());
            }

            return newOutflowStocks.contains(stockCode);
        } catch (Exception e) {
            warnGuardFailureDaily("섹터 OUTFLOW", "fail-open(차단 안 함)으로 진행", e);
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
            // ⚠ 이 가드는 예외 이전의 문제가 있다 — 소스(short_selling_balance)가 死라 테이블이
            //   비어 있고, 그 경우 예외 없이 false 가 나와 여기조차 안 온다(장식 가드 상태).
            //   복구/종결 판정은 SCHEDULE_DECISIONS "공매도 死피드" 안건.
            warnGuardFailureDaily("공매도 비율", "fail-open(차단 안 함)으로 진행", e);
            return false;
        }
    }

    // ==================== 매수 로직 (스캘핑) ====================

    /**
     * 스캘핑 매수 로직
     * - 실행 시간: 09:00~11:30 (장 전반부, 30초 간격, 평일만)
     * - 수급 급증 종목 중 스캘핑 조건 충족 시 진입
     *
     * 2026-04-24: 주기 5초 → 30초로 완화. KIS EGW00201 재시도 백오프와 충돌 방지.
     *   스캘핑 전략은 분 단위로 움직이므로 30초 간격으로 충분.
     */
    @Scheduled(cron = "*/30 * 9-11 * * MON-FRI", zone = "Asia/Seoul")
    public void executeScalpingBuyLogic() {
        // 리더 게이트(fail-CLOSED) — 멀티 인스턴스면 리더 1개만 주문. Redis 미가용 시 주문 안 함. killswitch 와 별개로 둘 다 통과해야 함.
        if (!botLeader.isLeaderForBot()) return;
        if (!scalpingBuyRunning.compareAndSet(false, true)) {
            log.warn("[스캘핑봇] 이전 실행 아직 진행 중 — 이번 틱 스킵 (스레드 누적 방지)");
            return;
        }
        try {
            executeScalpingBuyLogicInternal();
        } finally {
            scalpingBuyRunning.set(false);
        }
    }

    /**
     * SKIP 사유를 남긴다 — 직전과 같은 사유면 DEBUG, 바뀌었으면 INFO.
     *
     * <p>판정 로직이 아니라 <b>로그 레벨만</b> 정한다. 호출부의 흐름(return)은 그대로다.
     * 사유별 상세 값(킬스위치 플래그 등)은 메시지에 그대로 담기므로 정보 손실이 없다.
     */
    private void logScalpingSkip(String reason) {
        String key = LocalDate.now(clock) + "|" + reason;
        if (key.equals(lastScalpingSkipKey.getAndSet(key))) {
            log.debug("[스캘핑봇] SKIP: {}", reason);
        } else {
            log.info("[스캘핑봇] SKIP: {}", reason);
        }
    }

    private void executeScalpingBuyLogicInternal() {
        if (!botActive.get()) {
            logScalpingSkip("botActive=false");
            return;
        }
        if (isScalpingBlocked()) {
            logScalpingSkip(String.format("스캘핑 킬스위치 발동 중 (scalping=%s, total=%s)",
                    scalpingKillSwitchTriggered.get(), killSwitchTriggered.get()));
            return;
        }

        // ★ 실전투자 모드에서 스캘핑 비활성
        if (currentMode == TradingMode.REAL) {
            return;
        }

        // 연속 손절 3회 시 당일 정지
        if (consecutiveStopLossPaused.get()) {
            logScalpingSkip("연속 손절 3회 당일 정지");
            return;
        }

        // 09:45~10:30만 스캘핑 매수 허용 (골든타임 집중)
        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(MORNING_ENTRY_START) || now.isAfter(MORNING_ENTRY_END)) {
            return; // 시간 밖은 정상 동작이므로 로그 불필요
        }

        // ★ 일일 손실 서킷브레이커(V38) — 당일 실현손실 한도 도달 시 신규 진입 차단(매도는 계속) ★
        //   시간창 통과 후 평가 = 골든타임에만 합산 쿼리(틱당 1회). trigger-buy 수동 트리거도 이 경로.
        if (entryBlockedByDailyLossBreaker(currentMode == TradingMode.REAL, "스캘핑봇")) {
            return;
        }

        // ★ 변동성 국면 게이트(V46) — 브레이커 형제 레이어. BLOCK=진입 취소 / REDUCE=사이즈 축소 ★
        final VolatilityRegimeService.Decision volGate = volRegimeEntryDecision("스캘핑봇");
        if (volGate == VolatilityRegimeService.Decision.BLOCK) {
            return;
        }

        log.info("[스캘핑봇] ===== 골든타임 진입 ({}) =====", now);

        // ★ 하루 최대 스캘핑 매수 제한
        if (todayBuyCount.get() >= MAX_SCALPING_TRADES_PER_DAY) {
            logScalpingSkip("하루 매수 " + todayBuyCount.get() + "회 달성");
            return;
        }

        // ★ 나스닥 선물 -1% 이하 → 글로벌 리스크, 매수 보류
        if (checkNasdaqHalt()) {
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

        log.debug("[스캘핑봇] ===== 매수 로직 시작 ({}) =====", LocalTime.now(clock));
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

            // ★ 스냅샷 stale 가드 — 정상 갱신 주기(10분) 초과 시 매수 보류.
            //   collectIntradaySnapshot cron 실패 시 어제 마지막 데이터로 의도치 않은 진입 차단.
            if (!isSurgeDataFresh(surgeStocks)) {
                return;
            }

            // 외국인 + 기관 순매수 종목 합치기 (중복 제거)
            List<InvestorSurgeDto> targetStocks = mergeAndFilterSurgeStocks(surgeStocks);
            if (targetStocks.isEmpty()) {
                log.info("[스캘핑봇] 수급 급증 후보 0종목 — 외국인/기관 순매수 종목 없음");
                return;
            }

            log.info("[스캘핑봇] 수급 급증 후보: {}종목 (상위 10개만 체크)", targetStocks.size());
            // ★ 상위 10종목만 체크 (중소형 상위 5개만으론 거래대금/체결강도 통과 어려움)
            if (targetStocks.size() > 10) {
                targetStocks = targetStocks.subList(0, 10);
            }

            // 계좌 정보 조회
            AccountSummaryDto accountSummary = activeTradeService.getAccountSummary();
            BigDecimal currentBalance = accountSummary.getCurrentBalance() != null
                    ? accountSummary.getCurrentBalance() : BigDecimal.ZERO;
            BigDecimal totalAsset = currentBalance.add(
                    accountSummary.getTotalEvaluation() != null ? accountSummary.getTotalEvaluation() : BigDecimal.ZERO);
            BigDecimal maxPerStock = totalAsset.multiply(MAX_INVESTMENT_RATIO);
            // 가용현금 초과 방지
            if (maxPerStock.compareTo(currentBalance) > 0) {
                maxPerStock = currentBalance;
            }
            // 변동성 REDUCED 모드 — 진입 캡 축소(investAmount=min(cash,maxPerStock)라 캡 축소가 그대로 반영)
            if (volGate == VolatilityRegimeService.Decision.REDUCE) {
                maxPerStock = maxPerStock.multiply(BigDecimal.valueOf(volRegimeReducedFactor()));
            }

            // 이미 보유 중인 종목 코드
            List<String> holdingCodes = currentPortfolio.stream()
                    .map(PortfolioItemDto::getStockCode)
                    .collect(Collectors.toList());

            for (InvestorSurgeDto surge : targetStocks) {
                // 잔액 확인
                if (currentBalance.compareTo(MIN_BALANCE) < 0) {
                    log.info("[스캘핑봇] 잔액 부족");
                    break;
                }

                // 이미 보유 중이거나 매수 진행 중인 종목 스킵
                if (holdingCodes.contains(surge.getStockCode()) || scalpingPositions.containsKey(surge.getStockCode())) {
                    continue;
                }

                // 쿨다운: 최근 30분 이내 매도한 종목 재매수 금지
                LocalDateTime lastSell = sellCooldownMap.get(surge.getStockCode());
                if (lastSell != null && java.time.Duration.between(lastSell, LocalDateTime.now(clock)).toMinutes() < SELL_COOLDOWN_MINUTES) {
                    log.debug("[스캘핑봇] 쿨다운: {} - 매도 후 {}분 경과 (기준: {}분)",
                            surge.getStockName(), java.time.Duration.between(lastSell, LocalDateTime.now(clock)).toMinutes(), SELL_COOLDOWN_MINUTES);
                    continue;
                }

                // ★ 섹터 OUTFLOW 종목 진입 차단 ★
                if (isOutflowSectorStock(surge.getStockCode())) {
                    log.info("[스캘핑봇] Skip [{}({})] 섹터 OUTFLOW — 자금 유출 섹터 진입 차단",
                            surge.getStockName(), surge.getStockCode());
                    continue;
                }

                // ★ 거래정지/상폐 종목 진입 차단 ★
                if (!stockStatusService.isActive(surge.getStockCode())) {
                    log.info("[스캘핑봇] Skip [{}({})] 거래정지/상폐 종목",
                            surge.getStockName(), surge.getStockCode());
                    continue;
                }

                // ★ 고공매도 종목 진입 차단 ★
                if (isHighShortSellingStock(surge.getStockCode())) {
                    log.info("[스캘핑봇] Skip [{}({})] 공매도 비율 5% 이상 — 고공매도 종목 진입 차단",
                            surge.getStockName(), surge.getStockCode());
                    continue;
                }

                // ★ 스캘핑 진입 조건 체크 ★
                ScalpingEntryResult entryResult = checkScalpingEntry(surge);
                if (!entryResult.canEnter) {
                    continue;
                }

                // ★ 가격 sanity 가드 — 전일 종가(J 소스) 대비 ±50% 초과(×10 오염 의심)면 진입 차단 ★
                if (!passesPriceSanity(surge.getStockCode(), surge.getStockName(),
                        entryResult.currentPrice, "스캘핑")) {
                    continue;
                }

                // 매수 수량 계산 — 현행: min(잔액, 종목상한) 전액 매수.
                // ATR 세트(flag ON + VIRTUAL, V42): 리스크 균등 사이징(수량 축소 전용 — 항상 현행 이하).
                // 스캘핑 손절폭 입력 = 현행 고정 -1.2%(청산 로직 무변경 — 일봉 ATR 척도는 분 단위 전략과 불일치).
                BigDecimal investAmount = currentBalance.compareTo(maxPerStock) < 0 ? currentBalance : maxPerStock;
                int quantity;
                BigDecimal appliedRiskBudget = null;
                if (isAtrSetActive(currentMode)) {
                    appliedRiskBudget = resolveAtrRiskBudget();
                    quantity = PositionSizer.judge(totalAsset, entryResult.currentPrice,
                            STOP_LOSS_RATE.abs(), appliedRiskBudget, MAX_INVESTMENT_RATIO, investAmount);
                } else {
                    quantity = investAmount.divide(entryResult.currentPrice, 0, RoundingMode.DOWN).intValue();
                }

                if (quantity <= 0) {
                    continue;
                }

                // 매수 실행 — 모드 전환 race 방어: 중간에 mode 가 바뀌었으면 중단
                if (!botActive.get() || currentMode != TradingMode.VIRTUAL) {
                    log.warn("[스캘핑봇] 매수 직전 봇 비활성화 또는 모드 전환 감지 — 중단");
                    return;
                }

                // ★ 진입 직전 현재가 재확인 — 신호 평가 후 ~수백 ms 동안 가격 변동 방어.
                //   surge cache (30s) 데이터가 stale 한 상태에서 매수 발사 시 잘못된 수량 위험.
                //   KIS 호출 1회 추가 비용 발생 — 변동률 ±2% 이상이면 skip.
                BigDecimal latestPrice = fetchLatestPriceQuiet(surge.getStockCode());
                if (latestPrice != null && latestPrice.signum() > 0) {
                    BigDecimal drift = latestPrice.subtract(entryResult.currentPrice).abs()
                            .divide(entryResult.currentPrice, 4, RoundingMode.HALF_UP);
                    if (drift.compareTo(new BigDecimal("0.02")) > 0) {
                        log.info("[스캘핑봇] 진입 직전 가격 {}% 변동 감지 — skip {} ({}원 → {}원)",
                                drift.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                                surge.getStockName(),
                                formatNumber(entryResult.currentPrice), formatNumber(latestPrice));
                        continue;
                    }
                }

                // ★ 메모리 포지션 선등록 (KIS 호출 전) — 동시 진입 race 방지 ★
                // buy() 가 KIS API 라 응답까지 ~수백 ms. 그 사이 다른 사이클이 같은 종목
                // 진입 평가 시 putIfAbsent 가 prev != null 반환 → 즉시 skip 되어
                // 같은 종목 2번 주문 발사 차단.
                ScalpingPosition newPos = new ScalpingPosition(surge.getStockCode(), surge.getStockName(),
                        entryResult.currentPrice, quantity);
                ScalpingPosition prev = scalpingPositions.putIfAbsent(surge.getStockCode(), newPos);
                if (prev != null) {
                    log.warn("[스캘핑봇] 동시 진입 race 감지 - 종목: {} (기존 매수가 {}원), 신규 진입 skip",
                            surge.getStockName(), prev.buyPrice);
                    continue;
                }

                boolean buyOk = false;
                inFlightTrades.incrementAndGet();
                try {
                    activeTradeService.buy(surge.getStockCode(), surge.getStockName(),
                            entryResult.currentPrice, quantity, "SCALPING_ENTRY");
                    buyOk = true;
                    lastTradeTime = LocalDateTime.now(clock);
                    todayBuyCount.incrementAndGet();

                    // ATR 세트 적용 시 적용값 감사 스냅샷(사후검증용) — 주문 흐름과 무관(best-effort)
                    if (appliedRiskBudget != null) {
                        auditAtrApplied("SCALPING", surge.getStockCode(), surge.getStockName(), quantity,
                                entryResult.currentPrice, null, STOP_LOSS_RATE, null, appliedRiskBudget);
                    }

                    // WebSocket 실시간 시세 구독 — 매도 평가 시 polling 대신 push 사용.
                    // 빈 미등록(disabled) 시 no-op.
                    RealtimePriceBus bus = getBus();
                    if (bus != null) {
                        try { bus.subscribe(surge.getStockCode()); } catch (Exception ignore) {}
                    }

                    try {
                        persistScalpingPosition(newPos);
                    } catch (Exception persistEx) {
                        alertPersistFailure("스캘핑", surge.getStockCode(), surge.getStockName(), persistEx);
                    }

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

                    Thread.sleep(currentMode == TradingMode.REAL ? BUY_DELAY_REAL_MS : BUY_DELAY_VIRTUAL_MS);

                } catch (Exception e) {
                    log.error("[스캘핑봇] 매수 실패: {} - {}", surge.getStockName(), e.getMessage());
                } finally {
                    inFlightTrades.decrementAndGet();
                    // 매수 실패 시 메모리 등록 롤백 — 안 그러면 가짜 포지션이 매도 로직에서
                    // 매도 시도되어 KIS 에서 "보유 없음" 에러 반복
                    if (!buyOk) {
                        scalpingPositions.remove(surge.getStockCode());
                    }
                }
            }

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now(clock);
            log.error("[스캘핑봇] 매수 로직 오류", e);
        }
    }

    /**
     * surge 스냅샷이 매매에 사용해도 될 만큼 신선한지 검증.
     *
     * 정상 흐름: InvestorSurgeService.collectIntradaySnapshot 가 10분마다 새 snapshot 생성 →
     *           surgeStocksCacheTime(30s TTL) 와 별개로 데이터 자체의 snapshotTime 도 신선해야 한다.
     * stale 케이스: cron 실패, DB 락, KIS 호출 실패 등으로 어제 마지막 snapshot 만 남는 상황.
     *               이 데이터로 매수 진입하면 어제 시점의 시그널로 오늘 거래되어 손실 위험.
     *
     * 첫 데이터의 snapshotTime 만 검사 — 동일 cron 에서 일괄 적재되므로 대표값으로 충분.
     */
    private boolean isSurgeDataFresh(Map<String, List<InvestorSurgeDto>> surgeStocks) {
        InvestorSurgeDto sample = surgeStocks.values().stream()
                .filter(list -> list != null && !list.isEmpty())
                .map(list -> list.get(0))
                .findFirst().orElse(null);
        if (sample == null || sample.getSnapshotTime() == null) {
            // snapshotTime 미설정 — 신선도 판단 불가. 안전 측으로 통과(기존 동작 유지).
            return true;
        }
        LocalTime now = LocalTime.now(clock);
        long ageMinutes = java.time.Duration.between(sample.getSnapshotTime(), now).toMinutes();
        // 자정 경유는 음수 — 정상 갱신으로 간주
        if (ageMinutes < 0) return true;
        if (ageMinutes > SURGE_DATA_MAX_AGE_MINUTES) {
            log.warn("[스캘핑봇] surge 스냅샷 stale {}분 (기준: {}분) — 매수 보류. snapshotTime={}",
                    ageMinutes, SURGE_DATA_MAX_AGE_MINUTES, sample.getSnapshotTime());
            notifyStaleSurgeData(ageMinutes);
            return false;
        }
        return true;
    }

    /** stale 알림 dedup — 30분에 한 번. cron 실패가 지속될 때 텔레그램 폭주 방지. */
    private void notifyStaleSurgeData(long ageMinutes) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (lastStaleSurgeAlertTime != null
                && java.time.Duration.between(lastStaleSurgeAlertTime, now).toMinutes() < STALE_DATA_ALERT_COOLDOWN_MINUTES) {
            return;
        }
        lastStaleSurgeAlertTime = now;
        if (telegramService.isEnabled()) {
            try {
                telegramService.sendRisk(
                        "<b>⚠️ surge 스냅샷 stale 감지</b>\n\n" +
                        "데이터 " + ageMinutes + "분 경과 (기준 " + SURGE_DATA_MAX_AGE_MINUTES + "분).\n" +
                        "InvestorSurgeService 수집 cron 또는 KIS 응답을 확인하세요.\n" +
                        "자동매수는 신선도 회복까지 보류됩니다."
                );
            } catch (Exception e) { log.warn("[봇] stale 경보 텔레그램 발송 실패 — 사람이 이 보류를 모른다: {}", e.toString()); }
        }
    }

    /**
     * 가격 DTO 가 매도 평가에 사용해도 될 만큼 신선한지 검증.
     * - fetchedAt 미설정: 신선도 판단 불가 → stale 로 간주 (보수적)
     * - fetchedAt 이 PRICE_STALE_SECONDS 초과: stale
     */
    private boolean isPriceStale(StockPriceDto priceDto) {
        LocalDateTime fetchedAt = priceDto.getFetchedAt();
        if (fetchedAt == null) return true;
        long ageSec = java.time.Duration.between(fetchedAt, LocalDateTime.now(clock)).getSeconds();
        return ageSec > PRICE_STALE_SECONDS;
    }

    /**
     * 수급 급증 데이터 캐시 조회 (30초 TTL)
     * 스냅샷은 10분마다 갱신되므로 매 사이클(5초)마다 DB 조회할 필요 없음
     */
    private synchronized Map<String, List<InvestorSurgeDto>> getCachedSurgeStocks() {
        LocalDateTime now = LocalDateTime.now(clock);
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
                log.info("[스캘핑봇] Skip [{}({})] 순매수 부족 (현재: {}억 < 기준: {}억)",
                        stockName, stockCode, netBuyAmount, MIN_NET_BUY_AMOUNT);
                return ScalpingEntryResult.fail("순매수 부족: " + netBuyAmount + "억");
            }

            // ==================== 필수 조건 2: 현재가 조회 ====================
            StockPriceDto priceDto = stockPriceService.getStockPrice(stockCode);
            if (priceDto == null || priceDto.getCurrentPrice() == null) {
                log.info("[스캘핑봇] Skip [{}({})] 현재가 조회 실패", stockName, stockCode);
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

            // 둘 중 하나라도 충족하면 통과(OR) — 유동성 지표 관대 적용
            if (!tradingValueOk && !volumeRatioOk) {
                log.info("[스캘핑봇] Skip [{}({})] 거래대금/거래량 부족 (거래대금: {}, 전일거래량비율 충족: {})",
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
                    log.info("[스캘핑봇] Skip [{}({})] 저변동성 종목 (일중변동폭: {}% < 기준: {}%)",
                            stockName, stockCode, intradayRange.setScale(1, RoundingMode.HALF_UP), MIN_INTRADAY_RANGE);
                    return ScalpingEntryResult.fail("저변동성 종목: 일중변동폭 " + intradayRange.setScale(1, RoundingMode.HALF_UP) + "%");
                }
            }

            // ==================== 필수 조건 3: 양봉 ====================
            if (openPrice == null || openPrice.compareTo(BigDecimal.ZERO) <= 0) {
                BigDecimal changeRate = priceDto.getChangeRate();
                if (changeRate == null || changeRate.compareTo(BigDecimal.ZERO) <= 0) {
                    log.info("[스캘핑봇] Skip [{}({})] 양봉 조건 미충족 (등락률: {}%)",
                            stockName, stockCode, changeRate);
                    return ScalpingEntryResult.fail("양봉 조건 미충족");
                }
            } else if (currentPrice.compareTo(openPrice) <= 0) {
                log.info("[스캘핑봇] Skip [{}({})] 음봉 (현재가: {} <= 시초가: {})",
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
            // 체결강도를 필수→보조로 변경 (KIS API가 null 반환하는 종목이 50%+ 차지)
            BigDecimal volumePower = null;
            try {
                ScalpingAnalysisDto scalpingData = scalpingAnalysisService.getVolumePowerRefresh(stockCode);
                if (scalpingData != null && scalpingData.getVolumePower() != null) {
                    volumePower = scalpingData.getVolumePower();
                }
            } catch (Exception e) {
                log.debug("[스캘핑봇] 체결강도 조회 실패 [{}]: {}", stockCode, e.getMessage());
            }

            int subScore = 0;
            int subTotal = 4;
            List<String> passedSubs = new java.util.ArrayList<>();
            List<String> failedSubs = new java.util.ArrayList<>();

            // ===== 보조 A: 체결강도 ≥ 120% (null이면 스킵) =====
            if (volumePower != null) {
                if (volumePower.compareTo(MIN_VOLUME_POWER) >= 0) {
                    subScore++;
                    passedSubs.add("체결강도 " + volumePower + "%");
                } else {
                    failedSubs.add("체결강도 " + volumePower + "% < " + MIN_VOLUME_POWER + "%");
                }
            } else {
                failedSubs.add("체결강도 데이터 없음(스킵)");
            }

            // ===== 보조 B: RSI(14) < 55 =====
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

            // ===== 보조 C: 20MA 이격도 < 3% =====
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

            // ===== 보조 D: 갭상승 < 5% =====
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

            // ==================== 최종 판정: 보조 조건 충족 수 ====================
            int requiredSubScore = REQUIRED_SUB_CONDITIONS;
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
     * - 15초 간격으로 실행 (08:00~20:00, 프리/정규/애프터마켓 전체)
     *
     * 2026-04-23 장애 원인: 3초 주기 × EGW00201 백오프(2~4초) = 스케줄러 스레드풀 고갈
     *   → 컨테이너 OOM → Docker 재시작 루프 → 호스트 다운 (SSH 마비)
     * 2026-04-24 수정: 3초 → 15초 완화 + 동시실행 가드 + 긴급 kill switch.
     *   15초면 손절 체결까지 지연 평균 7.5초 — 충분히 허용 범위.
     */
    @Scheduled(cron = "*/15 * 8-19 * * MON-FRI", zone = "Asia/Seoul")
    public void executeScalpingSellLogic() {
        if (!botLeader.isLeaderForBot()) return;   // 리더 게이트(fail-CLOSED)
        if (!scalpingSellRunning.compareAndSet(false, true)) {
            log.warn("[스캘핑봇] 매도 로직 이전 실행 아직 진행 중 — 이번 틱 스킵");
            return;
        }
        // 모드 전환 race 방어 — 진행 중 trade 카운터에 등록.
        // startBot 의 모드 전환은 inFlightTrades.get() == 0 될 때까지 대기.
        inFlightTrades.incrementAndGet();
        try {
            executeScalpingSellLogicInternal();
        } finally {
            inFlightTrades.decrementAndGet();
            scalpingSellRunning.set(false);
        }
    }

    private void executeScalpingSellLogicInternal() {
        // 킬스위치가 손절을 막지 않게(2026-08-05 감사 P0) — §4d 매도 fail-open.
        if (!shouldRunSellCycle(botActive.get(), killSwitchTriggered.get(), !scalpingPositions.isEmpty())) {
            return;
        }

        if (isMarketClosed()) {
            return;
        }

        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(PRE_MARKET_START) || now.isAfter(AFTER_MARKET_END)) {
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

                // ★ 스윙/종가매수 포지션은 스캘핑 매도에서 제외 (각자 매도 로직이 있음)
                String stockCode = portfolio.getStockCode();
                if (swingPositions.containsKey(stockCode) || closingPositions.containsKey(stockCode)) {
                    continue;
                }

                BigDecimal currentPrice = priceDto.getCurrentPrice();
                // ★ 매도 평가 가격 신선도 검증 — stockPriceService 캐시(KIS 1분 / Naver 10분) 가
                //    stale 한 시점에 익절/트레일링 임계값을 잘못 통과시키지 않도록 KIS 재조회.
                //    재조회 실패 시 보류(continue) — 다음 15초 사이클에서 재시도.
                if (isPriceStale(priceDto)) {
                    BigDecimal fresh = fetchLatestPriceQuiet(stockCode);
                    if (fresh != null && fresh.signum() > 0) {
                        currentPrice = fresh;
                    } else {
                        log.warn("[스캘핑봇] 매도 평가 가격 stale + 재조회 실패 — 보류: {} (fetchedAt={})",
                                portfolio.getStockName(), priceDto.getFetchedAt());
                        continue;
                    }
                }
                ScalpingPosition position = scalpingPositions.get(stockCode);

                if (position == null) {
                    // 스캘핑 포지션 정보 없으면 스킵 (다른 전략 포지션일 수 있음)
                    continue;
                }

                // 고점 갱신
                position.updateHighPrice(currentPrice);

                BigDecimal profitRate = calcProfitRate(currentPrice, position.buyPrice);
                BigDecimal highDropRate = calcHighDropRate(currentPrice, position.highPrice);

                // 매수 후 경과 시간
                long minutesElapsed = java.time.Duration.between(position.buyTime, LocalDateTime.now(clock)).toMinutes();

                String sellReason = null;
                int sellQuantity = portfolio.getQuantity();
                boolean isPartialSell = false;

                // 1. 손절 체크 (-1.5%)
                if (profitRate.compareTo(STOP_LOSS_RATE) <= 0) {
                    sellReason = "STOP_LOSS";
                    log.info("[스캘핑봇] 손절 조건: {} - 손익률 {}%", portfolio.getStockName(), profitRate);
                }
                // 2. 익절 1차 체크 (+1.2% 절반 매도)
                //    ⚠ 1주 포지션은 절반이 0주라 매도가 안 나가는데 halfSold 도 안 서서, else-if 체인
                //    아래의 트레일링·타임컷이 <b>영영 평가되지 않았다</b>(2026-08-05 감사). 익절 구간에
                //    머무는 한 +8%까지 갔다 되밀려도 매도가 없었다. 나눌 수 없으면 이 분기를 건너뛰어
                //    트레일링/타임컷이 정상 평가되게 한다(1주는 전량 매도가 그 둘의 몫).
                else if (!position.halfSold && portfolio.getQuantity() >= 2
                        && profitRate.compareTo(TAKE_PROFIT_FIRST) >= 0) {
                    sellReason = "TAKE_PROFIT_HALF";
                    sellQuantity = portfolio.getQuantity() / 2;
                    if (sellQuantity > 0) {
                        isPartialSell = true;
                        position.halfSold = true;
                        try {
                            persistScalpingPosition(position);  // 재시작 대비: halfSold 플래그 영속화
                        } catch (Exception persistEx) {
                            alertPersistFailure("스캘핑(halfSold)", position.stockCode, position.stockName, persistEx);
                        }
                        log.info("[스캘핑봇] 1차 익절: {} - 손익률 {}%, 절반({}) 매도",
                                portfolio.getStockName(), profitRate, sellQuantity);
                    }
                }
                // 3. 트레일링 스탑 체크 (고점 대비 -1.0%)
                //    1주 포지션은 절반익절이 구조적으로 불가능하므로 halfSold 를 요구하지 않는다
                //    (요구하면 트레일링이 영영 안 걸린다 — 2026-08-05 감사).
                else if ((position.halfSold || portfolio.getQuantity() == 1)
                        && highDropRate.compareTo(TRAILING_STOP_RATE) <= 0) {
                    sellReason = "TRAILING_STOP";
                    log.info("[스캘핑봇] 트레일링 스탑: {} - 고점대비 {}%", portfolio.getStockName(), highDropRate);
                }
                // 4. 타임컷 체크 (동적 연장 포함)
                else if (minutesElapsed >= TIME_CUT_EXTENDED_MINUTES) {
                    // 20분(최대 연장) → 무조건 청산
                    sellReason = "TIME_CUT";
                    log.info("[스캘핑봇] 타임컷(최대): {} - {}분 경과, 손익률 {}%",
                            portfolio.getStockName(), minutesElapsed, profitRate);
                }
                else if (minutesElapsed >= TIME_CUT_MINUTES && !position.timeExtended) {
                    // 15분 경과 → 연장 조건 체크 (수익 +0.3% & 거래량 증가 시 +5분)
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
            lastErrorTime = LocalDateTime.now(clock);
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
            TradeHistoryDto sellDto = activeTradeService.sell(portfolio.getStockCode(), currentPrice, quantity, reason);
            lastTradeTime = LocalDateTime.now(clock);
            todaySellCount.incrementAndGet();

            BigDecimal profitLoss = currentPrice.subtract(buyPrice).multiply(BigDecimal.valueOf(quantity));

            log.info("[스캘핑봇-{}] {} 완료: {} x {} @ {}원, 손익: {}원",
                    currentMode.name(), reason, portfolio.getStockName(),
                    quantity, formatNumber(currentPrice), formatNumber(profitLoss));

            // 매도 성공 → 실패 카운터 리셋 (부분/전량 무관)
            sellFailCount.remove(portfolio.getStockCode());
            sellFailLastAlert.remove(portfolio.getStockCode());

            // 전량 매도 시 포지션 정리 + 쿨다운 기록.
            // B2-A: 실전 지정가가 부분/미체결로 확정되면 포지션을 제거하지 않고 유지 → 잔량 KIS orphan 방지.
            //       (전량체결/조회불가는 현행대로 제거 — 조회 실패해도 최악이 현행과 동일)
            if (!isPartialSell && isSellConfirmedShort(portfolio.getStockCode(), sellDto, quantity)) {
                log.warn("[스캘핑봇] 매도 부분/미체결 확정 — 포지션 유지, 다음 사이클 재시도: {}", portfolio.getStockName());
                if (telegramService.isEnabled()) {
                    try {
                        telegramService.sendRisk(String.format(
                                "⚠️ <b>[스캘핑봇] 매도 부분/미체결</b>\n\n종목: %s (%s)\n지정가 %s원 주문이 전량 체결되지 않아 포지션을 유지하고 다음 사이클에 재시도합니다.",
                                portfolio.getStockName(), portfolio.getStockCode(), formatNumber(currentPrice)));
                    } catch (Exception e) { log.warn("[스캘핑봇] 부분/미체결 경보 발송 실패 — 사람 개입 알림이 안 갔다: {}", e.toString()); }
                }
            } else if (!isPartialSell) {
                scalpingPositions.remove(portfolio.getStockCode());
                deletePersistedPosition(Strategy.SCALPING, portfolio.getStockCode());
                sellCooldownMap.put(portfolio.getStockCode(), LocalDateTime.now(clock));
                // WebSocket 구독 해제 — 41 슬롯 한도 보호.
                RealtimePriceBus bus = getBus();
                if (bus != null) {
                    try { bus.unsubscribe(portfolio.getStockCode()); } catch (Exception ignore) {}
                }

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
            log.error("[스캘핑봇] 매도 실패: {} - {}", portfolio.getStockName(), e.getMessage(), e);

            // 1차 익절(절반 매도)은 주문 전에 halfSold=true 를 선반영·영속화하므로, 주문 실패 시
            // 되돌리지 않으면 절반익절이 영구 스킵되고 트레일링 스탑이 전량을 지배한다 — 매수 buyOk 롤백과 동일 원칙.
            if (isPartialSell) {
                ScalpingPosition failedPos = scalpingPositions.get(portfolio.getStockCode());
                if (failedPos != null && failedPos.halfSold) {
                    failedPos.halfSold = false;
                    try {
                        persistScalpingPosition(failedPos);
                    } catch (Exception persistEx) {
                        log.warn("[스캘핑봇] halfSold 롤백 영속화 실패(메모리는 롤백됨): {}", persistEx.getMessage());
                    }
                }
            }

            // 종목별 실패 카운트 — 5회 누적 시 포기 (무한 재시도 / 알림 폭주 방지)
            String code = portfolio.getStockCode();
            int failCount = sellFailCount.computeIfAbsent(code, k -> new AtomicInteger(0))
                    .incrementAndGet();
            boolean giveUp = failCount >= SELL_FAIL_GIVE_UP;

            // 알림 dedup — 같은 종목은 5분에 1회만 (포기 시점은 무조건 알림)
            boolean shouldAlert = giveUp || shouldSendSellFailAlert(code);
            if (shouldAlert && telegramService.isEnabled()) {
                try {
                    String header = giveUp
                            ? "🚨 <b>[스캘핑봇] 매도 " + failCount + "회 실패 — 포기</b>"
                            : "⚠️ <b>[스캘핑봇] 매도 실패 (" + failCount + "/" + SELL_FAIL_GIVE_UP + ")</b>";
                    telegramService.sendRisk(String.format(
                            "%s\n\n종목: %s (%s)\n수량: %d주 @ %s원\n사유: %s\n에러: %s%s",
                            header,
                            portfolio.getStockName(), portfolio.getStockCode(),
                            quantity, formatNumber(currentPrice), reason,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                            giveUp ? "\n\n⚠️ 메모리 포지션 정리됨 — 실거래 보유는 수동 확인 필요" : ""));
                } catch (Exception telegramErr) {
                    log.warn("[봇] 텔레그램 RISK 알람 전송 실패: {}", telegramErr.getMessage());
                }
            }

            if (giveUp) {
                // 5회 연속 실패 — 메모리 포지션 강제 cleanup. 실거래 보유는 운영자 수동 처리.
                scalpingPositions.remove(code);
                deletePersistedPosition(Strategy.SCALPING, code);
                sellFailCount.remove(code);
                sellFailLastAlert.remove(code);
            }
        }
    }

    /** WebSocket 빈이 등록되어 있으면 인스턴스, 아니면 null. ObjectProvider 가 매번 안전 반환. */
    private RealtimePriceBus getBus() {
        return realtimePriceBusProvider.getIfAvailable();
    }

    /**
     * KIS 현재가 빠른 조회 — 진입 직전 가격 검증용. 실패 시 null (조용히 skip).
     *
     * 우선순위:
     *  1) WebSocket push 캐시 (≤2초): rate-limit 부담 0, 사실상 즉시값
     *  2) KIS REST 호출: WebSocket 비활성/구독 안 됨/첫 틱 도착 전인 경우
     */
    private BigDecimal fetchLatestPriceQuiet(String stockCode) {
        RealtimePriceBus bus = getBus();
        if (bus != null) {
            BigDecimal pushed = bus.getCurrentPriceIfFresh(stockCode, 2);
            if (pushed != null && pushed.signum() > 0) return pushed;
        }
        try {
            JsonNode resp = kisService.getStockPriceWithPriority(stockCode,
                    KisApiRateLimiter.Priority.HIGH);
            if (resp == null || !resp.has("output")) return null;
            JsonNode output = resp.get("output");
            if (!output.has("stck_prpr")) return null;
            String s = output.get("stck_prpr").asText();
            if (s == null || s.isEmpty()) return null;
            return new BigDecimal(s.replace(",", ""));
        } catch (Exception e) {
            log.debug("[스캘핑봇] 진입 직전 가격 재조회 실패 — surge cache 가격으로 진행: {}", e.getMessage());
            return null;
        }
    }

    /**
     * ×10 가격 이상치 봇 방어선 (2026-07-06) — 진입가가 전일 종가 대비 ±50% 초과면 주문 차단.
     *
     * <p>앵커 = {@code StockPriceHistory}(J=KRX 단독 소스) 최신 종가. 현재가 경로(UN 통합시세)와
     * 소스가 분리돼 있어 UN 응답 전체 ×10(BATCH_SCALED)에도 오염되지 않는 유일한 앵커다.
     * KIS 응답 역산(prdy_vrss)은 통배수 오염 시 같이 스케일돼 무력 — 앵커로 쓰지 말 것.
     *
     * <p>§16-3(가격 이상치 로깅만·미보정)과 비충돌 — 가격은 건드리지 않고 <b>주문만 막는다</b>.
     * 앵커 결측/오래됨/조회실패 = UNKNOWN = 통과(§4c: 결측을 근거로 차단하지 않음 —
     * 1차 탐지망은 여전히 {@code warnIfPriceOutlier} 로깅).
     */
    private boolean passesPriceSanity(String stockCode, String stockName, BigDecimal entryPrice, String botLabel) {
        PriceSanityGuard.Result r;
        try {
            var anchor = stockPriceHistoryRepository.findTopByStockCodeOrderByTradeDateDesc(stockCode).orElse(null);
            r = PriceSanityGuard.judge(entryPrice,
                    anchor != null ? anchor.getClosePrice() : null,
                    anchor != null ? anchor.getTradeDate() : null,
                    LocalDate.now(clock));
        } catch (Exception e) {
            warnGuardFailureDaily(botLabel + " sanity앵커", "가드 skip(§4d 인지된 트레이드오프)", e);
            return true;
        }
        if (r.verdict() != PriceSanityGuard.Verdict.BLOCKED) {
            return true;
        }
        log.error("[{}봇] ★ 가격 sanity 차단 ★ {} ({}) 진입가 {}원 — {}",
                botLabel, stockName, stockCode, formatNumber(entryPrice), r.detail());
        notifyPriceSanityBlock(stockCode, stockName, entryPrice, r, botLabel);
        return false;
    }

    /** 가격 sanity 차단 리스크 알림 — 같은 종목은 10분에 한 번만 (킬스위치 체크 실패 알림과 동일 스로틀 패턴). */
    private void notifyPriceSanityBlock(String stockCode, String stockName, BigDecimal entryPrice,
                                        PriceSanityGuard.Result result, String botLabel) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime last = priceSanityAlertAt.get(stockCode);
        if (last != null && java.time.Duration.between(last, now).toMinutes() < PRICE_SANITY_ALERT_COOLDOWN_MIN) {
            return;
        }
        priceSanityAlertAt.put(stockCode, now);
        try {
            telegramService.sendRisk(String.format(
                    "<b>🚨 [%s봇] 가격 이상치 진입 차단</b>\n\n종목: <b>%s</b> (%s)\n진입 시도가: %s원 (전일 종가 대비 ×%s)\n\n%s\n※ 가격은 보정하지 않음 — 주문만 차단. [가격이상] 로그 확인 요망.",
                    botLabel, stockName, stockCode, formatNumber(entryPrice),
                    result.ratio() != null ? result.ratio().toPlainString() : "?",
                    result.detail()));
        } catch (Exception e) {
            log.warn("[{}봇] 가격 sanity 차단 알림 발송 실패: {}", botLabel, e.getMessage());
        }
    }

    /** 매도 실패 알림 dedup — 같은 종목은 5분에 한 번만. */
    private boolean shouldSendSellFailAlert(String stockCode) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime lastAlert = sellFailLastAlert.get(stockCode);
        if (lastAlert != null
                && java.time.Duration.between(lastAlert, now).getSeconds() < SELL_FAIL_ALERT_COOLDOWN_SEC) {
            return false;
        }
        sellFailLastAlert.put(stockCode, now);
        return true;
    }

    // ==================== 장 마감 청산 ====================

    @Scheduled(cron = "0 10 15 * * MON-FRI", zone = "Asia/Seoul")
    public void executeScalpingClearance() {
        if (!botLeader.isLeaderForBot()) return;   // 리더 게이트(fail-CLOSED)
        if (!botActive.get()) {
            return;
        }

        if (isMarketClosed()) {
            return;
        }

        log.info("[스캘핑봇] ===== 15:10 스캘핑 청산 시작 (스윙/종가매수·수동 보유 유지) =====");

        try {
            // 봇 스캘핑 추적분만 청산 — 스윙/종가는 물론 수동/untracked 보유분도 건드리지 않음(reconcile 보존 철학).
            TimeCutResult result = sellPortfolioMatching(botOwnedCodes(Set.of(Strategy.SCALPING)), "SCALPING_CLEARANCE");

            if (telegramService.isEnabled()) {
                sendEndOfDayReport(result);
            }

            // 스캘핑 포지션만 정리 — DB 행도 함께 삭제(안 지우면 재시작 복원 시 고스트 부활 + 당일 재진입 차단)
            scalpingPositions.clear();
            try {
                positionRepository.deleteByStrategyAndTradingMode(Strategy.SCALPING, currentMode.name());
            } catch (Exception e) {
                log.warn("[스캘핑봇] 15:10 청산 후 포지션 DB 정리 실패: {}", e.getMessage());
            }

            log.info("[스캘핑봇] 15:10 스캘핑 청산 완료 - {}종목 매도(스윙/종가·수동 유지), 총 손익: {}원",
                    result.soldCount, formatNumber(result.totalProfitLoss));

        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now(clock);
            log.error("[스캘핑봇] 장 마감 청산 오류", e);
        }
    }

    /**
     * 정규장 마감(15:20) 강제청산 — 봇이 포지션 들고 마감하는 오버나잇 노출 방지. BotConfig.forceRegularSessionLiquidation(기본 ON).
     *
     * <p>가드: 리더(작업1, fail-CLOSED) AND 봇활성 AND 미killed AND 설정 ON AND 시각≥15:20 — 모두 통과해야 청산(둘 다 통과해야 주문 원칙).
     * DB killswitch(TradingSafetyService, KIS 불확실성)는 {@code activeTradeService.sell()} 내부에서 추가로 주문을 차단한다.
     * VIRTUAL/REAL 공용({@code activeTradeService}).
     *
     * <p>이 경로는 <b>정규 연속세션(REGULAR)</b> 청산 그대로다(현행 무변경). 15:28 이후 지정가 미체결 잔여는
     * {@link #executeNxtLiquidationRetry()}(NXT 연장세션 15:35~19:55, flag OFF·2026-09-14 대비)가 재청산한다 —
     * 두 경로 모두 {@code isLiquidatedToday()}/{@code markLiquidatedToday()} 를 공유(NXT 에서 완청산 시 마킹).
     */
    @Scheduled(cron = "0 20-28 15 * * MON-FRI", zone = "Asia/Seoul")
    public void executeRegularSessionLiquidation() {
        if (!botLeader.isLeaderForBot()) return;   // 리더 게이트(fail-CLOSED)
        if (!shouldRunLiquidationWindow(botActive.get(), killSwitchTriggered.get(),
                isForceRegularLiquidationEnabled(), isLiquidatedToday(),
                LocalTime.now(clock), FORCE_LIQUIDATION_TIME, FORCE_LIQUIDATION_WINDOW_END)) {
            return;
        }
        log.info("[봇] ===== 정규장 마감 강제청산 시도 (윈도우 15:20~15:28) =====");
        try {
            // 봇 소유(스캘핑+스윙+종가) 추적분만 청산 — 수동/untracked 보유분은 건드리지 않음(reconcile 보존 철학).
            Set<String> botOwned = botOwnedCodes(Set.of(Strategy.SCALPING, Strategy.SWING, Strategy.CLOSING));
            TimeCutResult result = sellPortfolioMatching(botOwned, "REGULAR_SESSION_CLOSE");
            // 완료 = 봇 소유분이 KIS 잔고에서 모두 빠짐(수동 보유분은 남아있어도 무방). 부분 미체결은 다음 분 재시도.
            //
            // ⚠ 잔고 조회 실패를 "잔량 없음"으로 읽으면 미체결인데 완청산을 선언하고, markLiquidatedToday() 가
            // 15:21~15:28 재시도·15:29 미완료 경고·NXT 재청산을 <b>한꺼번에</b> 꺼버린다(2026-08-05 감사 P0).
            // 매도 주문 직후는 잔고 캐시가 무효화돼 실호출이라 실패 확률이 가장 높은 시점이다.
            Optional<List<PortfolioItemDto>> afterSell = activeTradeService.tryGetPortfolio();
            if (afterSell.isEmpty()) {
                log.warn("[봇] 정규장 강제청산 — 잔고 조회 실패로 완료 판정 보류(다음 분 재시도, 경고 유지)");
                return;
            }
            Set<String> stillHeld = new java.util.HashSet<>();
            for (PortfolioItemDto p : afterSell.get()) {
                if (p.getStockCode() != null) stillHeld.add(p.getStockCode());
            }
            stillHeld.retainAll(botOwned);
            // 거래 불가(정지·상폐) 종목은 완료 판정에서 제외(2026-08-05 감사) — 애초에 매도가 불가능한데
            // stillHeld 에 남아 markLiquidatedToday() 가 영영 안 찍혔고, 그 결과 매일 15:29 미완료 경고만
            // 반복되며 그 한 종목이 당일 청산 완료 플래그 전체를 막았다. 제외분은 로그로 가시화(§4c).
            Set<String> untradable = filterUntradable(stillHeld);
            if (!untradable.isEmpty()) {
                log.warn("[봇] 정규장 강제청산 — 거래불가 종목 {}건은 완료 판정에서 제외(수동 확인 필요): {}",
                        untradable.size(), untradable);
                stillHeld.removeAll(untradable);
            }
            if (stillHeld.isEmpty()) {
                scalpingPositions.clear();
                swingPositions.clear();
                closingPositions.clear();
                clearPersistedPositionsForCurrentMode("정규장 강제청산");
                markLiquidatedToday();
                log.info("[봇] 정규장 마감 강제청산 완료 - {}종목 매도, 총 손익: {}원",
                        result.soldCount, formatNumber(result.totalProfitLoss));
                if (telegramService.isEnabled()) {
                    sendEndOfDayReport(result);
                }
            } else {
                log.warn("[봇] 정규장 청산 일부 미체결 — 다음 분 재시도 (봇 소유 잔여 {}종목)", stillHeld.size());
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now(clock);
            log.error("[봇] 정규장 마감 강제청산 오류", e);
        }
    }

    /**
     * 정규장 청산 윈도우(15:29) 종료 후에도 미완료면 텔레그램 리스크 경고 — 청산 실패를 사람이 인지하게.
     * 리더가 15:19에 죽고 승계 공백으로 윈도우 내 캐치업도 못 한 극단 케이스 등을 잡는다.
     */
    @Scheduled(cron = "0 29 15 * * MON-FRI", zone = "Asia/Seoul")
    public void warnIfLiquidationMissed() {
        if (!botLeader.isLeaderForBot()) return;
        if (!shouldWarnLiquidationMissed(botActive.get(), killSwitchTriggered.get(),
                isForceRegularLiquidationEnabled(), isLiquidatedToday())) {
            return;
        }
        log.error("[봇] 정규장 강제청산 미완료 — 윈도우(15:20~15:28) 종료. 오버나잇 노출, 수동 점검 필요.");
        if (telegramService.isEnabled()) {
            telegramService.sendRisk("⚠️ [봇] 정규장 마감 강제청산 미완료 — 윈도우 종료 후에도 청산 안 됨. 오버나잇 포지션 수동 점검 필요.");
        }
    }

    /**
     * NXT 방어 청산 재시도 (2026-09-14 대비, flag OFF — docs/NXT_ROUTING_DESIGN.md §2).
     *
     * <p>정규장 청산(15:20~15:28)에서 <b>지정가 미체결로 남은 봇 소유 잔여</b>를 NXT 연장세션(15:30~20:00)에서
     * 재청산 — P2-13 유일 오버나잇 갭을 9/14 후 없앨 수단. <b>진입은 불건드림(§16-2)</b> — 방어적 청산 창 확대만.
     *
     * <p>가드(전부 AND): 리더(fail-CLOSED) AND {@code bot.nxt-liquidation.enabled} AND {@code bot.nxt-routing.enabled}
     * AND 봇활성 AND 미killed AND 설정 ON AND 창[15:35~19:55] AND 당일 정규장 미완료. 청산 자체는
     * {@code activeTradeService.sell(..., NXT_EXTENDED)} 로 <b>SELL 게이트 전부 통과</b>(killswitch·in-flight 마커·
     * KIS성공+DB실패 killswitch) + <b>지정가</b>(시장가 금지, P2-13-b 기각 근거 유지). NXT 라우팅은 주문 리프가 적용.
     *
     * <p>cron 은 15:00~19:55 매 5분 발화하되 창 밖(≤15:30)은 {@code shouldRunNxtLiquidation} 이 선차단.
     */
    @Scheduled(cron = "0 0/5 15-19 * * MON-FRI", zone = "Asia/Seoul")
    public void executeNxtLiquidationRetry() {
        if (!botLeader.isLeaderForBot()) return;   // 리더 게이트(fail-CLOSED)
        if (!shouldRunNxtLiquidation(botActive.get(), killSwitchTriggered.get(),
                nxtLiquidationEnabled, nxtRoutingEnabled, isForceRegularLiquidationEnabled(),
                isLiquidatedToday(), LocalTime.now(clock), NXT_LIQUIDATION_START, NXT_LIQUIDATION_END)) {
            return;
        }
        // 봇 소유 ∩ KIS 잔고 잔여만 대상(수동/untracked 보호). 없으면 이번 사이클 조용히 종료.
        Set<String> botOwned = botOwnedCodes(Set.of(Strategy.SCALPING, Strategy.SWING, Strategy.CLOSING));
        Set<String> stillHeld = new java.util.HashSet<>();
        for (PortfolioItemDto p : activeTradeService.getPortfolio()) {
            if (p.getStockCode() != null) stillHeld.add(p.getStockCode());
        }
        stillHeld.retainAll(botOwned);
        if (stillHeld.isEmpty()) return;   // 잔여 없음 — NXT 재청산 불필요

        log.info("[봇] ===== NXT 방어 청산 재시도 (창 15:35~19:55, 봇 소유 잔여 {}종목) =====", stillHeld.size());
        try {
            TimeCutResult result = sellPortfolioMatching(botOwned, "NXT_SESSION_CLOSE", OrderSession.NXT_EXTENDED);
            // 완청산 확인 = 봇 소유분이 KIS 잔고에서 모두 빠짐. 정규장과 동일 판정·표기 재사용.
            // 조회 실패 시 완료 선언 금지(정규장과 동일 이유 — markLiquidatedToday 가 감시망을 끈다).
            Optional<List<PortfolioItemDto>> afterFetch = activeTradeService.tryGetPortfolio();
            if (afterFetch.isEmpty()) {
                log.warn("[봇] NXT 방어 청산 — 잔고 조회 실패로 완료 판정 보류(다음 사이클 재시도)");
                return;
            }
            Set<String> after = new java.util.HashSet<>();
            for (PortfolioItemDto p : afterFetch.get()) {
                if (p.getStockCode() != null) after.add(p.getStockCode());
            }
            after.retainAll(botOwned);
            if (after.isEmpty()) {
                scalpingPositions.clear();
                swingPositions.clear();
                closingPositions.clear();
                clearPersistedPositionsForCurrentMode("NXT 방어 청산");
                markLiquidatedToday();
                log.info("[봇] NXT 방어 청산 완료 - {}종목 매도, 총 손익: {}원",
                        result.soldCount, formatNumber(result.totalProfitLoss));
                if (telegramService.isEnabled()) {
                    telegramService.sendRisk(String.format(
                            "🌙 [봇] NXT 방어 청산 완료 — 정규장 미체결 잔여 %d종목 연장세션 청산(총 손익 %s원).",
                            result.soldCount, formatNumber(result.totalProfitLoss)));
                }
            } else {
                log.warn("[봇] NXT 방어 청산 일부 미체결 — 다음 5분 재시도 (봇 소유 잔여 {}종목)", after.size());
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorTime = LocalDateTime.now(clock);
            log.error("[봇] NXT 방어 청산 오류", e);
        }
    }

    /** 청산 윈도우 실행 여부 — 순수 함수(테스트 대상). 리더는 호출부 별도 게이트. 당일 미완료 AND 자격 AND 윈도우 내. */
    static boolean shouldRunLiquidationWindow(boolean botActive, boolean killed, boolean configOn,
                                              boolean doneToday, LocalTime now,
                                              LocalTime windowStart, LocalTime windowEnd) {
        if (doneToday) return false;
        if (!configOn) return false;
        // 킬스위치는 청산을 막지 않는다(2026-08-05 감사 P0) — §4d 의 매도 fail-open 원칙.
        // 킬스위치는 '손실 중'에 발동하므로 그때야말로 청산이 가장 필요하다. 예전엔 !botActive || killed 로
        // 막아, -3% 에서 손실을 멈추라고 만든 장치가 손절·강제청산을 전부 꺼버렸다.
        // 운영자의 명시적 stopBot()(killed=false && !botActive)만 존중한다.
        if (!botActive && !killed) return false;
        return !now.isBefore(windowStart) && !now.isAfter(windowEnd);
    }

    /**
     * NXT 방어 청산 실행 여부 — 순수 함수(테스트 대상). 리더는 호출부 별도 게이트.
     * 정규장 청산 완료(doneToday) 시 불필요, nxt 두 flag·설정·자격·창 전부 충족해야 실행.
     * flag OFF(nxtLiqEnabled/nxtRoutingEnabled 중 하나라도) 시 항상 false = 현행 보존.
     */
    static boolean shouldRunNxtLiquidation(boolean botActive, boolean killed,
                                           boolean nxtLiqEnabled, boolean nxtRoutingEnabled, boolean configOn,
                                           boolean doneToday, LocalTime now,
                                           LocalTime windowStart, LocalTime windowEnd) {
        if (doneToday) return false;
        if (!nxtLiqEnabled || !nxtRoutingEnabled || !configOn || !botActive || killed) return false;
        return !now.isBefore(windowStart) && !now.isAfter(windowEnd);
    }

    /** 윈도우 종료 후 미완료 경고 여부 — 순수 함수(테스트 대상). 자격 있는데 당일 미완료면 경고. */
    static boolean shouldWarnLiquidationMissed(boolean botActive, boolean killed, boolean configOn, boolean doneToday) {
        // 킬스위치 상태에서도 경고는 떠야 한다(2026-08-05 감사) — 예전엔 !killed 조건 때문에
        // 킬스위치가 발동하면 청산도 안 되고 경고도 안 떠서, 운영자가 방치를 인지할 단서가 로그뿐이었다.
        return configOn && !doneToday && (botActive || killed);
    }

    /**
     * 매도(청산) 사이클을 돌려야 하는가 — 순수 함수(2026-08-05 감사 P0).
     *
     * <p>§4d 의 <b>매도 fail-open</b> 원칙: 매도를 막으면 손절을 놓쳐 손실이 커진다. 그래서
     * <b>킬스위치는 매도를 막지 않는다</b>. 킬스위치는 손실 중에 발동하므로 그때야말로 손절이 필요하다.
     *
     * <p>운영자의 명시적 {@code stopBot()}(killed 아님 + botActive=false)만 매도를 멈춘다 —
     * 수동 통제를 가져간 것이므로 존중한다.
     */
    static boolean shouldRunSellCycle(boolean botActive, boolean killed, boolean hasPositions) {
        if (!hasPositions) return false;
        return botActive || killed;
    }

    /** 수급 시그널 최대 허용 나이(달력일) — 주말(2)+연휴 마진. 이보다 오래면 진입 안 함. */
    static final int SWING_SIGNAL_MAX_AGE_DAYS = 5;

    /**
     * 매도 수량 결정 — 순수 함수(2026-08-05 감사). <b>봇이 산 만큼만 판다.</b>
     *
     * <p><b>고치는 문제</b>: 매도 수량이 {@code portfolio.getQuantity()}(=KIS 계좌 전체 보유수량)라,
     * 같은 종목을 수동으로도 들고 있으면 <b>봇 손절선에 수동 물량까지 청산</b>됐다. §4d 는 "KIS 계좌는
     * 수동매매와 공유 가능"을 명시적 전제로 두고 청산 대상을 <b>종목 단위</b>로 보호하는데, 수량 단위로는
     * 무방비였다.
     *
     * <p>봇 수량이 미상(null/0 — 구버전 복원분)이면 종전대로 계좌 수량을 쓴다. 결측을 0으로 읽어
     * 매도를 아예 못 하게 만들면 손절이 막혀 더 위험하다(§4c 는 결측을 불리하게도 위장하지 않는다).
     *
     * @return 실제 주문할 수량(계좌 보유분을 넘지 않는다)
     */
    static int resolveSellQuantity(Integer botQuantity, int accountQuantity) {
        if (accountQuantity <= 0) return 0;
        if (botQuantity == null || botQuantity <= 0) return accountQuantity;   // 미상 → 종전 동작
        return Math.min(botQuantity, accountQuantity);
    }

    /**
     * 킬스위치를 해제해도 되는가 — 순수 함수(2026-08-05 감사).
     *
     * <p>{@code startBot} 이 무조건 킬스위치를 풀고 {@code dailyStartAsset} 을 <b>손실 후 자산</b>으로
     * 재설정해, 운영자가 재시작만 하면 같은 날 -3% 사이클을 반복할 수 있었다(일일 손실 상한 무력화).
     * 발동 <b>당일</b>에는 재개해도 한도를 유지하고, 다음 거래일에 자동 해제한다
     * (§4d 일일 손실 브레이커의 trippedDate 날짜 비교와 같은 방식).
     */
    static boolean canClearKillSwitch(boolean killed, LocalDate killedOn, LocalDate today) {
        if (!killed) return true;                       // 애초에 발동 안 함
        if (killedOn == null || today == null) return true;  // 이력 불명 — 종전대로(fail-open)
        return !killedOn.equals(today);                 // 다른 날이면 해제 가능
    }

    /**
     * 매수 체결 확인 — 미체결/부분체결이면 <b>경고만</b>(2026-08-05 감사). REAL 모드 한정.
     *
     * <p><b>왜 포지션을 지우지 않는가</b>: 미체결 지정가 주문은 장 마감까지 호가창에 살아 있어 나중에
     * 체결될 수 있다. 여기서 포지션을 지우면 그 체결분이 <b>봇 추적 밖 REAL 포지션</b>이 되어 손절이
     * 영영 안 걸린다(§4d reconcile 이 경고만 하는 이유와 같은 상황). 포지션을 유지해야 매도 루프가
     * 계속 감시하고, 실제로 미체결이면 매도 시 "보유 수량 부족"으로 자연히 걸러진다.
     *
     * <p><b>⚠ 잔여</b>: 완전한 해결은 미체결 잔량 <b>취소</b>(order-rvsecncl)가 필요한데, 이는 주문변경
     * API 라 모의계좌 검증이 선행돼야 하는 §4d Phase 2 미구현 항목이다. 그때까지는 운영자 통지가 최선.
     */
    private void warnIfBuyNotFilled(String stockCode, String stockName, TradeHistoryDto buyDto,
                                    int requestedQty, TradingMode runMode) {
        if (runMode != TradingMode.REAL) return;                    // VIRTUAL 은 즉시 체결(시뮬레이션)
        if (buyDto == null || buyDto.getOrderNo() == null) return;  // 주문번호 없으면 확인 불가(§4c)
        try {
            RealTradeService.FillResult fill = realTradeService.confirmFill(
                    stockCode, buyDto.getOrderNo(), requestedQty);
            if (fill == null || fill.isFull()) return;              // 전량 체결 또는 조회불가(UNKNOWN=현행)
            String msg = String.format(
                    "매수 미체결/부분체결 — %s(%s) 요청 %d주 중 %d주 체결. 주문번호 %s 가 호가창에 남아 있습니다. "
                    + "나중에 체결되면 봇 추적과 어긋날 수 있으니 확인하세요.",
                    stockName, stockCode, requestedQty, fill.filledQty(), buyDto.getOrderNo());
            log.warn("[스윙봇] {}", msg);
            if (telegramService.isEnabled()) {
                telegramService.sendRisk("⚠️ " + msg);
            }
        } catch (Exception e) {
            // 실패 = 미체결 여부를 모른 채 넘어감 — 경보 경로 자체가 침묵하는 실패라 보여야 한다(매수당 1회).
            log.warn("[스윙봇] 매수 체결확인 실패({}) — 미체결이어도 경보가 안 나간 상태: {}", stockCode, e.getMessage());
        }
    }

    /**
     * 수급 시그널 신선도 — 순수 함수(2026-08-05 감사).
     *
     * <p>스윙 진입은 "외국인 N일 연속 순매수"로 후보를 잡는데, 그 시그널의 <b>종료일</b>을 보지 않았다.
     * 수집이 며칠 실패하면 <b>이미 소멸한 시그널</b>로 실주문이 나간다(스캘핑엔 {@code isSurgeDataFresh}
     * 가 있고, §4c 의 {@code scoreSupplyDemand} 노후 가드도 같은 취지인데 봇 진입 경로에만 빠져 있었다).
     *
     * <p>거래일 달력 대신 <b>달력일 상한</b>({@value #SWING_SIGNAL_MAX_AGE_DAYS}일)을 쓴다 — 주말+연휴를
     * 넉넉히 덮으면서 "며칠 묵은 시그널"은 확실히 잡는다. 봇에 달력 서비스를 새로 주입하지 않으려는
     * 의도적 단순화(생성자 변경은 회귀면이 넓다).
     *
     * @param signalEndDate 연속매수 종료일(최근일). null=미상이면 통과(fail-open — 전 종목 차단이 더 위험)
     * @param today         오늘(KST). null 이면 통과
     */
    static boolean isSwingSignalFresh(java.time.LocalDate signalEndDate, java.time.LocalDate today) {
        if (signalEndDate == null || today == null) return true;
        return !signalEndDate.isBefore(today.minusDays(SWING_SIGNAL_MAX_AGE_DAYS));
    }

    /** 오늘 정규장 강제청산 완료 여부 — BotConfig.lastForceLiquidationDate == 오늘. 조회 실패 시 미완료(청산 시도 우선). */
    private boolean isLiquidatedToday() {
        try {
            LocalDate today = LocalDate.now(clock);
            return botConfigRepository.findByConfigKey(BOT_CONFIG_KEY)
                    .map(c -> today.equals(c.getLastForceLiquidationDate()))
                    .orElse(false);
        } catch (Exception e) {
            log.warn("[봇] lastForceLiquidationDate 조회 실패 — 미완료로 간주: {}", e.getMessage());
            return false;
        }
    }

    /** 오늘 청산 완료 표기 — 영속(새 리더 승계 시 캐치업 판단 기준). 단일 save 라 원자적. */
    private void markLiquidatedToday() {
        try {
            BotConfig config = botConfigRepository.findByConfigKey(BOT_CONFIG_KEY)
                    .orElse(BotConfig.builder().configKey(BOT_CONFIG_KEY).build());
            config.setLastForceLiquidationDate(LocalDate.now(clock));
            botConfigRepository.save(config);
        } catch (Exception e) {
            log.error("[봇] lastForceLiquidationDate 저장 실패: {}", e.getMessage());
        }
    }

    /** BotConfig.forceRegularSessionLiquidation — null/조회실패 시 ON(오버나잇 회피 우선). */
    private boolean isForceRegularLiquidationEnabled() {
        try {
            return botConfigRepository.findByConfigKey(BOT_CONFIG_KEY)
                    .map(c -> c.getForceRegularSessionLiquidation() == null || c.getForceRegularSessionLiquidation())
                    .orElse(true);
        } catch (Exception e) {
            log.warn("[봇] forceRegularSessionLiquidation 조회 실패 — 기본 ON 처리: {}", e.getMessage());
            return true;
        }
    }

    public TimeCutResult sellAllPortfolio() {
        return sellAllPortfolio("END_OF_DAY");
    }

    /** 전 포지션 청산 — sell 사유(reason)를 호출자가 지정(예: END_OF_DAY / REGULAR_SESSION_CLOSE). */
    public TimeCutResult sellAllPortfolio(String reason) {
        List<PortfolioItemDto> portfolios = activeTradeService.getPortfolio();

        if (portfolios.isEmpty()) {
            log.info("[봇] 보유 종목 없음 - 청산 스킵");
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
                        portfolio.getStockCode(), currentPrice, portfolio.getQuantity(), reason);

                lastTradeTime = LocalDateTime.now(clock);
                todaySellCount.incrementAndGet();
                soldCount++;
                sellCooldownMap.put(portfolio.getStockCode(), LocalDateTime.now(clock));

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
     * 봇이 소유한 종목코드 — in-memory 포지션 맵(전략별) + DB 영속(현 모드) 합집합. <b>수동/untracked 는 미포함(보호)</b>.
     * DB 도 합치는 이유: 모드전환 in-flight 레이스 등으로 메모리에서 빠진 봇 포지션도 청산에서 놓치지 않기 위함
     * (오버나잇 노출 방지 — "추적분인데 안 파는" 반대 사고 차단). reconcile 의 "수동/untracked 보존" 철학과 일관.
     * DB 조회 실패 시 in-memory 맵만 사용(best-effort).
     */
    /**
     * 거래 불가(정지·상폐) 종목만 골라낸다 — 청산 완료 판정에서 제외용(2026-08-05 감사).
     *
     * <p>매도가 구조적으로 불가능한 종목이 {@code stillHeld} 에 남아 {@code markLiquidatedToday()} 를
     * 영영 막았고, 그 한 종목 때문에 매일 15:29 미완료 경고만 반복됐다. 제외하되 <b>조용히 지우지 않고</b>
     * 호출부가 로그로 노출한다(§4c — 수동 확인이 필요한 잔여다).
     *
     * <p>{@code isActive} 는 KRX 동기화 전 fail-open 이라, 판정 불가 시 "거래 가능"으로 보아
     * 종전대로 청산 대상에 남긴다(안전 방향).
     */
    private Set<String> filterUntradable(Set<String> codes) {
        Set<String> out = new java.util.HashSet<>();
        for (String code : codes) {
            try {
                if (!stockStatusService.isActive(code)) out.add(code);
            } catch (Exception e) {
                log.debug("[봇] 거래가능 판정 실패({}) — 청산 대상 유지: {}", code, e.getMessage());
            }
        }
        return out;
    }

    private Set<String> botOwnedCodes(Set<Strategy> strategies) {
        Set<String> codes = new java.util.HashSet<>();
        if (strategies.contains(Strategy.SCALPING)) codes.addAll(scalpingPositions.keySet());
        if (strategies.contains(Strategy.SWING)) codes.addAll(swingPositions.keySet());
        if (strategies.contains(Strategy.CLOSING)) codes.addAll(closingPositions.keySet());
        try {
            for (BotTradingPosition p : positionRepository.findByTradingMode(currentMode.name())) {
                if (p.getStockCode() != null && p.getStrategy() != null && strategies.contains(p.getStrategy())) {
                    codes.add(p.getStockCode());
                }
            }
        } catch (Exception e) {
            log.warn("[봇] 청산 대상 DB 조회 실패 — in-memory 맵만 사용: {}", e.getMessage());
        }
        return codes;
    }

    /** 청산 대상 = KIS 실보유 ∩ 봇 소유 — 순수 함수(테스트 대상). 수동(KIS만 보유)은 보호, 유령(봇 추적O·KIS X)은 스킵. */
    static Set<String> liquidationTargets(Set<String> kisHeldCodes, Set<String> botOwnedCodes) {
        Set<String> t = new java.util.TreeSet<>(kisHeldCodes);
        t.retainAll(botOwnedCodes);
        return t;
    }

    /**
     * 봇 소유(targetCodes)에 해당하는 KIS 보유분만 청산 — <b>수동/untracked 는 매도하지 않음</b>(reconcile 보존 철학).
     * 기존 "전체 매도(sellAllPortfolio)"·"제외 매도(sellPortfolioExcept)"가 수동 보유분까지 팔던 것을 대체.
     */
    private TimeCutResult sellPortfolioMatching(Set<String> targetCodes, String reason) {
        return sellPortfolioMatching(targetCodes, reason, OrderSession.REGULAR);
    }

    /**
     * 세션 지정 청산 (NXT 방어 청산용). REGULAR(기본)은 현행과 동일 — 정규장 청산이 그대로 위임.
     * NXT_EXTENDED 는 매도 주문만 연장세션으로 라우팅(모든 SELL 게이트는 {@code activeTradeService.sell} 내부에서 동일 통과).
     */
    private TimeCutResult sellPortfolioMatching(Set<String> targetCodes, String reason, OrderSession session) {
        List<PortfolioItemDto> toSell = activeTradeService.getPortfolio().stream()
                .filter(p -> p.getStockCode() != null && targetCodes.contains(p.getStockCode()))
                .collect(Collectors.toList());
        if (toSell.isEmpty()) {
            log.info("[봇] 청산 대상(봇 소유 ∩ 보유) 없음 - 스킵");
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
                // REGULAR 은 기존 4-arg 그대로 호출(현행 바이트 동일). NXT 만 세션 오버로드 경유.
                TradeHistoryDto result = (session == OrderSession.REGULAR)
                        ? activeTradeService.sell(portfolio.getStockCode(), currentPrice, portfolio.getQuantity(), reason)
                        : activeTradeService.sell(portfolio.getStockCode(), currentPrice, portfolio.getQuantity(), reason, session);
                lastTradeTime = LocalDateTime.now(clock);
                todaySellCount.incrementAndGet();
                soldCount++;
                sellCooldownMap.put(portfolio.getStockCode(), LocalDateTime.now(clock));
                BigDecimal profitLoss = result.getProfitLoss() != null ? result.getProfitLoss() : BigDecimal.ZERO;
                totalProfitLoss = totalProfitLoss.add(profitLoss);
                BigDecimal avgPrice = portfolio.getAveragePrice();
                BigDecimal profitRate = (avgPrice != null && avgPrice.compareTo(BigDecimal.ZERO) > 0)
                        ? currentPrice.subtract(avgPrice).divide(avgPrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                        : BigDecimal.ZERO;
                soldItems.add(new TimeCutItem(portfolio.getStockName(), portfolio.getStockCode(),
                        portfolio.getQuantity(), currentPrice, profitLoss, profitRate));
                log.info("[봇] 청산({}): {} x {} @ {}원, 손익: {}원", reason, portfolio.getStockName(),
                        portfolio.getQuantity(), formatNumber(currentPrice), formatNumber(profitLoss));
                Thread.sleep(300);
            } catch (Exception e) {
                log.error("[봇] 청산 실패: {} - {}", portfolio.getStockName(), e.getMessage());
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
        if (!botLeader.isLeaderForBot()) return;   // 리더 게이트(fail-CLOSED)
        if (!botActive.get() || killSwitchTriggered.get()) return;
        if (isMarketClosed()) return;

        // 일일 손실률 평가 — 스캘핑 손실로 -3% 도달 시 스윙 신규 진입도 막아야 함.
        // (이 호출은 KIS 자산 조회 1회 비용. 스윙 매수는 14:00 1회 발사라 부담 없음)
        if (checkKillSwitch()) return;

        // 모드/서비스 스냅샷 — 진행 중 모드 전환되어도 시작 시점 service 로 일관 동작
        final TradingMode runMode = currentMode;
        final TradeService runTrader = activeTradeService;

        // ★ 일일 손실 서킷브레이커(V38) — runMode 스냅샷 이후 평가(모드 flip 시 잘못된 계좌 합산 방지) ★
        if (entryBlockedByDailyLossBreaker(runMode == TradingMode.REAL, "스윙봇")) {
            return;
        }

        // ★ 변동성 국면 게이트(V46) — 브레이커 형제 레이어. BLOCK=진입 취소 / REDUCE=사이즈 축소(청산 무관) ★
        final VolatilityRegimeService.Decision volGate = volRegimeEntryDecision("스윙봇");
        if (volGate == VolatilityRegimeService.Decision.BLOCK) {
            return;
        }

        // 스윙 보유 한도 체크
        if (swingPositions.size() >= SWING_MAX_HOLDING) {
            log.debug("[스윙봇] 최대 보유 {}종목 도달 — 신규 진입 스킵", SWING_MAX_HOLDING);
            return;
        }

        log.info("[스윙봇] ===== 스윙 진입 체크 시작 (mode={}) =====", runMode);

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
            BigDecimal currentBalance = account.getCurrentBalance() != null
                    ? account.getCurrentBalance() : BigDecimal.ZERO;
            BigDecimal totalAsset = currentBalance.add(
                    account.getTotalEvaluation() != null ? account.getTotalEvaluation() : BigDecimal.ZERO);
            BigDecimal maxPerStock = totalAsset.multiply(SWING_INVESTMENT_RATIO);
            // 가용현금 초과 방지: 한 종목 매수액이 보유 현금을 넘지 않도록 캡
            if (maxPerStock.compareTo(currentBalance) > 0) {
                maxPerStock = currentBalance;
            }
            // 변동성 REDUCED 모드 — 진입 캡 축소(PositionSizer 6번째 인자·plain-divide 공통 캡이라 두 경로 모두 반영)
            if (volGate == VolatilityRegimeService.Decision.REDUCE) {
                maxPerStock = maxPerStock.multiply(BigDecimal.valueOf(volRegimeReducedFactor()));
            }

            // 이미 보유 중인 종목 제외
            Set<String> holdingCodes = activeTradeService.getPortfolio().stream()
                    .map(PortfolioItemDto::getStockCode).collect(Collectors.toSet());

            java.time.LocalDate signalToday = java.time.LocalDate.now(clock);

            for (ConsecutiveBuyDto candidate : candidates.values()) {
                if (swingPositions.size() >= SWING_MAX_HOLDING) break;
                if (holdingCodes.contains(candidate.getStockCode())) continue;
                if (swingPositions.containsKey(candidate.getStockCode())) continue;
                // 스캘핑/종가매수 봇이 동일 종목 보유 중이면 중복 진입 방지
                if (scalpingPositions.containsKey(candidate.getStockCode())) continue;
                if (closingPositions.containsKey(candidate.getStockCode())) continue;
                if (sellCooldownMap.containsKey(candidate.getStockCode())) continue;

                // ★ 시그널 신선도(2026-08-05 감사) — 수집이 며칠 실패하면 이미 소멸한 연속매수
                //   시그널로 실주문이 나간다. 스캘핑엔 isSurgeDataFresh 가 있는데 스윙에만 없었다.
                if (!isSwingSignalFresh(candidate.getEndDate(), signalToday)) {
                    log.info("[스윙봇] Skip [{}({})] 수급 시그널 노후(종료일 {}, 허용 {}일)",
                            candidate.getStockName(), candidate.getStockCode(),
                            candidate.getEndDate(), SWING_SIGNAL_MAX_AGE_DAYS);
                    continue;
                }

                // ★ 거래정지/상폐 종목 진입 차단(2026-08-05 감사) — 스캘핑엔 있던 게이트가
                //   실주문 경로인 스윙에만 빠져 있었다.
                if (!stockStatusService.isActive(candidate.getStockCode())) {
                    log.info("[스윙봇] Skip [{}({})] 거래정지/상폐 종목",
                            candidate.getStockName(), candidate.getStockCode());
                    continue;
                }

                // 기술적 조건 체크
                if (!checkSwingTechnical(candidate.getStockCode())) continue;

                // 현재가 조회
                StockPriceDto priceDto = stockPriceService.getStockPrice(candidate.getStockCode());
                if (priceDto == null || priceDto.getCurrentPrice() == null) continue;

                BigDecimal currentPrice = priceDto.getCurrentPrice();

                // ★ 가격 sanity 가드 — 전일 종가(J 소스) 대비 ±50% 초과(×10 오염 의심)면 진입 차단 ★
                if (!passesPriceSanity(candidate.getStockCode(), candidate.getStockName(),
                        currentPrice, "스윙")) {
                    continue;
                }

                // 매수 수량 — 현행: maxPerStock 전액. ATR 세트(flag ON + VIRTUAL, V42):
                // 진입 시점 ATR14 스냅샷 → 손절폭 = ATR×2.5/진입가 → 리스크 균등 수량(항상 현행 이하).
                // ATR 결측(§4c) = 그 종목은 완전 현행(수량 폴백 + 고정 -3/+5 청산).
                int quantity;
                BigDecimal swingEntryAtr = null;
                AtrExitRule.Levels atrLevels = null;
                BigDecimal swingRiskBudget = null;
                if (isAtrSetActive(runMode)) {
                    swingEntryAtr = computeEntryAtrQuiet(candidate.getStockCode());
                    atrLevels = AtrExitRule.judge(currentPrice, swingEntryAtr);
                    if (atrLevels != null) {
                        swingRiskBudget = resolveAtrRiskBudget();
                        quantity = PositionSizer.judge(totalAsset, currentPrice, atrLevels.stopPct().abs(),
                                swingRiskBudget, SWING_INVESTMENT_RATIO, maxPerStock);
                    } else {
                        swingEntryAtr = null;   // 결측 → 완전 현행
                        quantity = maxPerStock.divide(currentPrice, 0, RoundingMode.DOWN).intValue();
                    }
                } else {
                    quantity = maxPerStock.divide(currentPrice, 0, RoundingMode.DOWN).intValue();
                }
                if (quantity <= 0) continue;

                // 매수 실행 — 모드 전환 race 방어
                if (!botActive.get() || currentMode != runMode) {
                    log.warn("[스윙봇] 매수 직전 봇 비활성화 또는 모드 전환 감지 — 중단");
                    return;
                }
                String investorLabel = candidate.getInvestorType().equals("FOREIGN") ? "외국인" : "기관";
                String reason = "SWING_" + candidate.getInvestorType();

                // ★ 메모리 포지션 선등록 — KIS 호출 전. 동시 진입 race 차단 ★
                SwingPosition newSwing = new SwingPosition(candidate.getStockCode(), candidate.getStockName(),
                        currentPrice, investorLabel + candidate.getConsecutiveDays() + "일연속");
                newSwing.botQuantity = quantity;   // 매도 시 계좌 전량이 아니라 이 수량만(2026-08-05 감사)
                if (atrLevels != null) {   // 진입 스냅샷 고정 — 보유 중 재계산 금지
                    newSwing.entryAtr = swingEntryAtr;
                    newSwing.atrStopPct = atrLevels.stopPct();
                    newSwing.atrTargetPct = atrLevels.targetPct();
                }
                SwingPosition prevSwing = swingPositions.putIfAbsent(candidate.getStockCode(), newSwing);
                if (prevSwing != null) {
                    log.warn("[스윙봇] 동시 진입 race - {} 이미 보유, skip", candidate.getStockName());
                    continue;
                }

                boolean swingBuyOk = false;
                try {
                    TradeHistoryDto buyDto = runTrader.buy(candidate.getStockCode(), candidate.getStockName(),
                            currentPrice, quantity, reason);
                    swingBuyOk = true;

                    // ★ 매수 체결 확인(2026-08-05 감사) — 지정가 매수는 즉시 체결되지 않는 게 흔한데
                    //   "주문 접수 = 체결"로 간주해왔다. 미체결이면 30초 뒤 매도 틱이 잔고에 없다고
                    //   포지션을 지웠고, 그 주문이 나중에 체결되면 봇 추적 밖 REAL 포지션이 됐다.
                    //   포지션은 유지한다(지워야 나중 체결이 orphan 된다) — 운영자에게 알리는 게 목적.
                    warnIfBuyNotFilled(candidate.getStockCode(), candidate.getStockName(), buyDto, quantity, runMode);

                    try {
                        persistSwingPosition(newSwing, Strategy.SWING);
                    } catch (Exception persistEx) {
                        alertPersistFailure("스윙", candidate.getStockCode(), candidate.getStockName(), persistEx);
                    }

                    lastTradeTime = LocalDateTime.now(clock);
                    todayBuyCount.incrementAndGet();

                    // ATR 세트 적용 시 적용값 감사 스냅샷(사후검증용) — 주문 흐름과 무관(best-effort)
                    if (atrLevels != null) {
                        auditAtrApplied("SWING", candidate.getStockCode(), candidate.getStockName(), quantity,
                                currentPrice, swingEntryAtr, atrLevels.stopPct(), atrLevels.targetPct(),
                                swingRiskBudget);
                    }

                    log.info("[스윙봇-{}] ★ 스윙 진입 ★ {} ({}) {}원 x {}주 | {}",
                            currentMode.name(), candidate.getStockName(), candidate.getStockCode(),
                            formatNumber(currentPrice), quantity, investorLabel + candidate.getConsecutiveDays() + "일연속");

                    // 텔레그램 알림 — 손절/익절 표시는 실제 적용 레벨(ATR 세트면 스냅샷, 아니면 현행 -3/+5)
                    if (telegramService.isEnabled()) {
                        String modeTag = currentMode == TradingMode.REAL ? "실전" : "모의";
                        BigDecimal displayStop = newSwing.atrStopPct != null
                                ? newSwing.atrStopPct.setScale(2, RoundingMode.HALF_UP) : SWING_STOP_LOSS;
                        BigDecimal displayTarget = newSwing.atrTargetPct != null
                                ? newSwing.atrTargetPct.setScale(2, RoundingMode.HALF_UP) : SWING_TAKE_PROFIT;
                        telegramService.sendSignal(String.format(
                                "<b>📈 [%s] 스윙 진입</b>\n\n🎯 <b>%s</b> (%s)\n💰 %s원 x %d주\n\n📊 %s %d일 연속매수 (일평균 %s억)\n🔴 손절: %s%% | 🟢 익절: +%s%%\n⏰ 최대 보유: %d일\n\n%s MyPlatform %s",
                                modeTag, candidate.getStockName(), candidate.getStockCode(),
                                formatNumber(currentPrice), quantity,
                                investorLabel, candidate.getConsecutiveDays(),
                                candidate.getAvgDailyAmount().setScale(0, RoundingMode.HALF_UP),
                                displayStop, displayTarget, SWING_MAX_HOLD_DAYS,
                                currentMode == TradingMode.REAL ? "🔴" : "🟡", modeTag));
                    }

                    Thread.sleep(BUY_DELAY_VIRTUAL_MS);
                } catch (Exception e) {
                    log.error("[스윙봇] 매수 실패: {} - {}", candidate.getStockName(), e.getMessage());
                } finally {
                    // 매수 실패 시 메모리 등록 롤백 (스캘핑과 동일 패턴)
                    if (!swingBuyOk) {
                        swingPositions.remove(candidate.getStockCode());
                    }
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
                BigDecimal ma20Floor = indicators.getMa20().multiply(SWING_MA20_SUPPORT); // MA20 -3% 이내
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
    @Scheduled(cron = "*/30 * 8-19 * * MON-FRI", zone = "Asia/Seoul")
    public void executeSwingSellLogic() {
        if (!botLeader.isLeaderForBot()) return;   // 리더 게이트(fail-CLOSED)
        if (!swingSellRunning.compareAndSet(false, true)) {
            log.warn("[스윙봇] 매도 로직 이전 실행 아직 진행 중 — 이번 틱 스킵");
            return;
        }
        try {
            executeSwingSellLogicInternal();
        } finally {
            swingSellRunning.set(false);
        }
    }

    private void executeSwingSellLogicInternal() {
        // 킬스위치가 손절을 막지 않게(2026-08-05 감사 P0) — §4d 매도 fail-open.
        if (!shouldRunSellCycle(botActive.get(), killSwitchTriggered.get(), !swingPositions.isEmpty())) return;
        if (isMarketClosed()) return;

        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(PRE_MARKET_START) || now.isAfter(AFTER_MARKET_END)) return;

        try {
            // 조회 실패를 "보유 없음"으로 오인하면 아래에서 포지션을 삭제해 손절이 영영 안 나간다
            // (2026-08-05 감사 P0). 실패면 이번 사이클 보류 — 포지션은 그대로 둔다(§4c).
            Optional<List<PortfolioItemDto>> fetched = activeTradeService.tryGetPortfolio();
            if (fetched.isEmpty()) {
                log.warn("[매매봇] 잔고 조회 실패 — 이번 사이클 매도 판정 보류(포지션 유지)");
                return;
            }
            List<PortfolioItemDto> portfolios = fetched.get();
            Map<String, PortfolioItemDto> portfolioMap = portfolios.stream()
                    .collect(Collectors.toMap(PortfolioItemDto::getStockCode, p -> p, (a, b) -> a));

            for (SwingPosition position : new java.util.ArrayList<>(swingPositions.values())) {
                PortfolioItemDto portfolio = portfolioMap.get(position.stockCode);
                if (portfolio == null) {
                    swingPositions.remove(position.stockCode);
                    deletePersistedPosition(Strategy.SWING, position.stockCode);
                    continue;
                }

                StockPriceDto priceDto = stockPriceService.getStockPrice(position.stockCode);
                if (priceDto == null || priceDto.getCurrentPrice() == null) continue;

                BigDecimal currentPrice = priceDto.getCurrentPrice();
                // ★ 손절 평가 가격 신선도 검증(2026-08-05 감사) — 스캘핑엔 있던 가드가 여기엔 없어서
                //   최대 15분 묵은 캐시가로 손절을 판정했다(실제 -4% 인데 캐시가 -1% 면 손절 미발동).
                //   게다가 그 낡은 가격이 그대로 지정가가 되어 체결이 안 되고 미체결만 쌓인다.
                //   재조회 실패 시 보류 — 다음 사이클 재시도.
                if (isPriceStale(priceDto)) {
                    BigDecimal fresh = fetchLatestPriceQuiet(position.stockCode);
                    if (fresh != null && fresh.signum() > 0) {
                        currentPrice = fresh;
                    } else {
                        log.warn("[매매봇] 매도 평가 가격 stale + 재조회 실패 — 보류: {} (fetchedAt={})",
                                position.stockCode, priceDto.getFetchedAt());
                        continue;
                    }
                }
                position.updateHighPrice(currentPrice);

                BigDecimal profitRate = calcProfitRate(currentPrice, position.buyPrice);
                BigDecimal highDropRate = calcHighDropRate(currentPrice, position.highPrice);

                long holdDays = position.holdDays();
                String sellReason = null;

                // 손절/익절 레벨 — 현행 고정 -3/+5. ATR 세트 포지션(V42, 진입 스냅샷 有)만 동적 레벨.
                SwingExitLevels levels = resolveSwingExitLevels(
                        currentMode, position.atrStopPct, position.atrTargetPct);
                BigDecimal stopLevel = levels.stop();
                BigDecimal targetLevel = levels.target();

                // 1. 손절 (현행 -3% / ATR 세트: -k×ATR)
                if (profitRate.compareTo(stopLevel) <= 0) {
                    sellReason = "STOP_LOSS";
                    log.info("[스윙봇] 손절: {} 손익률 {}% (레벨 {}%)", position.stockName, profitRate, stopLevel);
                }
                // 2. 익절 (현행 +5% / ATR 세트: 손절폭×5/3)
                else if (profitRate.compareTo(targetLevel) >= 0) {
                    sellReason = "TAKE_PROFIT";
                    log.info("[스윙봇] 익절: {} 손익률 {}% (레벨 {}%)", position.stockName, profitRate, targetLevel);
                }
                // 3. 트레일링 (수익 +2% 이후, 고점 대비 -2%)
                else if (profitRate.compareTo(SWING_TRAILING_MIN_PROFIT) > 0
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
                    // in-flight 등록(2026-08-05 감사) — 스캘핑엔 있고 스윙/종가에만 없어서, 모드 전환
                    // (startBot)이 in-flight 대기 없이 activeTradeService 를 갈아치우면 진행 중이던
                    // 매도가 반대 계좌로 나갈 수 있었다(REAL↔VIRTUAL 교차 주문).
                    inFlightTrades.incrementAndGet();
                    // 봇이 산 만큼만 매도 — 수동 보유분이 봇 손절선에 청산되지 않게(2026-08-05 감사).
                    // 봇 수량 미상(구버전 복원분)이면 종전대로 계좌 수량.
                    int botSellQty = resolveSellQuantity(position.botQuantity, portfolio.getQuantity());
                    try {
                        if (botSellQty <= 0) {
                            log.warn("[매매봇] 매도 수량 0 — 스킵: {}", position.stockCode);
                            continue;
                        }
                        TradeHistoryDto sellDto = activeTradeService.sell(portfolio.getStockCode(), currentPrice,
                                botSellQty, sellReason);
                        lastTradeTime = LocalDateTime.now(clock);
                        todaySellCount.incrementAndGet();

                        BigDecimal profitLoss = currentPrice.subtract(position.buyPrice)
                                .multiply(BigDecimal.valueOf(botSellQty));

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

                        // B2-A: 부분/미체결 확정이면 포지션 유지(다음 사이클 재시도), 아니면 현행대로 제거
                        if (isSellConfirmedShort(position.stockCode, sellDto, portfolio.getQuantity())) {
                            log.warn("[스윙봇] 매도 부분/미체결 확정 — 포지션 유지, 재시도: {}", position.stockName);
                            if (telegramService.isEnabled()) {
                                try {
                                    telegramService.sendRisk(String.format(
                                            "⚠️ <b>[스윙봇] 매도 부분/미체결</b>\n\n종목: %s (%s)\n전량 체결되지 않아 포지션을 유지하고 재시도합니다.",
                                            position.stockName, position.stockCode));
                                } catch (Exception e) { log.warn("[스윙봇] 부분/미체결 경보 발송 실패 — 사람 개입 알림이 안 갔다: {}", e.toString()); }
                            }
                        } else {
                            swingPositions.remove(position.stockCode);
                            deletePersistedPosition(Strategy.SWING, position.stockCode);
                            sellCooldownMap.put(position.stockCode, LocalDateTime.now(clock));
                        }
                    } catch (Exception e) {
                        log.error("[스윙봇] 매도 실패: {} - {}", position.stockName, e.getMessage(), e);
                        if (telegramService.isEnabled()) {
                            try {
                                telegramService.sendRisk(String.format(
                                        "🚨 <b>[스윙봇] 매도 실패</b>\n\n종목: %s (%s)\n수량: %d주 @ %s원\n사유: %s\n에러: %s\n\n수동 확인 필요",
                                        position.stockName, position.stockCode,
                                        portfolio.getQuantity(), formatNumber(currentPrice), sellReason,
                                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                            } catch (Exception telegramErr) {
                    log.warn("[봇] 텔레그램 RISK 알람 전송 실패: {}", telegramErr.getMessage());
                }
                        }

                    } finally {
                        inFlightTrades.decrementAndGet();
                    }
                }
            }
        } catch (Exception e) {
            log.error("[스윙봇] 스윙 매도 로직 오류", e);
        }
    }

    // ==================== [전략 C] 종가 매수 — 비활성 ====================

    /** 종가 진입 창 파싱(HH:mm) — 잘못된 설정이면 안전 폴백(현행 15:15~15:20). 순수(테스트 대상). */
    static LocalTime parseTimeOr(String raw, LocalTime fallback) {
        try {
            return (raw == null || raw.isBlank()) ? fallback : LocalTime.parse(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 종가 진입 창 내부 여부 — 순수 함수(테스트 대상). start~end 포함 경계. */
    static boolean withinClosingEntryWindow(LocalTime now, LocalTime start, LocalTime end) {
        return now != null && !now.isBefore(start) && !now.isAfter(end);
    }

    private LocalTime closingEntryStart() { return parseTimeOr(closingEntryStartCfg, LocalTime.of(15, 15)); }
    private LocalTime closingEntryEnd() { return parseTimeOr(closingEntryEndCfg, LocalTime.of(15, 20)); }

    /**
     * 종가 매수 (비활성 — 재활성 대기). 2026-09-14 거래시간 연장 대비 <b>재설계 완료(2026-07-08)</b>.
     * 재활성화 = @Scheduled 주석 해제 한 줄(종가단일가 시각에 맞춰 cron + bot.closing.entry-* 설정 조정).
     *
     * <p><b>게이트 전량 배선(재설계)</b>: ① 리더(botLeader.isLeaderForBot, fail-CLOSED) ② isMarketClosed(15:30)
     * ③ 종가 진입 창(설정 이동 가능, 기본 15:15~15:20) ④ killswitch ⑤ 일일손실 브레이커(V38) ⑥ passesPriceSanity
     * (오염가 진입 차단) ⑦ BUY 멱등키(RealTradeService, KIS 직전). 스윙 매수와 동일 게이트 셋.
     *
     * <p><b>§16-2 진입 불변</b>: 종가 매수 진입은 <b>KRX 정규장(09:00~15:30) 내로만</b> — isMarketClosed()가
     * 하드 상한이라 NXT 진입은 구조적으로 불가. 매수는 {@code activeTradeService.buy}(세션 미지정=REGULAR 고정) —
     * <b>NXT 세션 진입 경로 없음</b>(방어 청산만 NXT 확장, 진입은 절대 확장 안 함).
     */
    // @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Seoul")   // 재활성 시 해제(종가단일가 시각 맞춰 조정)
    public void executeClosingBuyLogic() {
        if (!botLeader.isLeaderForBot()) return;   // ★ 리더 게이트(fail-CLOSED) — 재설계 배선(감사 #4) ★
        if (!botActive.get() || killSwitchTriggered.get()) return;
        // ★ §16-2: KRX 정규장(≤15:30) 하드 상한 — 진입은 KRX 만, NXT 진입 불가 ★
        if (isMarketClosed()) return;
        // ★ 종가 진입 창(설정 이동 가능, 기본 15:15~15:20) — KRX 정규장 내로만 유효(9/14 종가단일가 시각 이동 대응) ★
        if (!withinClosingEntryWindow(LocalTime.now(clock), closingEntryStart(), closingEntryEnd())) return;
        // 일일 손실률 평가 — 스윙과 동일하게 -3% 한도 검출 위해
        if (checkKillSwitch()) return;
        // ★ 일일 손실 서킷브레이커(V38) — 비활성 전략이지만 재활성화 대비 방어적 게이트 ★
        if (entryBlockedByDailyLossBreaker(currentMode == TradingMode.REAL, "종가매수")) return;
        // ★ 변동성 국면 게이트(V46) — 브레이커 형제 레이어. BLOCK=진입 취소 / REDUCE=사이즈 축소 ★
        final VolatilityRegimeService.Decision volGate = volRegimeEntryDecision("종가매수");
        if (volGate == VolatilityRegimeService.Decision.BLOCK) return;

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
            BigDecimal currentBalance = account.getCurrentBalance() != null
                    ? account.getCurrentBalance() : BigDecimal.ZERO;
            BigDecimal totalAsset = currentBalance.add(
                    account.getTotalEvaluation() != null ? account.getTotalEvaluation() : BigDecimal.ZERO);
            BigDecimal maxPerStock = totalAsset.multiply(CLOSING_INVESTMENT_RATIO);
            if (maxPerStock.compareTo(currentBalance) > 0) {
                maxPerStock = currentBalance;
            }
            // 변동성 REDUCED 모드 — 진입 캡 축소
            if (volGate == VolatilityRegimeService.Decision.REDUCE) {
                maxPerStock = maxPerStock.multiply(BigDecimal.valueOf(volRegimeReducedFactor()));
            }

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

                // ★ 가격 sanity 가드 — 전일 종가(J 소스) 대비 ±50% 초과(×10 오염 의심)면 진입 차단(재설계 배선) ★
                if (!passesPriceSanity(code, surge.getStockName(), currentPrice, "종가매수")) {
                    continue;
                }

                int quantity = maxPerStock.divide(currentPrice, 0, RoundingMode.DOWN).intValue();
                if (quantity <= 0) continue;

                // 매수 실행
                try {
                    BigDecimal fAmt = foreignBuy.get(code);
                    BigDecimal iAmt = instBuy.get(code);

                    // §16-2: 종가 진입은 REGULAR 고정(세션 미지정) — NXT 진입 경로 없음.
                    activeTradeService.buy(code, surge.getStockName(), currentPrice, quantity, "CLOSING_BUY");

                    SwingPosition newClosing = new SwingPosition(code, surge.getStockName(),
                            currentPrice, String.format("외국인%.0f억+기관%.0f억", fAmt, iAmt));
                    newClosing.botQuantity = quantity;   // 계좌 전량이 아닌 봇 수량만 매도(2026-08-05 감사)
                    closingPositions.put(code, newClosing);
                    try {
                        persistSwingPosition(newClosing, Strategy.CLOSING);
                    } catch (Exception persistEx) {
                        alertPersistFailure("종가매수", code, surge.getStockName(), persistEx);
                    }

                    lastTradeTime = LocalDateTime.now(clock);
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
                    Thread.sleep(BUY_DELAY_VIRTUAL_MS);
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
     * 종가 매수 포지션 감시 (비활성 — 재활성 대기). <b>재설계 완료(2026-07-08)</b>.
     *
     * <p><b>게이트 배선(재설계)</b>: ① 리더(botLeader.isLeaderForBot, fail-CLOSED) ② isMarketClosed(15:30)
     * ③ SELL in-flight 마커(V45)·killswitch — 이 둘은 {@code activeTradeService.sell} → RealTradeService.sell
     * 내부에서 통과(구 주석의 "SELL 멱등키 미도입"은 V45 로 해소). ④ 매도 확정(isSellConfirmedShort) 재시도.
     *
     * <p>매도는 {@code activeTradeService.sell}(4-arg=REGULAR) — 종가 포지션 청산도 KRX 정규장(≤15:30) 내에서만
     * (isMarketClosed 상한). NXT 창 잔여는 {@link #executeNxtLiquidationRetry()}(봇 소유 잔여 전량)가 별도로 커버.
     */
    // @Scheduled(cron = "*/30 * 8-19 * * MON-FRI", zone = "Asia/Seoul")   // 재활성 시 해제
    public void executeClosingSellLogic() {
        if (!botLeader.isLeaderForBot()) return;   // ★ 리더 게이트(fail-CLOSED) — 재설계 배선(감사 #4) ★
        if (!botActive.get() || closingPositions.isEmpty()) return;
        if (isMarketClosed()) return;

        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(PRE_MARKET_START) || now.isAfter(AFTER_MARKET_END)) return;

        try {
            // 조회 실패를 "보유 없음"으로 오인하면 아래에서 포지션을 삭제해 손절이 영영 안 나간다
            // (2026-08-05 감사 P0). 실패면 이번 사이클 보류 — 포지션은 그대로 둔다(§4c).
            Optional<List<PortfolioItemDto>> fetched = activeTradeService.tryGetPortfolio();
            if (fetched.isEmpty()) {
                log.warn("[매매봇] 잔고 조회 실패 — 이번 사이클 매도 판정 보류(포지션 유지)");
                return;
            }
            List<PortfolioItemDto> portfolios = fetched.get();
            Map<String, PortfolioItemDto> portfolioMap = portfolios.stream()
                    .collect(Collectors.toMap(PortfolioItemDto::getStockCode, p -> p, (a, b) -> a));

            for (SwingPosition position : new java.util.ArrayList<>(closingPositions.values())) {
                PortfolioItemDto portfolio = portfolioMap.get(position.stockCode);
                if (portfolio == null) {
                    closingPositions.remove(position.stockCode);
                    deletePersistedPosition(Strategy.CLOSING, position.stockCode);
                    continue;
                }

                StockPriceDto priceDto = stockPriceService.getStockPrice(position.stockCode);
                if (priceDto == null || priceDto.getCurrentPrice() == null) continue;

                BigDecimal currentPrice = priceDto.getCurrentPrice();
                // ★ 손절 평가 가격 신선도 검증(2026-08-05 감사) — 스캘핑엔 있던 가드가 여기엔 없어서
                //   최대 15분 묵은 캐시가로 손절을 판정했다(실제 -4% 인데 캐시가 -1% 면 손절 미발동).
                //   게다가 그 낡은 가격이 그대로 지정가가 되어 체결이 안 되고 미체결만 쌓인다.
                //   재조회 실패 시 보류 — 다음 사이클 재시도.
                if (isPriceStale(priceDto)) {
                    BigDecimal fresh = fetchLatestPriceQuiet(position.stockCode);
                    if (fresh != null && fresh.signum() > 0) {
                        currentPrice = fresh;
                    } else {
                        log.warn("[매매봇] 매도 평가 가격 stale + 재조회 실패 — 보류: {} (fetchedAt={})",
                                position.stockCode, priceDto.getFetchedAt());
                        continue;
                    }
                }
                position.updateHighPrice(currentPrice);

                BigDecimal profitRate = calcProfitRate(currentPrice, position.buyPrice);
                BigDecimal highDropRate = calcHighDropRate(currentPrice, position.highPrice);

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
                // 3. 트레일링 (수익 +1% 이후, 고점 대비 -1%)
                else if (profitRate.compareTo(CLOSING_TRAILING_MIN_PROFIT) > 0
                        && highDropRate.compareTo(CLOSING_TRAILING_STOP) <= 0) {
                    sellReason = "TRAILING_STOP";
                }
                // 4. 시가 갭하락 즉시 청산: 익일 09:05 이후, 수익률 -1% 이하 → 대외변수 급락 방어
                else if (holdDays >= 1
                        && LocalTime.now(clock).isAfter(CLOSING_GAP_DOWN_CHECK_TIME)
                        && LocalTime.now(clock).isBefore(CLOSING_EARLY_EXIT_TIME)
                        && profitRate.compareTo(CLOSING_GAP_DOWN_LIMIT) <= 0) {
                    sellReason = "GAP_DOWN_EXIT";
                    log.info("[종가매수] 시가 갭하락 즉시청산: {} 손익률 {}% (시가 -1% 이하)",
                            position.stockName, profitRate);
                }
                // 5. 갭업 미발생 조기 청산: 익일 10시까지 +0.5% 미달 시 손실 줄이며 청산
                else if (holdDays >= 1
                        && LocalTime.now(clock).isAfter(CLOSING_EARLY_EXIT_TIME)
                        && profitRate.compareTo(CLOSING_EARLY_EXIT_MIN_PROFIT) < 0) {
                    sellReason = "EARLY_EXIT";
                    log.info("[종가매수] 갭업 미발생 조기청산: {} 손익률 {}% (기준 {}% 미달)",
                            position.stockName, profitRate, CLOSING_EARLY_EXIT_MIN_PROFIT);
                }
                // 5. 일수 타임컷 (2일 초과)
                else if (holdDays >= CLOSING_MAX_HOLD_DAYS) {
                    sellReason = "TIME_CUT";
                }

                if (sellReason != null) {
                    // in-flight 등록(2026-08-05 감사) — 스캘핑엔 있고 스윙/종가에만 없어서, 모드 전환
                    // (startBot)이 in-flight 대기 없이 activeTradeService 를 갈아치우면 진행 중이던
                    // 매도가 반대 계좌로 나갈 수 있었다(REAL↔VIRTUAL 교차 주문).
                    inFlightTrades.incrementAndGet();
                    // 봇이 산 만큼만 매도 — 수동 보유분이 봇 손절선에 청산되지 않게(2026-08-05 감사).
                    // 봇 수량 미상(구버전 복원분)이면 종전대로 계좌 수량.
                    int botSellQty = resolveSellQuantity(position.botQuantity, portfolio.getQuantity());
                    try {
                        if (botSellQty <= 0) {
                            log.warn("[매매봇] 매도 수량 0 — 스킵: {}", position.stockCode);
                            continue;
                        }
                        TradeHistoryDto sellDto = activeTradeService.sell(portfolio.getStockCode(), currentPrice,
                                botSellQty, sellReason);
                        lastTradeTime = LocalDateTime.now(clock);
                        todaySellCount.incrementAndGet();

                        BigDecimal profitLoss = currentPrice.subtract(position.buyPrice)
                                .multiply(BigDecimal.valueOf(botSellQty));

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

                        // B2-A: 부분/미체결 확정이면 포지션 유지(다음 사이클 재시도), 아니면 현행대로 제거
                        if (isSellConfirmedShort(position.stockCode, sellDto, portfolio.getQuantity())) {
                            log.warn("[종가매수] 매도 부분/미체결 확정 — 포지션 유지, 재시도: {}", position.stockName);
                        } else {
                            closingPositions.remove(position.stockCode);
                            deletePersistedPosition(Strategy.CLOSING, position.stockCode);
                            sellCooldownMap.put(position.stockCode, LocalDateTime.now(clock));
                        }
                    } catch (Exception e) {
                        log.error("[종가매수] 매도 실패: {} - {}", position.stockName, e.getMessage());

                    } finally {
                        inFlightTrades.decrementAndGet();
                    }
                }
            }
        } catch (Exception e) {
            log.error("[종가매수] 매도 로직 오류", e);
        }
    }

    // ==================== 킬 스위치 ====================

    /**
     * 킬 스위치 체크 (이중 구조)
     * - 스캘핑 킬스위치: -1.5% → 스캘핑만 중지 (스윙/종가 유지)
     * - 전체 킬스위치: -3.0% → 봇 전체 종료
     */
    private boolean checkKillSwitch() {
        if (killSwitchTriggered.get()) {
            return true;
        }

        try {
            AccountSummaryDto account = activeTradeService.getAccountSummary();
            BigDecimal currentAsset = account.getCurrentBalance().add(
                    account.getTotalEvaluation() != null ? account.getTotalEvaluation() : BigDecimal.ZERO);

            // 자산 0원 = REAL 잔고 조회 실패 폴백(빈 요약 DTO) 가능성 — -100% 손실로 오판해
            // 총 킬스위치를 쏘면 손절 모니터링·강제청산까지 전부 꺼진다(§4c: 결측을 실값으로 위장 금지).
            // 예외 경로와 동일하게 fail-open + 스로틀 알림으로 처리.
            if (currentAsset.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[매매봇] 킬스위치 체크 보류 — 계좌 자산 0원(잔고 조회 실패 폴백 의심)");
                notifyKillSwitchCheckFailure(new IllegalStateException("계좌 자산 조회값 0원 — 잔고 조회 실패 폴백 의심"));
                return false;
            }

            if (dailyStartAsset.compareTo(BigDecimal.ZERO) <= 0) {
                dailyStartAsset = currentAsset;
                return false;
            }

            BigDecimal dailyProfitRate = currentAsset.subtract(dailyStartAsset)
                    .divide(dailyStartAsset, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

            // 전체 킬스위치: -3% → 봇 완전 종료
            if (dailyProfitRate.compareTo(KILL_SWITCH_TOTAL_RATE) <= 0) {
                killSwitchTriggered.set(true);
                killSwitchDate = LocalDate.now(clock);   // 당일 재개 차단용(2026-08-05 감사)
                botActive.set(false);
                saveBotState(STATUS_STOPPED, currentMode);

                log.warn("[매매봇] 🛑 전체 킬스위치 발동! 일일 손실: {}%", dailyProfitRate);

                if (telegramService.isEnabled()) {
                    String modeEmoji = currentMode == TradingMode.REAL ? "🔴" : "🤖";
                    String modeTag = currentMode == TradingMode.REAL ? "실전투자" : "모의투자";

                    telegramService.sendRisk(
                            String.format("<b>🛑 [%s] 전체 킬스위치 발동!</b>\n\n", modeTag) +
                            "⚠️ 일일 최대 손실 한도(-3%) 초과로 봇을 자동 종료합니다.\n\n" +
                            "📊 시작 자산: " + formatNumber(dailyStartAsset) + "원\n" +
                            "📊 현재 자산: " + formatNumber(currentAsset) + "원\n" +
                            "📉 일일 손익: " + String.format("%.2f", dailyProfitRate) + "%\n\n" +
                            "━━━━━━━━━━━━━━━━\n" +
                            modeEmoji + " MyPlatform " + modeTag
                    );
                }

                return true;
            }

            // 스캘핑 킬스위치: -1.5% → 스캘핑만 중지 (스윙/종가는 계속)
            if (!scalpingKillSwitchTriggered.get()
                    && dailyProfitRate.compareTo(KILL_SWITCH_SCALPING_RATE) <= 0) {
                scalpingKillSwitchTriggered.set(true);

                log.warn("[매매봇] ⚠️ 스캘핑 킬스위치 발동! 일일 손실: {}% (스윙/종가 유지)", dailyProfitRate);

                if (telegramService.isEnabled()) {
                    telegramService.sendRisk(String.format(
                            "<b>⚠️ 스캘핑 킬스위치 발동</b>\n일일 손실 %s%% → 스캘핑 신규 매수 중지\n스윙/종가매수 전략은 정상 운영",
                            String.format("%.2f", dailyProfitRate)));
                }
            }

        } catch (Exception e) {
            log.error("[매매봇] 킬 스위치 체크 오류: {}", e.getMessage());
            // fail-open(미차단) 유지 — 일시 조회 실패로 봇 전체를 세우지 않는다. 단 손실 차단기(-3%/-1.5%)가
            // "조용히" 꺼진 상태를 운영자가 모르면 안 됨 → RISK 알림(10분 스로틀 — 분 단위 크론 스팸 방지).
            notifyKillSwitchCheckFailure(e);
        }

        return false;
    }

    /** 킬스위치 체크 실패 RISK 알림 스로틀 타임스탬프 — 10분당 1회만. */
    private volatile LocalDateTime killSwitchCheckFailAlertAt = null;

    /** 킬스위치 체크 실패를 운영자에게 알림(10분 스로틀) — 안전가드의 조용한 무력화 방지. */
    private void notifyKillSwitchCheckFailure(Exception e) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (!telegramService.isEnabled()) return;
        if (killSwitchCheckFailAlertAt != null && killSwitchCheckFailAlertAt.plusMinutes(10).isAfter(now)) return;
        killSwitchCheckFailAlertAt = now;
        try {
            telegramService.sendRisk("⚠️ <b>[봇] 킬스위치 체크 실패</b>\n계좌 조회 오류로 일일 손실 차단(-3%/-1.5%)이 "
                    + "일시 무력화 상태입니다(매매는 계속). 반복되면 수동 점검 필요.\n오류: " + e.getMessage());
        } catch (Exception ignore) { /* 알림 실패는 무시 — 체크 흐름 유지 */ }
    }

    /** 스캘핑 전용 킬스위치 (스윙/종가는 허용) */
    private boolean isScalpingBlocked() {
        return killSwitchTriggered.get() || scalpingKillSwitchTriggered.get();
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
            return analysis.getVolumePower().compareTo(VOLUME_INCREASING_THRESHOLD) >= 0;
        } catch (Exception e) {
            log.debug("[스캘핑봇] 거래량 추세 확인 실패 [{}]: {}", stockCode, e.getMessage());
            return false;
        }
    }

    // ==================== 유틸리티 ====================

    /** 수익률 계산 (%) */
    private BigDecimal calcProfitRate(BigDecimal currentPrice, BigDecimal buyPrice) {
        return currentPrice.subtract(buyPrice)
                .divide(buyPrice, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    /** 고점 대비 하락률 계산 (%) */
    private BigDecimal calcHighDropRate(BigDecimal currentPrice, BigDecimal highPrice) {
        return currentPrice.subtract(highPrice)
                .divide(highPrice, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

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
        LocalDate today = LocalDate.now(clock);
        if (lastResetDate == null || !lastResetDate.equals(today)) {
            todayBuyCount.set(0);
            todaySellCount.set(0);
            killSwitchTriggered.set(false);
            scalpingKillSwitchTriggered.set(false);
            consecutiveStopLossCount.set(0);
            consecutiveStopLossPaused.set(false);
            kospiDropPaused.set(false);
            scalpingPositions.clear();
            sellCooldownMap.clear();
            outflowSectorStocks.set(ConcurrentHashMap.newKeySet());
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
        message.append("⏰ ").append(LocalTime.now(clock).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))).append("\n\n");

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

    /** KRX 정규장 시간 (한국시간 기준). 09:00:00 이전 또는 15:30:00 이후는 장 외. */
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private boolean isMarketClosed() {
        LocalDate today = LocalDate.now(clock);
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

        // 정규장 시간 체크 (09:00 ~ 15:30 KST)
        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
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
