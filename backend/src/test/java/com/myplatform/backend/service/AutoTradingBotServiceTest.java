package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.InvestorSurgeDto;
import com.myplatform.backend.dto.PaperTradingDto.AccountSummaryDto;
import com.myplatform.backend.dto.PaperTradingDto.BotStatusDto;
import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.dto.PaperTradingDto.TradeHistoryDto;
import com.myplatform.backend.dto.ScalpingAnalysisDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.dto.TechnicalIndicatorsDto;
import com.myplatform.backend.entity.BotConfig;
import com.myplatform.backend.entity.BotTradingPosition;
import com.myplatform.backend.repository.BotConfigRepository;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.VirtualPortfolioRepository;
import com.myplatform.backend.service.AutoTradingBotService.TradingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AutoTradingBotService 단위 테스트 — 핵심 상태 전환 / concurrency guard.
 *
 * 검증 시나리오 (테스트 가능한 5개):
 *  1. startBot() → botActive=true + DB 저장 + 텔레그램 알림
 *  2. stopBot() → botActive=false + DB 저장
 *  3. 모드 전환 (VIRTUAL → REAL) → 포지션 정리
 *  4. 이미 실행 중일 때 startBot() 재호출 → 멱등 (재초기화 X)
 *  5. AtomicBoolean concurrency flag — 동시 실행 차단
 *
 * 스킵된 시나리오 (서비스 리팩토링 없이 단위 테스트 불가):
 *  - "Kill switch ON 상태 → 신호 무시": checkKillSwitch() 가 private + LocalTime.now() 직접 호출.
 *    @Scheduled executeScalpingBuyLogic() 가 시장 시간 체크 (isMarketClosed) 를 LocalTime.now()
 *    실제 시계 기반으로 하므로 테스트 환경에서 동작이 시간대에 의존. 통합 테스트로 분리 필요.
 *  - "Scalping buy 신호 → 매수 진행": 200+ 라인의 진입 조건(수급/RSI/이격도/체결강도/공매도/섹터)을
 *    모두 mock 하면 사실상 통합 테스트가 되어 단위 범위 초과. 별도 PR 권장.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AutoTradingBotServiceTest {

    @Mock private VirtualTradeService virtualTradeService;
    @Mock private RealTradeService realTradeService;
    @Mock private VirtualPortfolioRepository portfolioRepository;
    @Mock private InvestorSurgeService investorSurgeService;
    @Mock private ScalpingAnalysisService scalpingAnalysisService;
    @Mock private StockPriceService stockPriceService;
    @Mock private TelegramNotificationService telegramService;
    @Mock private BotConfigRepository botConfigRepository;
    @Mock private TechnicalIndicatorService technicalIndicatorService;
    @Mock private KoreaInvestmentService kisService;
    @Mock private GlobalFuturesService globalFuturesService;
    @Mock private SectorTradingService sectorTradingService;
    @Mock private ShortSellingService shortSellingService;
    @Mock private StockStatusService stockStatusService;
    @Mock private InvestorTradeService investorTradeService;
    @Mock private GlobalMarketService globalMarketService;
    @Mock private BotTradingPositionRepository positionRepository;
    // RealtimePriceBus 는 @ConditionalOnProperty 로 등록되어 단위 테스트에서는 빈 자체가 없음.
    // ObjectProvider 모킹 → getIfAvailable() = null 반환하도록 BeforeEach 에서 설정.
    @Mock private org.springframework.beans.factory.ObjectProvider<RealtimePriceBus> realtimePriceBusProvider;

    private AutoTradingBotService botService;

    // 고정 시계 — KST 평일 정규장 (2026-05-11 월요일 10:00 KST). 기본 테스트는 모두 "시장 열림" 상태.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private Clock fixedClock;

    /** 테스트별로 다른 시각이 필요할 때 botService 를 새 Clock 으로 재생성. */
    private AutoTradingBotService rebuildBotWithClock(Clock clock) {
        return new AutoTradingBotService(
                virtualTradeService,
                realTradeService,
                portfolioRepository,
                investorSurgeService,
                scalpingAnalysisService,
                stockPriceService,
                telegramService,
                botConfigRepository,
                technicalIndicatorService,
                kisService,
                globalFuturesService,
                sectorTradingService,
                shortSellingService,
                stockStatusService,
                investorTradeService,
                globalMarketService,
                positionRepository,
                realtimePriceBusProvider,
                clock,
                // 리더 선출 비활성(enabled=false) → isLeaderForBot()=true 항상 통과 → 기존 봇 동작 보존
                new BotLeaderElectionService(null, false, 30L, "test"));
    }

    @BeforeEach
    void setUp() {
        // 텔레그램 비활성 default — 알림 무시
        when(telegramService.isEnabled()).thenReturn(false);

        // WebSocket 빈 미등록 시나리오 — getIfAvailable() = null
        when(realtimePriceBusProvider.getIfAvailable()).thenReturn(null);

        // 빈 포트폴리오 + 0원 계좌 default
        AccountSummaryDto emptyAccount = AccountSummaryDto.builder()
                .accountId(1L)
                .currentBalance(BigDecimal.ZERO)
                .totalEvaluation(BigDecimal.ZERO)
                .build();
        when(virtualTradeService.getAccountSummary()).thenReturn(emptyAccount);
        when(realTradeService.getAccountSummary()).thenReturn(emptyAccount);
        when(virtualTradeService.getPortfolio()).thenReturn(new ArrayList<>());
        when(realTradeService.getPortfolio()).thenReturn(new ArrayList<>());

        // BotConfig DB save no-op
        when(botConfigRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        when(botConfigRepository.save(any(BotConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        // positionRepository defaults
        when(positionRepository.findByTradingMode(anyString())).thenReturn(Collections.emptyList());
        when(positionRepository.count()).thenReturn(0L);

        // 평일 월요일 10:00 KST — 정규장 시간 + 영업일.
        Instant marketOpen = ZonedDateTime.of(2026, 5, 11, 10, 0, 0, 0, KST).toInstant();
        fixedClock = Clock.fixed(marketOpen, KST);

        botService = rebuildBotWithClock(fixedClock);
    }

    // ================================================================
    // 봇 시작/중지
    // ================================================================

    @Nested
    @DisplayName("startBot / stopBot — 상태 전환")
    class StartStopTests {

        @Test
        @DisplayName("startBot(VIRTUAL) → active=true + 모의 모드 + DB 저장")
        void startBot_virtual_activatesAndPersists() {
            BotStatusDto status = botService.startBot(TradingMode.VIRTUAL);

            assertThat(status).isNotNull();
            assertThat(status.getActive()).isTrue();
            assertThat(status.getTradingMode()).isEqualTo("VIRTUAL");

            // DB 저장 확인
            verify(botConfigRepository, atLeastOnce()).save(any(BotConfig.class));
            assertThat(botService.getCurrentMode()).isEqualTo(TradingMode.VIRTUAL);
        }

        @Test
        @DisplayName("startBot(REAL) → 실전 모드 활성")
        void startBot_real_activatesRealMode() {
            BotStatusDto status = botService.startBot(TradingMode.REAL);

            assertThat(status.getActive()).isTrue();
            assertThat(status.getTradingMode()).isEqualTo("REAL");
            assertThat(botService.getCurrentMode()).isEqualTo(TradingMode.REAL);
        }

        @Test
        @DisplayName("stopBot() → active=false + DB 저장")
        void stopBot_deactivates() {
            botService.startBot(TradingMode.VIRTUAL);

            BotStatusDto status = botService.stopBot();

            assertThat(status.getActive()).isFalse();
            assertThat(status.getStatus()).isEqualTo("STOPPED");
            // start + stop 두 번 저장
            verify(botConfigRepository, atLeastOnce()).save(any(BotConfig.class));
        }

        @Test
        @DisplayName("이미 실행 중일 때 startBot() 재호출 → 멱등 (재초기화 X)")
        void startBot_alreadyRunning_isIdempotent() {
            botService.startBot(TradingMode.VIRTUAL);

            // 두 번째 start 는 같은 상태 반환
            BotStatusDto status = botService.startBot(TradingMode.VIRTUAL);

            assertThat(status.getActive()).isTrue();
            assertThat(botService.getCurrentMode()).isEqualTo(TradingMode.VIRTUAL);
        }

        @Test
        @DisplayName("이미 중지 상태에서 stopBot() → 멱등 (DB 변동 없음)")
        void stopBot_alreadyStopped_isIdempotent() {
            // 시작 안 한 상태에서 stop
            BotStatusDto status = botService.stopBot();

            assertThat(status.getActive()).isFalse();
            // 한 번도 시작 안 했으면 stopBot 도 추가 DB save 안 함 (early return)
            verify(botConfigRepository, never()).save(any(BotConfig.class));
        }
    }

    // ================================================================
    // 모드 전환 — 포지션 정리
    // ================================================================

    @Nested
    @DisplayName("모드 전환 — 포지션 격리")
    class ModeSwitchTests {

        @Test
        @DisplayName("VIRTUAL → REAL 전환 시 DB 포지션 deleteAll 호출 (정지 후 재시작 경로)")
        void modeSwitch_virtualToReal_clearsPositions() {
            // 1) VIRTUAL 시작 후 stop — startBot() 재진입 가능하게.
            //    (이미 실행 중이면 startBot() 가 early return 으로 모드 전환 로직 미실행)
            botService.startBot(TradingMode.VIRTUAL);
            botService.stopBot();

            // 2) 가짜 포지션 주입 — cleared > 0 분기로 진입시키기 위함
            injectFakeScalpingPosition();

            // 3) REAL 로 재시작 → currentMode(VIRTUAL) != newMode(REAL) → 포지션 정리
            BotStatusDto status = botService.startBot(TradingMode.REAL);
            assertThat(status).isNotNull();

            // ★ 포지션 in-memory cleared + DB deleteAll 호출 확인
            verify(positionRepository, atLeastOnce()).deleteAll();
            assertThat(botService.getCurrentMode()).isEqualTo(TradingMode.REAL);
        }

        @Test
        @DisplayName("같은 모드로 startBot() 재호출 → deleteAll 안 함")
        void sameMode_doesNotClearPositions() {
            botService.startBot(TradingMode.VIRTUAL);
            // 같은 VIRTUAL 모드 재호출 → 이미 실행 중이라 early return
            botService.startBot(TradingMode.VIRTUAL);

            verify(positionRepository, never()).deleteAll();
        }

        /**
         * reflection 으로 in-memory 포지션 맵에 가짜 entry 1개 주입.
         * 이렇게 해야 startBot() 의 cleared > 0 분기가 발동 → deleteAll() 호출 확인 가능.
         */
        private void injectFakeScalpingPosition() {
            try {
                Field f = AutoTradingBotService.class.getDeclaredField("scalpingPositions");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) f.get(botService);

                // ScalpingPosition 는 private static — reflection 으로 생성
                Class<?> spClass = Class.forName(
                        "com.myplatform.backend.service.AutoTradingBotService$ScalpingPosition");
                var ctor = spClass.getDeclaredConstructor(String.class, String.class, BigDecimal.class, int.class);
                ctor.setAccessible(true);
                Object fake = ctor.newInstance("005930", "삼성전자", new BigDecimal("70000"), 10);
                map.put("005930", fake);
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject fake position", e);
            }
        }
    }

    // ================================================================
    // Concurrency — AtomicBoolean 가드
    // ================================================================

    @Nested
    @DisplayName("AtomicBoolean concurrency flag — 동시 실행 차단")
    class ConcurrencyTests {

        @Test
        @DisplayName("executeScalpingBuyLogic — 이전 실행 진행 중이면 다음 틱 스킵")
        void scalpingBuy_concurrentTick_isSkipped() throws Exception {
            // scalpingBuyRunning 을 true 로 강제 set → 다음 호출은 즉시 early return
            Field f = AutoTradingBotService.class.getDeclaredField("scalpingBuyRunning");
            f.setAccessible(true);
            AtomicBoolean flag = (AtomicBoolean) f.get(botService);
            flag.set(true);

            // 봇이 active 라도 flag 가 true 면 internal 진입 안 함
            botService.startBot(TradingMode.VIRTUAL);
            // call
            botService.executeScalpingBuyLogic();

            // surgeStocks 조회 (internal 진입 마커) 가 일어나지 않았어야 함
            verify(investorSurgeService, never()).getAllSurgeStocks(any());

            // flag 는 여전히 true (호출이 set(false) 하지 않음 — 우리가 setup 한 것 유지)
            assertThat(flag.get()).isTrue();
        }

        @Test
        @DisplayName("executeScalpingSellLogic — flag 가드: 첫 호출은 정상, 동시 두 번째는 스킵")
        void scalpingSell_concurrentTick_isSkipped() throws Exception {
            // scalpingSellRunning 을 true 로 강제 set
            Field f = AutoTradingBotService.class.getDeclaredField("scalpingSellRunning");
            f.setAccessible(true);
            AtomicBoolean flag = (AtomicBoolean) f.get(botService);
            flag.set(true);

            botService.startBot(TradingMode.VIRTUAL);
            botService.executeScalpingSellLogic();

            // 내부 진입 마커: getPortfolio 가 호출되지 않았어야 함
            verify(virtualTradeService, never()).getPortfolio();
        }

        @Test
        @DisplayName("봇 비활성 상태에서 executeScalpingBuyLogic → 즉시 종료, flag false 로 복귀")
        void scalpingBuy_botInactive_earlyReturn() throws Exception {
            // 봇 시작 안 함
            botService.executeScalpingBuyLogic();

            // flag 가 false 로 set 됨 (try-finally 보장)
            Field f = AutoTradingBotService.class.getDeclaredField("scalpingBuyRunning");
            f.setAccessible(true);
            AtomicBoolean flag = (AtomicBoolean) f.get(botService);
            assertThat(flag.get()).isFalse();

            // 매매 호출 X
            verify(virtualTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
            verify(realTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
        }
    }

    // ================================================================
    // getBotStatus 상태 매핑
    // ================================================================

    @Nested
    @DisplayName("getBotStatus — 상태 매핑")
    class BotStatusTests {

        @Test
        @DisplayName("초기 상태 → STOPPED")
        void initial_stopped() {
            BotStatusDto status = botService.getBotStatus();
            assertThat(status.getActive()).isFalse();
            assertThat(status.getStatus()).isEqualTo("STOPPED");
        }

        @Test
        @DisplayName("startBot 후 → RUNNING 또는 ERROR (lastError 없으면 RUNNING)")
        void afterStart_running() {
            botService.startBot(TradingMode.VIRTUAL);
            BotStatusDto status = botService.getBotStatus();

            assertThat(status.getActive()).isTrue();
            // 에러/킬스위치 없으면 RUNNING
            assertThat(status.getStatus()).isIn("RUNNING", "VIX_PAUSED", "KOSPI_DROP_PAUSED");
        }

        @Test
        @DisplayName("kill switch ON → KILL_SWITCH 상태 반환")
        void killSwitchOn_reportsKillSwitch() throws Exception {
            // resetDailyCounters() 가 lastResetDate==null 일 때 killSwitchTriggered=false 로 reset 하므로
            // lastResetDate 를 fixedClock 기준 오늘로 먼저 set 해서 reset 방지
            Field lastReset = AutoTradingBotService.class.getDeclaredField("lastResetDate");
            lastReset.setAccessible(true);
            lastReset.set(botService, LocalDate.now(fixedClock));

            // killSwitchTriggered 를 true 로 강제 set
            Field f = AutoTradingBotService.class.getDeclaredField("killSwitchTriggered");
            f.setAccessible(true);
            AtomicBoolean killFlag = (AtomicBoolean) f.get(botService);
            killFlag.set(true);

            BotStatusDto status = botService.getBotStatus();
            assertThat(status.getStatus()).isEqualTo("KILL_SWITCH");
        }
    }

    // ================================================================
    // KillSwitch / Market Hours — Clock 주입 기반 결정론적 검증
    // ================================================================

    @Nested
    @DisplayName("KillSwitch / isMarketClosed — 시간 의존 로직 (Clock 주입)")
    class KillSwitchTests {

        /** private 메서드를 reflection 으로 호출. */
        private boolean invokeCheckKillSwitch(AutoTradingBotService svc) throws Exception {
            Method m = AutoTradingBotService.class.getDeclaredMethod("checkKillSwitch");
            m.setAccessible(true);
            return (boolean) m.invoke(svc);
        }

        private boolean invokeIsMarketClosed(AutoTradingBotService svc) throws Exception {
            Method m = AutoTradingBotService.class.getDeclaredMethod("isMarketClosed");
            m.setAccessible(true);
            return (boolean) m.invoke(svc);
        }

        /** 0원 계좌(dailyStartAsset==0 + currentAsset==0) → dailyStartAsset 초기화 후 false 반환. */
        @Test
        @DisplayName("checkKillSwitch — 정상 자산(0원, 미발동) 평일 10시 → false")
        void killSwitch_marketOpen_normalDay_returnsFalse() throws Exception {
            // setUp 의 fixedClock = 2026-05-11(월) 10:00 KST. 평일 정규장.
            // 빈 계좌 → dailyStartAsset == 0 분기로 진입, false 반환.
            boolean result = invokeCheckKillSwitch(botService);

            assertThat(result).isFalse();
        }

        /** 계좌조회 예외 → fail-open(false, 봇 유지)이되 RISK 알림으로 "차단기 꺼짐"을 노출 + 10분 스로틀. */
        @Test
        @DisplayName("checkKillSwitch — 계좌조회 예외 시 fail-open(false) + RISK 알림 1회(스로틀, 조용한 무력화 방지)")
        void killSwitch_checkFailure_failOpenWithAlert() throws Exception {
            when(virtualTradeService.getAccountSummary()).thenThrow(new RuntimeException("KIS 잔고 조회 실패"));
            when(telegramService.isEnabled()).thenReturn(true);

            assertThat(invokeCheckKillSwitch(botService)).isFalse();   // fail-open 유지(미차단)
            assertThat(invokeCheckKillSwitch(botService)).isFalse();   // 같은 시각(fixedClock) 재호출

            org.mockito.Mockito.verify(telegramService, org.mockito.Mockito.times(1))
                    .sendRisk(org.mockito.ArgumentMatchers.contains("킬스위치 체크 실패"));  // 알림 1회(10분 스로틀)
        }

        /** 이미 killSwitchTriggered=true 면 시각과 무관하게 true 반환. */
        @Test
        @DisplayName("checkKillSwitch — killSwitchTriggered=true 이면 true 즉시 반환")
        void killSwitch_alreadyTriggered_returnsTrue() throws Exception {
            Field f = AutoTradingBotService.class.getDeclaredField("killSwitchTriggered");
            f.setAccessible(true);
            ((AtomicBoolean) f.get(botService)).set(true);

            boolean result = invokeCheckKillSwitch(botService);

            assertThat(result).isTrue();
        }

        /** 평일 월요일 10:00 KST → 시장 열림. */
        @Test
        @DisplayName("isMarketClosed — 평일 월요일 10:00 KST → false (시장 열림)")
        void marketClosed_weekdayDuringHours_returnsFalse() throws Exception {
            boolean closed = invokeIsMarketClosed(botService);

            assertThat(closed).isFalse();
        }

        /** 평일 16:30 KST (장 마감 후) → 시장 닫힘. */
        @Test
        @DisplayName("isMarketClosed — 평일 16:30 KST (장 마감 후) → true")
        void marketClosed_weekdayAfterClose_returnsTrue() throws Exception {
            Instant afterClose = ZonedDateTime.of(2026, 5, 11, 16, 30, 0, 0, KST).toInstant();
            AutoTradingBotService svc = rebuildBotWithClock(Clock.fixed(afterClose, KST));

            boolean closed = invokeIsMarketClosed(svc);

            assertThat(closed).isTrue();
        }

        /** 평일 08:30 KST (장 시작 전) → 시장 닫힘. */
        @Test
        @DisplayName("isMarketClosed — 평일 08:30 KST (장 시작 전) → true")
        void marketClosed_weekdayBeforeOpen_returnsTrue() throws Exception {
            Instant beforeOpen = ZonedDateTime.of(2026, 5, 11, 8, 30, 0, 0, KST).toInstant();
            AutoTradingBotService svc = rebuildBotWithClock(Clock.fixed(beforeOpen, KST));

            boolean closed = invokeIsMarketClosed(svc);

            assertThat(closed).isTrue();
        }

        /** 토요일 10:00 KST → 시장 닫힘. (2026-05-09 = 토요일) */
        @Test
        @DisplayName("isMarketClosed — 토요일 10:00 KST → true (주말)")
        void marketClosed_saturday_returnsTrue() throws Exception {
            Instant saturday = ZonedDateTime.of(2026, 5, 9, 10, 0, 0, 0, KST).toInstant();
            AutoTradingBotService svc = rebuildBotWithClock(Clock.fixed(saturday, KST));

            boolean closed = invokeIsMarketClosed(svc);

            assertThat(closed).isTrue();
        }

        /** 일요일 10:00 KST → 시장 닫힘. (2026-05-10 = 일요일) */
        @Test
        @DisplayName("isMarketClosed — 일요일 10:00 KST → true (주말)")
        void marketClosed_sunday_returnsTrue() throws Exception {
            Instant sunday = ZonedDateTime.of(2026, 5, 10, 10, 0, 0, 0, KST).toInstant();
            AutoTradingBotService svc = rebuildBotWithClock(Clock.fixed(sunday, KST));

            boolean closed = invokeIsMarketClosed(svc);

            assertThat(closed).isTrue();
        }

        /** 2026-05-24 부처님오신날(공휴일) 10:00 KST → 시장 닫힘. */
        @Test
        @DisplayName("isMarketClosed — 공휴일(2026-05-24 부처님오신날) → true")
        void marketClosed_holiday_returnsTrue() throws Exception {
            Instant holiday = ZonedDateTime.of(2026, 5, 24, 10, 0, 0, 0, KST).toInstant();
            AutoTradingBotService svc = rebuildBotWithClock(Clock.fixed(holiday, KST));

            boolean closed = invokeIsMarketClosed(svc);

            assertThat(closed).isTrue();
        }
    }

    // ================================================================
    // executeScalpingBuyLogicInternal — 전체 매수 흐름 (gate 통과/거절)
    // ================================================================

    @Nested
    @DisplayName("executeScalpingBuyLogicInternal — gate 통합 시나리오")
    class ScalpingBuyTests {

        private static final String STOCK_CODE = "005930";
        private static final String STOCK_NAME = "삼성전자";

        /**
         * 모든 gate 가 green 인 happy-path 의 기본 stub 세팅.
         * 개별 테스트에서 한 gate 만 빨강으로 뒤집어 reject 시나리오 검증.
         *
         * 가정:
         *  - VIRTUAL 모드 (서비스가 REAL 일 때 스캘핑 skip 이므로 happy path 는 VIRTUAL 필수)
         *  - 평일 10:00 KST (setUp 의 fixedClock — 골든타임 09:10~15:00 안)
         *  - 단일 종목 005930 / 삼성전자, 순매수 50억, 현재가 70,000, 시초가 68,000
         */
        private void setupHappyPathStubs() throws Exception {
            // ===== 글로벌 시장 gate (전부 green) =====
            when(globalMarketService.shouldHaltBuying()).thenReturn(false);
            when(globalFuturesService.getFuturesQuote("^VIX")).thenReturn(null); // null → checkVixPause false

            // KOSPI gate: 외부 RestTemplate HTTP 호출이라 mock 불가 → 캐시 시간을 최근으로 박아
            // 60초 cooldown 분기로 진입시켜 fetchKospiChangeRate() 호출 skip.
            // kospiDropPaused 도 false 로 둬서 false 반환.
            Field lastKospi = AutoTradingBotService.class.getDeclaredField("lastKospiCheckTime");
            lastKospi.setAccessible(true);
            lastKospi.set(botService, LocalDateTime.now(fixedClock));

            // ===== 계좌: 100만원 (MIN_BALANCE=10만원 통과, 70,000원 × 14주 = 98만원 매수 가능) =====
            AccountSummaryDto richAccount = AccountSummaryDto.builder()
                    .accountId(1L)
                    .currentBalance(new BigDecimal("1000000"))
                    .totalEvaluation(BigDecimal.ZERO)
                    .build();
            when(virtualTradeService.getAccountSummary()).thenReturn(richAccount);
            when(virtualTradeService.getPortfolio()).thenReturn(new ArrayList<>());

            // ===== 수급 급증 데이터 (FOREIGN 매핑에 005930 1종목) =====
            InvestorSurgeDto surge = InvestorSurgeDto.builder()
                    .stockCode(STOCK_CODE)
                    .stockName(STOCK_NAME)
                    .investorType("FOREIGN")
                    .netBuyAmount(new BigDecimal("50"))  // MIN_NET_BUY_AMOUNT=10 통과
                    .build();
            Map<String, List<InvestorSurgeDto>> surgeMap = new HashMap<>();
            surgeMap.put("FOREIGN", List.of(surge));
            when(investorSurgeService.getAllSurgeStocks(any())).thenReturn(surgeMap);

            // ===== 종목 상태: 정상 거래 =====
            when(stockStatusService.isActive(STOCK_CODE)).thenReturn(true);

            // ===== 공매도: 정상 =====
            when(shortSellingService.isHighShortSellingStock(STOCK_CODE)).thenReturn(false);

            // ===== 섹터 OUTFLOW: 비어있음 =====
            when(sectorTradingService.getSectorRotation()).thenReturn(Collections.emptyList());

            // ===== 현재가 (필수3 + 변동성 + 거래대금/거래량 통과) =====
            StockPriceDto priceDto = new StockPriceDto();
            priceDto.setStockCode(STOCK_CODE);
            priceDto.setCurrentPrice(new BigDecimal("70000"));
            priceDto.setOpenPrice(new BigDecimal("68000"));            // 현재가 > 시초가 (양봉)
            priceDto.setHighPrice(new BigDecimal("70500"));
            priceDto.setLowPrice(new BigDecimal("67500"));             // 일중변동폭 ~4.4% > 1.5%
            priceDto.setVolume(new BigDecimal("5000000"));
            priceDto.setPreviousDayVolume(new BigDecimal("1000000"));  // 500% > MIN_VOLUME_RATIO 200%
            priceDto.setAccumulatedTradingValue(new BigDecimal("300000000000"));  // 3000억 > 200억
            priceDto.setChangePrice(new BigDecimal("1500"));           // prevClose = 70000-1500 = 68500 → gap = (68000-68500)/68500 = -0.7% < 5%
            priceDto.setChangeRate(new BigDecimal("2.19"));
            when(stockPriceService.getStockPrice(STOCK_CODE)).thenReturn(priceDto);

            // ===== 체결강도 (보조A pass: 150 ≥ 120) =====
            ScalpingAnalysisDto scalp = ScalpingAnalysisDto.builder()
                    .stockCode(STOCK_CODE)
                    .volumePower(new BigDecimal("150"))
                    .build();
            when(scalpingAnalysisService.getVolumePowerRefresh(STOCK_CODE)).thenReturn(scalp);

            // ===== RSI/MA: kisService 일봉 30개 returns 평탄한 시계열 =====
            // 평탄 데이터(70000 × 30) → RSI 계산 시 변화 0 → null 반환 가능 — 약간 변동 부여.
            List<BigDecimal> closePrices = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                // 일정 패턴 (오르락내리락) → RSI ~ 50 부근 (< 55)
                closePrices.add(new BigDecimal(70000 + (i % 2 == 0 ? 100 : -100)));
            }
            when(kisService.getDailyClosePricesWithPriority(eq(STOCK_CODE), anyInt(), any()))
                    .thenReturn(closePrices);

            // 분봉 RSI: null 반환 → 일봉 폴백 경로 사용 (controlled).
            when(kisService.getStockMinuteChartWithPriority(eq(STOCK_CODE), any()))
                    .thenReturn(null);

            // technicalIndicatorService.calculate(): RSI < 55, MA20 = currentPrice 와 거의 같게 → 이격도 ~0% < 3%
            TechnicalIndicatorsDto goodIndicators = TechnicalIndicatorsDto.builder()
                    .rsi14(new BigDecimal("45"))
                    .ma20(new BigDecimal("70000"))
                    .build();
            when(technicalIndicatorService.calculate(any())).thenReturn(goodIndicators);
            when(technicalIndicatorService.calculateSimple(any())).thenReturn(goodIndicators);

            // ===== 진입 직전 가격 재확인: null → drift check skip =====
            when(kisService.getStockPriceWithPriority(eq(STOCK_CODE), any())).thenReturn(null);

            // ===== buy mock: 정상 결과 (any 반환은 mockito default Object) =====
            // TradeService.buy 반환값(TradeHistoryDto)은 서비스에서 사용하지 않으니 default null OK.
        }

        @Test
        @DisplayName("Happy path — 모든 gate green → virtualTradeService.buy 1회 호출")
        void happyPath_allGatesGreen_buyInvokedOnce() throws Exception {
            botService.startBot(TradingMode.VIRTUAL);
            setupHappyPathStubs();

            // private executeScalpingBuyLogicInternal 직접 호출
            invokeBuyInternal(botService);

            // ★ 핵심: 매수 호출 발생
            verify(virtualTradeService, times(1)).buy(
                    eq(STOCK_CODE), eq(STOCK_NAME), any(BigDecimal.class), anyInt(), eq("SCALPING_ENTRY"));
            // 실전 모드는 호출되지 않아야 함
            verify(realTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Gate: 시장 폐장 (16:30 KST) → buy 미호출")
        void gate_marketClosed_skips() throws Exception {
            // 16:30 KST 로 시계 변경 (장 마감 후)
            Instant afterClose = ZonedDateTime.of(2026, 5, 11, 16, 30, 0, 0, KST).toInstant();
            AutoTradingBotService svc = rebuildBotWithClock(Clock.fixed(afterClose, KST));
            svc.startBot(TradingMode.VIRTUAL);
            setupHappyPathStubs();

            invokeBuyInternal(svc);

            // 16:30 은 MORNING_ENTRY_END(15:00) 초과 → 시간 분기에서 즉시 return
            verify(virtualTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Gate: 글로벌 OUTFLOW (나스닥 선물 급락) → buy 미호출")
        void gate_globalOutflow_nasdaqHalt_skips() throws Exception {
            botService.startBot(TradingMode.VIRTUAL);
            setupHappyPathStubs();
            // 글로벌 시장이 매수 보류 신호 → checkNasdaqHalt 가 true 반환
            when(globalMarketService.shouldHaltBuying()).thenReturn(true);

            invokeBuyInternal(botService);

            verify(virtualTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Gate: KOSPI 전체장 급락 (-2%) → buy 미호출")
        void gate_kospiDropPaused_skips() throws Exception {
            botService.startBot(TradingMode.VIRTUAL);
            setupHappyPathStubs();
            // checkKospiDrop() 의 외부 HTTP 호출은 mock 불가 → kospiDropPaused 플래그를 reflection 으로 true 설정.
            // 캐시 시간이 60초 이내라 fetchKospiChangeRate 스킵 후 kospiDropPaused.get() 그대로 반환.
            Field kospiPaused = AutoTradingBotService.class.getDeclaredField("kospiDropPaused");
            kospiPaused.setAccessible(true);
            ((AtomicBoolean) kospiPaused.get(botService)).set(true);

            invokeBuyInternal(botService);

            verify(virtualTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Gate: 거래정지 종목 → buy 미호출")
        void gate_tradingHalt_skips() throws Exception {
            botService.startBot(TradingMode.VIRTUAL);
            setupHappyPathStubs();
            // stockStatusService 가 비활성 종목으로 분류 → 종목 루프에서 continue
            when(stockStatusService.isActive(STOCK_CODE)).thenReturn(false);

            invokeBuyInternal(botService);

            verify(virtualTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Gate: 공매도 비율 과다 → buy 미호출")
        void gate_highShortSelling_skips() throws Exception {
            botService.startBot(TradingMode.VIRTUAL);
            setupHappyPathStubs();
            when(shortSellingService.isHighShortSellingStock(STOCK_CODE)).thenReturn(true);

            invokeBuyInternal(botService);

            verify(virtualTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Gate: 잔고 부족 (currentBalance < MIN_BALANCE=10만원) → buy 미호출")
        void gate_lowBalance_skips() throws Exception {
            botService.startBot(TradingMode.VIRTUAL);
            setupHappyPathStubs();
            // 잔고 5만원 → MIN_BALANCE(10만원) 미달 → for-loop 첫 종목에서 break
            AccountSummaryDto poor = AccountSummaryDto.builder()
                    .accountId(1L)
                    .currentBalance(new BigDecimal("50000"))
                    .totalEvaluation(BigDecimal.ZERO)
                    .build();
            when(virtualTradeService.getAccountSummary()).thenReturn(poor);

            invokeBuyInternal(botService);

            verify(virtualTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Gate: 체결강도 미달 (volumePower < 120) — 보조 1점 → 2점 미달 → buy 미호출")
        void gate_lowVolumePower_skips() throws Exception {
            botService.startBot(TradingMode.VIRTUAL);
            setupHappyPathStubs();
            // 체결강도 미달 (보조 A fail)
            ScalpingAnalysisDto weak = ScalpingAnalysisDto.builder()
                    .stockCode(STOCK_CODE)
                    .volumePower(new BigDecimal("80"))  // < 120
                    .build();
            when(scalpingAnalysisService.getVolumePowerRefresh(STOCK_CODE)).thenReturn(weak);
            // 갭 fail (보조 D fail): changePrice 를 음수로 → prevClose > open → gap 음수지만
            // 더 확실히 fail 시키려면 RSI 도 fail 시켜 보조점수 1점만 통과하게 만들어 2점 미달.
            // RSI 과열 (>= 55) 로 보조 B fail
            TechnicalIndicatorsDto badRsi = TechnicalIndicatorsDto.builder()
                    .rsi14(new BigDecimal("75"))     // ≥ 55 → fail
                    .ma20(new BigDecimal("70000"))   // 이격도 0% → pass (1점)
                    .build();
            when(technicalIndicatorService.calculate(any())).thenReturn(badRsi);
            when(technicalIndicatorService.calculateSimple(any())).thenReturn(badRsi);
            // 갭 fail: change=-5000 → prevClose=75000 → gap=(68000-75000)/75000 = -9.3% < 5% → 보조 D pass (1점)
            // 합계: 이격도 1점 + 갭 1점 = 2점 → 통과? 갭도 fail 시키자.
            // 갭상승은 (open - prevClose)/prevClose 로 계산. 5% 이상 갭상승 시 fail.
            // changePrice=+10000 → prevClose=70000-10000=60000 → gap=(68000-60000)/60000 = 13.3% ≥ 5% → fail
            StockPriceDto priceWithBadGap = new StockPriceDto();
            priceWithBadGap.setStockCode(STOCK_CODE);
            priceWithBadGap.setCurrentPrice(new BigDecimal("70000"));
            priceWithBadGap.setOpenPrice(new BigDecimal("68000"));
            priceWithBadGap.setHighPrice(new BigDecimal("70500"));
            priceWithBadGap.setLowPrice(new BigDecimal("67500"));
            priceWithBadGap.setVolume(new BigDecimal("5000000"));
            priceWithBadGap.setPreviousDayVolume(new BigDecimal("1000000"));
            priceWithBadGap.setAccumulatedTradingValue(new BigDecimal("300000000000"));
            priceWithBadGap.setChangePrice(new BigDecimal("10000"));  // gap +13% → 보조 D fail
            priceWithBadGap.setChangeRate(new BigDecimal("16.6"));
            when(stockPriceService.getStockPrice(STOCK_CODE)).thenReturn(priceWithBadGap);

            invokeBuyInternal(botService);

            // 보조 4개 중: A(체결강도) fail, B(RSI 75) fail, C(이격도 0%) pass, D(갭 +13%) fail
            // → 1/4 < 2 → 미달, buy 미호출
            verify(virtualTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Gate: RSI 과매수 (≥55) + 다른 보조 fail → 보조점수 미달 → buy 미호출")
        void gate_rsiOverbought_skips() throws Exception {
            botService.startBot(TradingMode.VIRTUAL);
            setupHappyPathStubs();
            // RSI 과열 — 보조 B fail. 추가로 갭 fail 시켜 4-2 미달.
            TechnicalIndicatorsDto overbought = TechnicalIndicatorsDto.builder()
                    .rsi14(new BigDecimal("85"))        // ≥ 55 → fail
                    .ma20(new BigDecimal("100000"))     // 현재가 70000 vs 100000 → 이격도 -30% < 3% → pass(1점)
                    .build();
            when(technicalIndicatorService.calculate(any())).thenReturn(overbought);
            when(technicalIndicatorService.calculateSimple(any())).thenReturn(overbought);
            // 체결강도 fail: 80 < 120 (보조 A fail)
            ScalpingAnalysisDto weakVol = ScalpingAnalysisDto.builder()
                    .stockCode(STOCK_CODE)
                    .volumePower(new BigDecimal("80"))
                    .build();
            when(scalpingAnalysisService.getVolumePowerRefresh(STOCK_CODE)).thenReturn(weakVol);
            // 갭 fail: changePrice +10000 → prevClose 60000 → gap +13%
            StockPriceDto p = new StockPriceDto();
            p.setStockCode(STOCK_CODE);
            p.setCurrentPrice(new BigDecimal("70000"));
            p.setOpenPrice(new BigDecimal("68000"));
            p.setHighPrice(new BigDecimal("70500"));
            p.setLowPrice(new BigDecimal("67500"));
            p.setVolume(new BigDecimal("5000000"));
            p.setPreviousDayVolume(new BigDecimal("1000000"));
            p.setAccumulatedTradingValue(new BigDecimal("300000000000"));
            p.setChangePrice(new BigDecimal("10000"));
            p.setChangeRate(new BigDecimal("16.6"));
            when(stockPriceService.getStockPrice(STOCK_CODE)).thenReturn(p);

            invokeBuyInternal(botService);

            // 보조: A fail, B fail, C pass(이격도), D fail → 1/4 < 2 → 미달
            verify(virtualTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("Gate: 섹터 약세 (OUTFLOW 섹터 포함) → buy 미호출")
        void gate_sectorOutflow_skips() throws Exception {
            botService.startBot(TradingMode.VIRTUAL);
            setupHappyPathStubs();
            // isOutflowSectorStock 의 캐시를 reflection 으로 직접 set —
            // sectorTradingService.getSectorConfig() chain stub 보다 안정적.
            Field cacheTimeField = AutoTradingBotService.class.getDeclaredField("outflowCacheTime");
            cacheTimeField.setAccessible(true);
            cacheTimeField.set(botService, LocalDateTime.now(fixedClock));
            Field outflowField = AutoTradingBotService.class.getDeclaredField("outflowSectorStocks");
            outflowField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<java.util.Set<String>> ref =
                    (java.util.concurrent.atomic.AtomicReference<java.util.Set<String>>) outflowField.get(botService);
            java.util.Set<String> outflowSet = java.util.concurrent.ConcurrentHashMap.newKeySet();
            outflowSet.add(STOCK_CODE);  // 005930 = OUTFLOW 섹터 종목
            ref.set(outflowSet);

            invokeBuyInternal(botService);

            verify(virtualTradeService, never()).buy(anyString(), anyString(), any(), anyInt(), anyString());
        }

        /** private executeScalpingBuyLogicInternal 을 reflection 으로 직접 호출. */
        private void invokeBuyInternal(AutoTradingBotService svc) throws Exception {
            Method m = AutoTradingBotService.class.getDeclaredMethod("executeScalpingBuyLogicInternal");
            m.setAccessible(true);
            m.invoke(svc);
        }
    }

    // ================================================================
    // 실시간성 가드 — surge 신선도 / 가격 stale
    // ================================================================

    @Nested
    @DisplayName("실시간성 가드 — surge 신선도 / 가격 stale")
    class FreshnessGuardTests {

        @Test
        @DisplayName("isSurgeDataFresh — snapshotTime 17분 경과면 false (15분 기준)")
        void isSurgeDataFresh_returnsFalse_whenSnapshotOlderThan15Minutes() throws Exception {
            // fixedClock 은 10:00 KST. 15분+ 이전 snapshot = 09:43 → 17분 경과
            InvestorSurgeDto stale = InvestorSurgeDto.builder()
                    .stockCode("005930").stockName("삼성전자")
                    .snapshotTime(java.time.LocalTime.of(9, 43))
                    .build();
            Map<String, List<InvestorSurgeDto>> data = new HashMap<>();
            data.put("FOREIGN", List.of(stale));

            Method m = AutoTradingBotService.class.getDeclaredMethod("isSurgeDataFresh", Map.class);
            m.setAccessible(true);
            boolean fresh = (boolean) m.invoke(botService, data);

            assertThat(fresh).isFalse();
        }

        @Test
        @DisplayName("isSurgeDataFresh — snapshotTime 5분 전이면 true (기준 내)")
        void isSurgeDataFresh_returnsTrue_whenSnapshotWithinThreshold() throws Exception {
            InvestorSurgeDto fresh = InvestorSurgeDto.builder()
                    .stockCode("005930").stockName("삼성전자")
                    .snapshotTime(java.time.LocalTime.of(9, 55))
                    .build();
            Map<String, List<InvestorSurgeDto>> data = new HashMap<>();
            data.put("FOREIGN", List.of(fresh));

            Method m = AutoTradingBotService.class.getDeclaredMethod("isSurgeDataFresh", Map.class);
            m.setAccessible(true);
            assertThat((boolean) m.invoke(botService, data)).isTrue();
        }

        @Test
        @DisplayName("isSurgeDataFresh — snapshotTime null 이면 true (보수적 통과)")
        void isSurgeDataFresh_returnsTrue_whenSnapshotTimeMissing() throws Exception {
            InvestorSurgeDto noTime = InvestorSurgeDto.builder()
                    .stockCode("005930").stockName("삼성전자")
                    .build();
            Map<String, List<InvestorSurgeDto>> data = new HashMap<>();
            data.put("FOREIGN", List.of(noTime));

            Method m = AutoTradingBotService.class.getDeclaredMethod("isSurgeDataFresh", Map.class);
            m.setAccessible(true);
            assertThat((boolean) m.invoke(botService, data)).isTrue();
        }

        @Test
        @DisplayName("isPriceStale — fetchedAt 90초 전이면 true (60초 기준 초과)")
        void isPriceStale_returnsTrue_whenFetchedAtOlderThan60Seconds() throws Exception {
            StockPriceDto dto = new StockPriceDto();
            dto.setFetchedAt(LocalDateTime.now(fixedClock).minusSeconds(90));

            Method m = AutoTradingBotService.class.getDeclaredMethod("isPriceStale", StockPriceDto.class);
            m.setAccessible(true);
            assertThat((boolean) m.invoke(botService, dto)).isTrue();
        }

        @Test
        @DisplayName("isPriceStale — fetchedAt 30초 전이면 false (기준 내)")
        void isPriceStale_returnsFalse_whenFetchedAtRecent() throws Exception {
            StockPriceDto dto = new StockPriceDto();
            dto.setFetchedAt(LocalDateTime.now(fixedClock).minusSeconds(30));

            Method m = AutoTradingBotService.class.getDeclaredMethod("isPriceStale", StockPriceDto.class);
            m.setAccessible(true);
            assertThat((boolean) m.invoke(botService, dto)).isFalse();
        }

        @Test
        @DisplayName("isPriceStale — fetchedAt null 이면 true (보수적)")
        void isPriceStale_returnsTrue_whenFetchedAtMissing() throws Exception {
            StockPriceDto dto = new StockPriceDto();

            Method m = AutoTradingBotService.class.getDeclaredMethod("isPriceStale", StockPriceDto.class);
            m.setAccessible(true);
            assertThat((boolean) m.invoke(botService, dto)).isTrue();
        }
    }

    @Nested
    @DisplayName("정규장 마감 강제청산 (작업2)")
    class RegularSessionLiquidation {
        private final LocalTime START = LocalTime.of(15, 20);
        private final LocalTime END = LocalTime.of(15, 28);

        private java.time.Clock clockAt(int h, int m) {
            return java.time.Clock.fixed(
                    java.time.ZonedDateTime.of(2026, 5, 11, h, m, 0, 0, KST).toInstant(), KST);
        }

        private void setBotActive(AutoTradingBotService bot, boolean v) throws Exception {
            Field f = AutoTradingBotService.class.getDeclaredField("botActive");
            f.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) f.get(bot)).set(v);
        }

        @Test
        @DisplayName("shouldRunLiquidationWindow — 미완료+자격+윈도우내(15:20~15:28 경계 포함)만 true")
        void runWindowTruthTable() {
            LocalTime mid = LocalTime.of(15, 22);
            assertThat(AutoTradingBotService.shouldRunLiquidationWindow(true, false, true, false, mid, START, END)).isTrue();
            assertThat(AutoTradingBotService.shouldRunLiquidationWindow(true, false, true, false, START, START, END)).isTrue();  // 시작 경계
            assertThat(AutoTradingBotService.shouldRunLiquidationWindow(true, false, true, false, END, START, END)).isTrue();    // 종료 경계
            assertThat(AutoTradingBotService.shouldRunLiquidationWindow(true, false, true, true, mid, START, END)).isFalse();    // 이미 완료
            assertThat(AutoTradingBotService.shouldRunLiquidationWindow(false, false, true, false, mid, START, END)).isFalse();  // 봇 비활성
            assertThat(AutoTradingBotService.shouldRunLiquidationWindow(true, true, true, false, mid, START, END)).isFalse();    // killswitch
            assertThat(AutoTradingBotService.shouldRunLiquidationWindow(true, false, false, false, mid, START, END)).isFalse();  // 설정 OFF
            assertThat(AutoTradingBotService.shouldRunLiquidationWindow(true, false, true, false, LocalTime.of(15, 19), START, END)).isFalse(); // 윈도우 전
            assertThat(AutoTradingBotService.shouldRunLiquidationWindow(true, false, true, false, LocalTime.of(15, 29), START, END)).isFalse(); // 윈도우 후
        }

        @Test
        @DisplayName("shouldWarnLiquidationMissed — 자격 있는데 당일 미완료면 경고")
        void warnTruthTable() {
            assertThat(AutoTradingBotService.shouldWarnLiquidationMissed(true, false, true, false)).isTrue();   // 미완료 → 경고
            assertThat(AutoTradingBotService.shouldWarnLiquidationMissed(true, false, true, true)).isFalse();   // 완료
            assertThat(AutoTradingBotService.shouldWarnLiquidationMissed(false, false, true, false)).isFalse(); // 비활성
            assertThat(AutoTradingBotService.shouldWarnLiquidationMissed(true, true, true, false)).isFalse();   // killswitch
            assertThat(AutoTradingBotService.shouldWarnLiquidationMissed(true, false, false, false)).isFalse(); // 설정 OFF
        }

        @Test
        @DisplayName("캐치업 — 윈도우내+미완료 → 매도 + 완료표기(config save)")
        void windowCatchUp() throws Exception {
            AutoTradingBotService bot = rebuildBotWithClock(clockAt(15, 22));
            setBotActive(bot, true);
            // findByConfigKey empty(default) → isLiquidatedToday=false, configOn=true(기본 ON)
            String code = "005930";
            PortfolioItemDto pos = PortfolioItemDto.builder()
                    .stockCode(code).stockName("종목").quantity(10).averagePrice(new BigDecimal("70000")).build();
            // 005930 을 봇 추적분(DB 영속 SWING)으로 등록 — 이제 청산은 봇 소유분만 매도한다.
            when(positionRepository.findByTradingMode(anyString())).thenReturn(List.of(
                    BotTradingPosition.builder().stockCode(code).strategy(BotTradingPosition.Strategy.SWING)
                            .tradingMode("VIRTUAL").build()));
            // sellPortfolioMatching getPortfolio 1번 + 완료판정 getPortfolio 1번 → 매도 후 빈 포트폴리오
            when(virtualTradeService.getPortfolio()).thenReturn(List.of(pos), Collections.emptyList());
            StockPriceDto price = new StockPriceDto();
            price.setStockCode(code); price.setCurrentPrice(new BigDecimal("71000"));
            when(stockPriceService.getStockPrices(any())).thenReturn(Map.of(code, price));
            when(virtualTradeService.sell(any(), any(), anyInt(), anyString()))
                    .thenReturn(TradeHistoryDto.builder().profitLoss(BigDecimal.TEN).build());

            bot.executeRegularSessionLiquidation();

            verify(virtualTradeService).sell(eq(code), any(), eq(10), eq("REGULAR_SESSION_CLOSE"));
            verify(botConfigRepository).save(any(BotConfig.class));   // markLiquidatedToday
        }

        @Test
        @DisplayName("청산 — 봇 추적분만 매도, 수동/untracked 보유분은 보존(전체매도 아님)")
        void liquidationOnlySellsBotTracked() throws Exception {
            AutoTradingBotService bot = rebuildBotWithClock(clockAt(15, 22));
            setBotActive(bot, true);
            String botCode = "005930", manualCode = "000660";
            PortfolioItemDto botPos = PortfolioItemDto.builder()
                    .stockCode(botCode).stockName("봇종목").quantity(10).averagePrice(new BigDecimal("70000")).build();
            PortfolioItemDto manualPos = PortfolioItemDto.builder()
                    .stockCode(manualCode).stockName("수동종목").quantity(5).averagePrice(new BigDecimal("50000")).build();
            // 봇 추적분 = 005930(SWING)만. 000660 은 KIS 잔고엔 있으나 봇 미추적(수동).
            when(positionRepository.findByTradingMode(anyString())).thenReturn(List.of(
                    BotTradingPosition.builder().stockCode(botCode).strategy(BotTradingPosition.Strategy.SWING)
                            .tradingMode("VIRTUAL").build()));
            // 매도 후에도 수동분(000660)은 잔고에 남음 → 봇 소유분(005930)만 빠지면 완료.
            when(virtualTradeService.getPortfolio()).thenReturn(List.of(botPos, manualPos), List.of(manualPos));
            StockPriceDto p1 = new StockPriceDto(); p1.setStockCode(botCode); p1.setCurrentPrice(new BigDecimal("71000"));
            when(stockPriceService.getStockPrices(any())).thenReturn(Map.of(botCode, p1));
            when(virtualTradeService.sell(any(), any(), anyInt(), anyString()))
                    .thenReturn(TradeHistoryDto.builder().profitLoss(BigDecimal.TEN).build());

            bot.executeRegularSessionLiquidation();

            verify(virtualTradeService).sell(eq(botCode), any(), eq(10), eq("REGULAR_SESSION_CLOSE"));  // 봇분 매도
            verify(virtualTradeService, never()).sell(eq(manualCode), any(), anyInt(), anyString());     // 수동분 보존
            verify(botConfigRepository).save(any(BotConfig.class));   // 봇분 청산 완료 → markLiquidatedToday
        }

        @Test
        @DisplayName("liquidationTargets — KIS 보유 ∩ 봇 소유(수동=KIS만 보호, 유령=봇만·KIS없음 스킵)")
        void liquidationTargets_intersect() {
            java.util.Set<String> kis = java.util.Set.of("A", "B", "C");   // C = 수동
            java.util.Set<String> bot = java.util.Set.of("A", "B", "D");   // D = 유령(봇 추적O, KIS X)
            assertThat(AutoTradingBotService.liquidationTargets(kis, bot)).containsExactlyInAnyOrder("A", "B");
        }

        @Test
        @DisplayName("이미 청산된 날 → 윈도우 재시도 no-op(매도 안 함)")
        void alreadyLiquidatedNoOp() throws Exception {
            AutoTradingBotService bot = rebuildBotWithClock(clockAt(15, 22));
            setBotActive(bot, true);
            BotConfig done = BotConfig.builder().configKey("trading_bot")
                    .forceRegularSessionLiquidation(true)
                    .lastForceLiquidationDate(LocalDate.of(2026, 5, 11)).build();
            when(botConfigRepository.findByConfigKey(anyString())).thenReturn(Optional.of(done));

            bot.executeRegularSessionLiquidation();

            verify(virtualTradeService, never()).sell(any(), any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("sellAllPortfolio(reason) — 보유 전 종목을 지정 사유로 매도(VIRTUAL/REAL 공용 activeTradeService)")
        void sellsAllWithReason() {
            String code = "005930";
            PortfolioItemDto pos = PortfolioItemDto.builder()
                    .stockCode(code).stockName("종목").quantity(10)
                    .averagePrice(new BigDecimal("70000")).build();
            when(virtualTradeService.getPortfolio()).thenReturn(List.of(pos));
            StockPriceDto price = new StockPriceDto();
            price.setStockCode(code);
            price.setCurrentPrice(new BigDecimal("71000"));
            when(stockPriceService.getStockPrices(any())).thenReturn(Map.of(code, price));
            when(virtualTradeService.sell(any(), any(), anyInt(), anyString()))
                    .thenReturn(TradeHistoryDto.builder().profitLoss(BigDecimal.TEN).build());

            botService.sellAllPortfolio("REGULAR_SESSION_CLOSE");

            verify(virtualTradeService).sell(eq(code), eq(new BigDecimal("71000")), eq(10), eq("REGULAR_SESSION_CLOSE"));
        }
    }
}
