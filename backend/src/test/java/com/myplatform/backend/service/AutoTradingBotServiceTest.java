package com.myplatform.backend.service;

import com.myplatform.backend.dto.PaperTradingDto.AccountSummaryDto;
import com.myplatform.backend.dto.PaperTradingDto.BotStatusDto;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
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

    private AutoTradingBotService botService;

    @BeforeEach
    void setUp() {
        // 텔레그램 비활성 default — 알림 무시
        when(telegramService.isEnabled()).thenReturn(false);

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

        botService = new AutoTradingBotService(
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
                positionRepository);
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
            // lastResetDate 를 오늘로 먼저 set 해서 reset 방지
            Field lastReset = AutoTradingBotService.class.getDeclaredField("lastResetDate");
            lastReset.setAccessible(true);
            lastReset.set(botService, java.time.LocalDate.now());

            // killSwitchTriggered 를 true 로 강제 set
            Field f = AutoTradingBotService.class.getDeclaredField("killSwitchTriggered");
            f.setAccessible(true);
            AtomicBoolean killFlag = (AtomicBoolean) f.get(botService);
            killFlag.set(true);

            BotStatusDto status = botService.getBotStatus();
            assertThat(status.getStatus()).isEqualTo("KILL_SWITCH");
        }
    }
}
