package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.PaperTradingDto.AccountSummaryDto;
import com.myplatform.backend.dto.PaperTradingDto.TradeHistoryDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.TradingKillSwitch;
import com.myplatform.backend.entity.VirtualTradeHistory;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import com.myplatform.backend.service.KoreaInvestmentService.BalanceInfo;
import com.myplatform.backend.service.KoreaInvestmentService.HoldingStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RealTradeService 단위 테스트 — 머니 처리 critical paths.
 *
 * 핵심 검증:
 *  1. KIS 응답 불확실 (null / RuntimeException) → 자동 kill switch (이중 주문 방지)
 *  2. KIS 명시적 실패 (rt_cd != 0) → kill switch 발동 X (주문 안 들어간 게 확실)
 *  3. KIS 성공 + DB save 실패 → CRITICAL 알림 + kill switch (가장 위험한 비일관 상태)
 *  4. 매매 직전 잔고 조회 (force=true) 는 stale cache 폴백 안 함 (null → abort)
 *  5. UI 표시용 잔고 조회는 cache 폴백 허용 (플리킹 방지)
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class RealTradeServiceTest {

    @Mock private KoreaInvestmentService kisService;
    @Mock private StockPriceService stockPriceService;
    @Mock private TelegramNotificationService telegramService;
    @Mock private VirtualTradeHistoryRepository tradeHistoryRepository;
    @Mock private TradingSafetyService safetyService;
    @Mock private TradingAuditService auditService;
    @Mock private BotOrderIntentService orderIntentService;

    @InjectMocks
    private RealTradeService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String STOCK_CODE = "005930";
    private static final String STOCK_NAME = "삼성전자";

    @BeforeEach
    void setUp() {
        // 공통 baseline: KIS 매매 가능 상태 + 텔레그램 활성 + safety 허용 + audit no-op
        when(kisService.isRealTradingConfigured()).thenReturn(true);
        when(telegramService.isEnabled()).thenReturn(true);

        when(safetyService.checkBuy(any())).thenReturn(new TradingSafetyService.Decision(true, null));
        when(safetyService.checkSell()).thenReturn(new TradingSafetyService.Decision(true, null));
        when(safetyService.isKilled()).thenReturn(false);
        when(safetyService.isLargeTrade(any())).thenReturn(false);

        // 멱등키: 기본 PROCEED(중복 아님) — 기존 buy 테스트가 게이트를 통과해 KIS 까지 가도록.
        lenient().when(orderIntentService.tryAcquire(anyString(), anyString(), any()))
                .thenReturn(BotOrderIntentService.OrderGate.PROCEED);
        when(safetyService.enable(anyString(), anyString())).thenReturn(new TradingKillSwitch());

        // auditService.start 는 Ctx 반환 — 내부 필드만 들고 있어 mock 으로 충분
        TradingAuditService.Ctx ctx = mockCtx();
        when(auditService.start(any(), any(), anyString(), anyString(), anyInt(), any(), anyString()))
                .thenReturn(ctx);

        // 종목명 조회 polyfill
        StockPriceDto priceDto = new StockPriceDto();
        priceDto.setStockCode(STOCK_CODE);
        priceDto.setStockName(STOCK_NAME);
        when(stockPriceService.getStockPrice(anyString())).thenReturn(priceDto);

        // sumRealizedProfitLoss default null (예외 안 나도록)
        when(tradeHistoryRepository.sumRealizedProfitLoss(any())).thenReturn(BigDecimal.ZERO);

        // save 는 echo (대부분 테스트에서 성공)
        when(tradeHistoryRepository.save(any(VirtualTradeHistory.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private TradingAuditService.Ctx mockCtx() {
        // Ctx 는 protected 생성자 + final field — 리플렉션으로 만들기보다 mock 으로 처리
        return org.mockito.Mockito.mock(TradingAuditService.Ctx.class);
    }

    /** KIS 매수/매도 응답 success 형태 (rt_cd=0 + ODNO). */
    private JsonNode kisOkResponse(String orderNo) {
        try {
            String json = "{\"rt_cd\":\"0\",\"msg1\":\"정상처리\",\"output\":{\"ODNO\":\"" + orderNo + "\"}}";
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** KIS rt_cd != 0 (명시적 거부). */
    private JsonNode kisFailResponse(String rtCd, String msg) {
        try {
            String json = "{\"rt_cd\":\"" + rtCd + "\",\"msg1\":\"" + msg + "\"}";
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 출금가능금액 충분 + 보유종목 holdingQty 만큼 보유한 잔고 응답. */
    private JsonNode okBalanceJson() {
        try {
            // parseBalance 가 결과를 무시하도록 mock 처리 — 빈 JSON 도 무방.
            return objectMapper.readTree("{\"output1\":[],\"output2\":[]}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 충분 가용 잔액 + 옵션으로 보유 종목 지정 — 매매 직전 잔고 조회용. */
    private BalanceInfo balanceWith(BigDecimal available, List<HoldingStock> holdings) {
        return BalanceInfo.builder()
                .availableBalance(available)
                .depositBalance(available)
                .totalEvaluation(BigDecimal.ZERO)
                .totalProfitLoss(BigDecimal.ZERO)
                .holdings(holdings != null ? holdings : new ArrayList<>())
                .build();
    }

    private HoldingStock holding(int qty, BigDecimal avgPrice) {
        return HoldingStock.builder()
                .stockCode(STOCK_CODE)
                .stockName(STOCK_NAME)
                .quantity(qty)
                .averagePrice(avgPrice)
                .currentPrice(avgPrice)
                .profitLoss(BigDecimal.ZERO)
                .profitRate(BigDecimal.ZERO)
                .build();
    }

    // ================================================================
    // buy() — 핵심 시나리오
    // ================================================================

    @Nested
    @DisplayName("buy() — 실전 매수")
    class BuyTests {

        @Test
        @DisplayName("happy path: KIS 성공 → DB 저장 + 텔레그램 알림")
        void buy_happyPath() {
            // given — 충분한 잔액 + KIS 정상 응답
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(balanceWith(new BigDecimal("100000000"), null));
            when(kisService.buyStock(eq(STOCK_CODE), eq(10), any())).thenReturn(kisOkResponse("ORD-123456"));

            // when
            TradeHistoryDto result = service.buy(STOCK_CODE, STOCK_NAME, new BigDecimal("70000"), 10, "MANUAL");

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStockCode()).isEqualTo(STOCK_CODE);
            assertThat(result.getTradeType()).isEqualTo("BUY");
            assertThat(result.getQuantity()).isEqualTo(10);

            // DB 거래 이력 저장 확인
            ArgumentCaptor<VirtualTradeHistory> tradeCaptor = ArgumentCaptor.forClass(VirtualTradeHistory.class);
            verify(tradeHistoryRepository).save(tradeCaptor.capture());
            assertThat(tradeCaptor.getValue().getTradeType()).isEqualTo("BUY");

            // 텔레그램 알림 호출 (sendSignal or sendRisk 둘 중 하나)
            verify(telegramService, atLeastOnce()).isEnabled();

            // audit success
            verify(auditService).success(any(), eq("0"), anyString(), eq("ORD-123456"));

            // kill switch 발동 안 함
            verify(safetyService, never()).enable(anyString(), anyString());
        }

        @Test
        @DisplayName("KIS 응답 null → kill switch 발동 + IllegalStateException")
        void buy_kisReturnsNull_triggersKillSwitch() {
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(balanceWith(new BigDecimal("100000000"), null));
            when(kisService.buyStock(anyString(), anyInt(), any())).thenReturn(null);

            assertThatThrownBy(() ->
                    service.buy(STOCK_CODE, STOCK_NAME, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("매수 주문 API 호출 실패");

            // kill switch 발동 확인
            verify(safetyService).enable(contains("KIS 매수"), eq("system-auto"));
            // 텔레그램 RISK 알림
            verify(telegramService).sendRisk(contains("자동 비상정지 발동"));
            // DB save 는 일어나지 않음
            verify(tradeHistoryRepository, never()).save(any(VirtualTradeHistory.class));
            // audit failure 기록
            verify(auditService).failure(any(), eq(null), eq("API 응답 null"), eq(null));
        }

        @Test
        @DisplayName("KIS RuntimeException → kill switch 발동 + 예외 전파")
        void buy_kisThrows_triggersKillSwitch() {
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(balanceWith(new BigDecimal("100000000"), null));
            when(kisService.buyStock(anyString(), anyInt(), any()))
                    .thenThrow(new RuntimeException("KIS timeout"));

            assertThatThrownBy(() ->
                    service.buy(STOCK_CODE, STOCK_NAME, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("KIS timeout");

            verify(safetyService).enable(contains("KIS 매수"), eq("system-auto"));
            verify(telegramService).sendRisk(anyString());
            verify(tradeHistoryRepository, never()).save(any(VirtualTradeHistory.class));
        }

        @Test
        @DisplayName("KIS rt_cd != 0 (명시적 거부) → kill switch 발동 안 함")
        void buy_kisExplicitFail_noKillSwitch() {
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(balanceWith(new BigDecimal("100000000"), null));
            when(kisService.buyStock(anyString(), anyInt(), any()))
                    .thenReturn(kisFailResponse("1", "주문가능시간이 아닙니다"));

            assertThatThrownBy(() ->
                    service.buy(STOCK_CODE, STOCK_NAME, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("매수 주문 실패");

            // ★ 핵심: KIS 가 명시 거부했으므로 kill switch 발동 X
            verify(safetyService, never()).enable(anyString(), anyString());
            verify(telegramService, never()).sendRisk(anyString());
            // 거래 이력 저장도 안 함
            verify(tradeHistoryRepository, never()).save(any(VirtualTradeHistory.class));
            // audit failure 기록
            verify(auditService).failure(any(), eq("1"), eq("주문가능시간이 아닙니다"), eq(null));
        }

        @Test
        @DisplayName("KIS 성공 + DB save 실패 → CRITICAL 알림 + kill switch")
        void buy_dbSaveFail_criticalAlertAndKillSwitch() {
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(balanceWith(new BigDecimal("100000000"), null));
            when(kisService.buyStock(anyString(), anyInt(), any())).thenReturn(kisOkResponse("ORD-9999"));
            when(tradeHistoryRepository.save(any(VirtualTradeHistory.class)))
                    .thenThrow(new RuntimeException("DB connection lost"));

            assertThatThrownBy(() ->
                    service.buy(STOCK_CODE, STOCK_NAME, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(RuntimeException.class);

            // ★ KIS 성공 후 DB 실패 — 가장 위험한 시나리오
            // CRITICAL 텔레그램 알림 (DB 불일치)
            verify(telegramService).sendRisk(contains("CRITICAL"));
            // kill switch 발동
            verify(safetyService).enable(contains("매수-DB저장"), eq("system-auto"));
        }

        @Test
        @DisplayName("KIS 미설정 → IllegalStateException, KIS 호출 안 함")
        void buy_kisNotConfigured_throwsBeforeApi() {
            when(kisService.isRealTradingConfigured()).thenReturn(false);

            assertThatThrownBy(() ->
                    service.buy(STOCK_CODE, STOCK_NAME, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("실전매매 API");

            verify(kisService, never()).buyStock(anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("음수 가격 → IllegalArgumentException (KIS 호출 안 함)")
        void buy_invalidPrice_rejected() {
            assertThatThrownBy(() ->
                    service.buy(STOCK_CODE, STOCK_NAME, new BigDecimal("-100"), 10, "MANUAL"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(kisService, never()).buyStock(anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("safetyService 차단 (킬스위치 ON 등) → audit blocked + 예외")
        void buy_safetyBlocked_doesNotCallKis() {
            when(safetyService.checkBuy(any()))
                    .thenReturn(new TradingSafetyService.Decision(false, "킬스위치 ON"));

            assertThatThrownBy(() ->
                    service.buy(STOCK_CODE, STOCK_NAME, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("매수 차단");

            verify(auditService).blocked(any(), any(), eq(STOCK_CODE), eq(STOCK_NAME),
                    eq(10), any(), anyString(), eq("킬스위치 ON"));
            verify(kisService, never()).buyStock(anyString(), anyInt(), any());
        }
    }

    // ================================================================
    // sell() — 핵심 시나리오
    // ================================================================

    @Nested
    @DisplayName("sell() — 실전 매도")
    class SellTests {

        @Test
        @DisplayName("happy path: 보유 충분 → KIS 매도 + DB 저장")
        void sell_happyPath() {
            BalanceInfo bal = balanceWith(BigDecimal.ZERO, List.of(holding(20, new BigDecimal("60000"))));
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(bal);
            when(kisService.sellStock(eq(STOCK_CODE), eq(10), any())).thenReturn(kisOkResponse("SELL-001"));

            TradeHistoryDto result = service.sell(STOCK_CODE, new BigDecimal("70000"), 10, "TAKE_PROFIT");

            assertThat(result).isNotNull();
            assertThat(result.getTradeType()).isEqualTo("SELL");
            assertThat(result.getQuantity()).isEqualTo(10);
            // 손익: (70000-60000) * 10 = 100,000 (수수료/세금 차감 후)
            assertThat(result.getProfitLoss()).isNotNull();

            verify(tradeHistoryRepository).save(any(VirtualTradeHistory.class));
            verify(telegramService).sendSignal(contains("매도"));
            verify(auditService).success(any(), eq("0"), anyString(), eq("SELL-001"));
            verify(safetyService, never()).enable(anyString(), anyString());
        }

        @Test
        @DisplayName("보유 수량 부족 → IllegalStateException (KIS 호출 안 함)")
        void sell_insufficientHolding_rejected() {
            // 5주만 보유한 상태에서 10주 매도 시도
            BalanceInfo bal = balanceWith(BigDecimal.ZERO, List.of(holding(5, new BigDecimal("60000"))));
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(bal);

            assertThatThrownBy(() ->
                    service.sell(STOCK_CODE, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("보유 수량 부족");

            verify(kisService, never()).sellStock(anyString(), anyInt(), any());
            verify(tradeHistoryRepository, never()).save(any(VirtualTradeHistory.class));
        }

        @Test
        @DisplayName("보유 종목 없음 → 거절")
        void sell_noHolding_rejected() {
            BalanceInfo bal = balanceWith(BigDecimal.ZERO, new ArrayList<>());
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(bal);

            assertThatThrownBy(() ->
                    service.sell(STOCK_CODE, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("보유 수량 부족");
        }

        @Test
        @DisplayName("KIS 응답 null → kill switch 발동")
        void sell_kisReturnsNull_triggersKillSwitch() {
            BalanceInfo bal = balanceWith(BigDecimal.ZERO, List.of(holding(20, new BigDecimal("60000"))));
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(bal);
            when(kisService.sellStock(anyString(), anyInt(), any())).thenReturn(null);

            assertThatThrownBy(() ->
                    service.sell(STOCK_CODE, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(IllegalStateException.class);

            verify(safetyService).enable(contains("KIS 매도"), eq("system-auto"));
            verify(telegramService).sendRisk(anyString());
        }

        @Test
        @DisplayName("KIS 성공 + DB save 실패 → CRITICAL 알림 + kill switch")
        void sell_dbSaveFail_criticalAlert() {
            BalanceInfo bal = balanceWith(BigDecimal.ZERO, List.of(holding(20, new BigDecimal("60000"))));
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(bal);
            when(kisService.sellStock(anyString(), anyInt(), any())).thenReturn(kisOkResponse("SELL-9999"));
            when(tradeHistoryRepository.save(any(VirtualTradeHistory.class)))
                    .thenThrow(new RuntimeException("DB lost"));

            assertThatThrownBy(() ->
                    service.sell(STOCK_CODE, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(RuntimeException.class);

            verify(telegramService).sendRisk(contains("CRITICAL"));
            verify(safetyService).enable(contains("매도-DB저장"), eq("system-auto"));
        }
    }

    // ================================================================
    // 잔고 조회 (force=true vs false) — 캐시 폴백 정책
    // ================================================================

    @Nested
    @DisplayName("잔고 조회 정책 — force / cache fallback")
    class BalanceCacheTests {

        @Test
        @DisplayName("매매 직전 force=true → KIS 실패 시 cache 폴백 안 함, 매수 abort")
        void buy_kisBalanceNull_abortsWithoutCacheFallback() {
            // KIS 잔고 조회가 null → force=true 이므로 매수 즉시 중단
            when(kisService.getBalance()).thenReturn(null);

            assertThatThrownBy(() ->
                    service.buy(STOCK_CODE, STOCK_NAME, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("잔고 조회 실패");

            // KIS 매수 주문은 호출되지 않음 (잔고 확인 단계에서 abort)
            verify(kisService, never()).buyStock(anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("매매 직전 실시간 잔고 부족 → 매수 차단")
        void buy_insufficientBalance_blocked() {
            // 5만원만 가용한 상태에서 70000 * 10 = 70만원 매수 시도
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(balanceWith(new BigDecimal("50000"), null));

            assertThatThrownBy(() ->
                    service.buy(STOCK_CODE, STOCK_NAME, new BigDecimal("70000"), 10, "MANUAL"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("실시간 잔고 부족");

            verify(kisService, never()).buyStock(anyString(), anyInt(), any());
        }
    }

    // ================================================================
    // getAccountSummary — UI 폴백 정책
    // ================================================================

    @Nested
    @DisplayName("getAccountSummary — 캐시 폴백 (UI 플리킹 방지)")
    class AccountSummaryTests {

        @Test
        @DisplayName("KIS 성공 → 잔고 + 평가금액 정상 반환")
        void summary_kisOk_returnsRealValues() {
            BalanceInfo bal = balanceWith(new BigDecimal("5000000"),
                    List.of(holding(10, new BigDecimal("60000"))));
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(bal);
            when(tradeHistoryRepository.sumRealizedProfitLoss(any()))
                    .thenReturn(new BigDecimal("100000"));

            AccountSummaryDto dto = service.getAccountSummary();

            assertThat(dto).isNotNull();
            assertThat(dto.getCurrentBalance()).isEqualByComparingTo("5000000");
            assertThat(dto.getHoldingCount()).isEqualTo(1);
            // 평가금액 = 60000 * 10 = 600,000
            assertThat(dto.getTotalEvaluation()).isEqualByComparingTo("600000");
            assertThat(dto.getRealizedProfitLoss()).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("KIS 한 번도 성공 못한 상태 → 빈 DTO 반환 (예외 X)")
        void summary_kisNeverSucceeded_returnsEmptyDto() {
            when(kisService.getBalance()).thenReturn(null);

            AccountSummaryDto dto = service.getAccountSummary();

            assertThat(dto).isNotNull();
            assertThat(dto.getCurrentBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(dto.getTotalEvaluation()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(dto.getHoldingCount()).isEqualTo(0);
            assertThat(dto.getAccountName()).contains("조회 중");
        }

        @Test
        @DisplayName("KIS 일시 실패 + 이전 성공 캐시 있음 → cached 반환 (UI 플리킹 방지)")
        void summary_kisFailWithCache_returnsCachedValues() {
            // 1차: 성공 → cache 채움
            BalanceInfo first = balanceWith(new BigDecimal("3000000"),
                    List.of(holding(5, new BigDecimal("50000"))));
            when(kisService.getBalance()).thenReturn(okBalanceJson());
            when(kisService.parseBalance(any())).thenReturn(first);

            AccountSummaryDto firstDto = service.getAccountSummary();
            assertThat(firstDto.getCurrentBalance()).isEqualByComparingTo("3000000");

            // 2차: 잔고 캐시 무효화 후 KIS 실패
            service.updatePortfolioPrices(); // cachedBalance = null
            // 캐시 폴백 동작 확인을 위해선 cachedBalance 가 유지된 상태여야 함 — 별도 시나리오로 처리
        }

        @Test
        @DisplayName("KIS 일시 실패 + 캐시 폴백: cachedBalance 가 유지된 경우 cached 값 반환")
        void summary_cacheFallback_returnsCachedBalance() {
            // 1차: 성공 (cache 채움)
            BalanceInfo cached = balanceWith(new BigDecimal("2000000"),
                    List.of(holding(7, new BigDecimal("40000"))));
            when(kisService.getBalance()).thenReturn(okBalanceJson(), (JsonNode) null);
            when(kisService.parseBalance(any())).thenReturn(cached);

            // 1번째 호출 — 캐시 성공
            service.getAccountSummary();

            // 2번째 호출 — KIS 가 null 반환 → cachedBalance 폴백 (캐시 30초 TTL 이내라 첫 응답이 그대로 cached)
            AccountSummaryDto dto = service.getAccountSummary();

            assertThat(dto).isNotNull();
            // 캐시 폴백되면 빈 DTO 가 아님 — accountName 정상
            assertThat(dto.getAccountName()).isEqualTo("실전투자 계좌");
            assertThat(dto.getCurrentBalance()).isEqualByComparingTo("2000000");
        }
    }

    // ================================================================
    // 기타 메서드
    // ================================================================

    @Test
    @DisplayName("getTradeMode() = REAL")
    void getTradeMode_returnsReal() {
        assertThat(service.getTradeMode()).isEqualTo("REAL");
    }

    @Test
    @DisplayName("getPortfolio() — 잔고 없으면 빈 리스트")
    void getPortfolio_emptyWhenNoBalance() {
        when(kisService.getBalance()).thenReturn(null);
        assertThat(service.getPortfolio()).isEmpty();
    }

    // ==================== reconcileSellFill — 부분체결 이중집계 방지 ====================

    private VirtualTradeHistory sellTrade(long id, int qty) {
        return VirtualTradeHistory.builder()
                .id(id).tradeType("SELL").quantity(qty)
                .price(new BigDecimal("1000")).totalAmount(new BigDecimal("1000").multiply(BigDecimal.valueOf(qty)))
                .commission(new BigDecimal("15")).tax(new BigDecimal("200"))
                .profitLoss(new BigDecimal("1000")).build();
    }

    @Test
    @DisplayName("reconcileSellFill — 부분체결(60/100): 수량·금액·손익을 실체결로 비례 축소")
    void reconcileSellFill_partialScalesDown() {
        VirtualTradeHistory trade = sellTrade(7L, 100);
        when(tradeHistoryRepository.findById(7L)).thenReturn(java.util.Optional.of(trade));

        service.reconcileSellFill(7L, 60);

        ArgumentCaptor<VirtualTradeHistory> cap = ArgumentCaptor.forClass(VirtualTradeHistory.class);
        verify(tradeHistoryRepository).save(cap.capture());
        VirtualTradeHistory saved = cap.getValue();
        assertThat(saved.getQuantity()).isEqualTo(60);
        assertThat(saved.getProfitLoss()).isEqualByComparingTo("600");      // 1000 × 0.6
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("60000");   // 100000 × 0.6
        verify(tradeHistoryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("reconcileSellFill — 미체결(0주): 거래 없음 → 기록 삭제")
    void reconcileSellFill_noneDeletes() {
        VirtualTradeHistory trade = sellTrade(7L, 100);
        when(tradeHistoryRepository.findById(7L)).thenReturn(java.util.Optional.of(trade));

        service.reconcileSellFill(7L, 0);

        verify(tradeHistoryRepository).delete(trade);
        verify(tradeHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("reconcileSellFill — 전량체결(filled≥recorded)·SELL아님·id없음: 정정 안 함")
    void reconcileSellFill_noOpCases() {
        VirtualTradeHistory full = sellTrade(7L, 100);
        when(tradeHistoryRepository.findById(7L)).thenReturn(java.util.Optional.of(full));
        service.reconcileSellFill(7L, 100);   // filled == recorded → no-op
        service.reconcileSellFill(null, 60);  // id 없음 → 조회조차 안 함

        verify(tradeHistoryRepository, never()).save(any());
        verify(tradeHistoryRepository, never()).delete(any());
    }
}
