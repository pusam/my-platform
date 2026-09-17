package com.myplatform.backend.service;

import com.myplatform.backend.dto.PaperTradingDto.PortfolioItemDto;
import com.myplatform.backend.dto.PaperTradingDto.TradeHistoryDto;
import com.myplatform.backend.entity.BotTradingPosition;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.service.RealTradeService.FillResult;
import com.myplatform.backend.service.RealTradeService.FillStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F1 재현 — 실전 분할익절이 <b>체결 확인 없이</b> 완료로 기록되던 결함(2026-09-17 감사).
 *
 * <p><b>재현 입력</b>: 10주 보유 → 1차 익절 5주 주문 → KIS 접수 성공(주문번호 있음) → 실제 체결 2주.
 *
 * <p><b>수정 전</b>: {@code executeScalpingSell} 의 체결 확인 분기가 {@code !isPartialSell} 이라
 * 분할매도는 {@code confirmFill} 을 아예 부르지 않았다. 주문 직전에 {@code halfSold=true} 를
 * 영속화했으므로 0주 체결이어도 1차 익절이 완료로 남고, {@code RealTradeService.sell} 이 저장한
 * <b>5주</b> 매도 이력·실현손익이 그대로 남았다.
 *
 * <p>이 테스트는 실제 매도 실행 경로({@code executeScalpingSell})를 그대로 태운다.
 */
class AutoTradingBotPartialSellTest {

    private static final String CODE = "005930";

    private AutoTradingBotService bot;
    private RealTradeService realTradeService;
    private BotTradingPositionRepository positionRepository;
    private TelegramNotificationService telegramService;

    @BeforeEach
    void setUp() throws Exception {
        realTradeService = mock(RealTradeService.class);
        positionRepository = mock(BotTradingPositionRepository.class);
        telegramService = mock(TelegramNotificationService.class);

        bot = mock(AutoTradingBotService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(bot, "realTradeService", realTradeService);
        ReflectionTestUtils.setField(bot, "activeTradeService", realTradeService);
        ReflectionTestUtils.setField(bot, "positionRepository", positionRepository);
        ReflectionTestUtils.setField(bot, "telegramService", telegramService);
        ReflectionTestUtils.setField(bot, "clock", java.time.Clock.systemDefaultZone());
        ReflectionTestUtils.setField(bot, "currentMode", tradingMode("REAL"));

        initCollectionFields(bot);

        lenient().when(telegramService.isEnabled()).thenReturn(false);
        lenient().when(positionRepository.findByStrategyAndStockCodeAndTradingMode(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(positionRepository.save(any(BotTradingPosition.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * {@code CALLS_REAL_METHODS} 모킹은 생성자를 거치지 않아 인라인 초기화된 final 컬렉션·카운터가
     * null 이다. 실제 매도 경로가 그것들을 만지므로 빈 인스턴스로 채운다(동작은 바꾸지 않는다).
     */
    private static void initCollectionFields(Object target) throws Exception {
        for (Field f : AutoTradingBotService.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            if (f.get(target) != null) continue;
            Class<?> t = f.getType();
            Object v = null;
            if (Map.class.isAssignableFrom(t)) v = new java.util.concurrent.ConcurrentHashMap<>();
            else if (java.util.Set.class.isAssignableFrom(t)) v = java.util.concurrent.ConcurrentHashMap.newKeySet();
            else if (t == java.util.concurrent.atomic.AtomicInteger.class) v = new java.util.concurrent.atomic.AtomicInteger();
            else if (t == java.util.concurrent.atomic.AtomicBoolean.class) v = new java.util.concurrent.atomic.AtomicBoolean();
            else if (t == java.util.concurrent.atomic.AtomicReference.class) v = new java.util.concurrent.atomic.AtomicReference<>();
            if (v != null) f.set(target, v);
        }
    }

    private static Object tradingMode(String name) throws Exception {
        Class<?> c = Class.forName("com.myplatform.backend.service.AutoTradingBotService$TradingMode");
        return Enum.valueOf(c.asSubclass(Enum.class), name);
    }

    /** in-memory 스캘핑 포지션 주입 — 10주 보유, 아직 1차 익절 전. */
    @SuppressWarnings("unchecked")
    private Object injectPosition() throws Exception {
        Class<?> sp = Class.forName("com.myplatform.backend.service.AutoTradingBotService$ScalpingPosition");
        Constructor<?> ctor = sp.getDeclaredConstructor(String.class, String.class, BigDecimal.class, int.class);
        ctor.setAccessible(true);
        Object pos = ctor.newInstance(CODE, "삼성전자", new BigDecimal("10000"), 10);

        Field f = AutoTradingBotService.class.getDeclaredField("scalpingPositions");
        f.setAccessible(true);
        ((Map<String, Object>) f.get(bot)).put(CODE, pos);
        return pos;
    }

    private static Object field(Object pos, String name) throws Exception {
        Field f = pos.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(pos);
    }

    private static PortfolioItemDto portfolio() {
        PortfolioItemDto p = new PortfolioItemDto();
        p.setStockCode(CODE);
        p.setStockName("삼성전자");
        p.setQuantity(10);
        return p;
    }

    /** 1차 익절 5주 매도를 실제 경로로 실행한다. */
    private void sellHalf() {
        ReflectionTestUtils.invokeMethod(bot, "executeScalpingSell",
                portfolio(), new BigDecimal("10120"), new BigDecimal("10000"),
                new BigDecimal("1.2"), 5, "TAKE_PROFIT_HALF", true);
    }

    private void givenOrderAccepted() {
        TradeHistoryDto dto = new TradeHistoryDto();
        dto.setId(77L);
        dto.setOrderNo("ODNO-1");
        when(realTradeService.sell(eq(CODE), any(), eq(5), anyString())).thenReturn(dto);
    }

    // ==================== 재현 ====================

    @Test
    @DisplayName("5주 주문 중 2주만 체결 — 체결을 확인하고 이력을 2주로 정정한다(수정 전엔 확인 자체를 안 했다)")
    void partialFillIsConfirmedAndReconciled() throws Exception {
        Object pos = injectPosition();
        givenOrderAccepted();
        when(realTradeService.confirmFill(eq(CODE), eq("ODNO-1"), eq(5)))
                .thenReturn(new FillResult(2, FillStatus.PARTIAL));

        sellHalf();

        verify(realTradeService).confirmFill(eq(CODE), eq("ODNO-1"), eq(5));
        verify(realTradeService).reconcileSellFill(eq(77L), eq(2));
        assertThat(field(pos, "halfSold")).as("2/5 체결은 1차 익절 완료가 아니다").isEqualTo(false);
        assertThat(field(pos, "partialOrderNo")).isEqualTo("ODNO-1");
        assertThat(field(pos, "partialFilledQty")).isEqualTo(2);
        assertThat(field(pos, "partialTargetQty")).isEqualTo(5);
    }

    @Test
    @DisplayName("0주 체결 — 확정 0주가 확정 수익으로 남지 않게 기록을 정정하고 완료로 치지 않는다")
    void zeroFillIsNotTreatedAsComplete() throws Exception {
        Object pos = injectPosition();
        givenOrderAccepted();
        when(realTradeService.confirmFill(eq(CODE), eq("ODNO-1"), eq(5)))
                .thenReturn(new FillResult(0, FillStatus.NONE));

        sellHalf();

        verify(realTradeService).reconcileSellFill(eq(77L), eq(0));
        assertThat(field(pos, "halfSold")).isEqualTo(false);
        assertThat(field(pos, "partialFilledQty")).isEqualTo(0);
    }

    @Test
    @DisplayName("전량 체결 — 1차 익절 완료로 닫고 기록은 정정하지 않는다")
    void fullFillCompletes() throws Exception {
        Object pos = injectPosition();
        givenOrderAccepted();
        when(realTradeService.confirmFill(eq(CODE), eq("ODNO-1"), eq(5)))
                .thenReturn(new FillResult(5, FillStatus.FULL));

        sellHalf();

        assertThat(field(pos, "halfSold")).isEqualTo(true);
        assertThat(field(pos, "partialOrderNo")).isNull();
        verify(realTradeService, never()).reconcileSellFill(any(), anyInt());
    }

    @Test
    @DisplayName("UNKNOWN — 기존 정책 보존: 전량체결로 간주하고 닫는다(조회 실패로 매도가 멈추지 않게)")
    void unknownPreservesExistingPolicy() throws Exception {
        Object pos = injectPosition();
        givenOrderAccepted();
        when(realTradeService.confirmFill(eq(CODE), eq("ODNO-1"), eq(5)))
                .thenReturn(new FillResult(5, FillStatus.UNKNOWN));

        sellHalf();

        assertThat(field(pos, "halfSold")).isEqualTo(true);
        verify(realTradeService, never()).reconcileSellFill(any(), anyInt());
    }

    // ==================== 중복 주문 금지 ====================

    @Test
    @DisplayName("미체결 주문이 남아 있으면 다음 틱은 새 주문 대신 기존 주문을 확인한다")
    void nextTickConfirmsInsteadOfPlacingNewOrder() throws Exception {
        Object pos = injectPosition();
        givenOrderAccepted();
        when(realTradeService.confirmFill(eq(CODE), eq("ODNO-1"), eq(5)))
                .thenReturn(new FillResult(2, FillStatus.PARTIAL));
        sellHalf();

        // 다음 틱 — 평가 루프가 부르는 진입점
        boolean hold = Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(bot, "resolveOutstandingPartial", pos));

        assertThat(hold).as("주문이 살아 있으면 이 종목의 매도 평가를 보류한다").isTrue();
        verify(realTradeService, never()).sell(eq(CODE), any(), eq(10), anyString());
    }

    @Test
    @DisplayName("늦은 추가 체결(2→5주)이면 완료로 닫고 기록을 확대 반영한다")
    void lateAdditionalFillCompletes() throws Exception {
        Object pos = injectPosition();
        givenOrderAccepted();
        when(realTradeService.confirmFill(eq(CODE), eq("ODNO-1"), eq(5)))
                .thenReturn(new FillResult(2, FillStatus.PARTIAL));
        sellHalf();

        when(realTradeService.confirmFill(eq(CODE), eq("ODNO-1"), eq(5)))
                .thenReturn(new FillResult(5, FillStatus.FULL));
        boolean hold = Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(bot, "resolveOutstandingPartial", pos));

        assertThat(hold).isFalse();
        assertThat(field(pos, "halfSold")).isEqualTo(true);
        assertThat(field(pos, "partialOrderNo")).isNull();
    }

    @Test
    @DisplayName("완료된 뒤에는 보류하지 않고 체결 조회도 더 하지 않는다 — 멱등")
    void completedStateIsIdempotent() throws Exception {
        Object pos = injectPosition();
        givenOrderAccepted();
        when(realTradeService.confirmFill(eq(CODE), eq("ODNO-1"), eq(5)))
                .thenReturn(new FillResult(5, FillStatus.FULL));
        sellHalf();

        boolean hold = Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(bot, "resolveOutstandingPartial", pos));

        assertThat(hold).isFalse();
        verify(realTradeService, org.mockito.Mockito.times(1)).confirmFill(anyString(), anyString(), anyInt());
    }

    // ==================== 상태 영속 ====================

    @Test
    @DisplayName("주문 접수 사실을 먼저 영속화한다 — 재시작해도 중복 주문을 내지 않게")
    void orderAcceptanceIsPersistedBeforeConfirmation() throws Exception {
        injectPosition();
        givenOrderAccepted();
        when(realTradeService.confirmFill(eq(CODE), eq("ODNO-1"), eq(5)))
                .thenReturn(new FillResult(2, FillStatus.PARTIAL));

        sellHalf();

        org.mockito.ArgumentCaptor<BotTradingPosition> cap =
                org.mockito.ArgumentCaptor.forClass(BotTradingPosition.class);
        verify(positionRepository, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(e -> "ODNO-1".equals(e.getPartialOrderNo()));
        assertThat(cap.getValue().getPartialFilledQty()).isEqualTo(2);
    }

    // ==================== VIRTUAL 회귀 ====================

    @Test
    @DisplayName("VIRTUAL 은 전량체결 가정 — 체결 조회를 하지 않고 완료로 닫는다(기존 동작 보존)")
    void virtualAssumesFullFill() throws Exception {
        ReflectionTestUtils.setField(bot, "currentMode", tradingMode("VIRTUAL"));
        Object pos = injectPosition();
        givenOrderAccepted();

        sellHalf();

        assertThat(field(pos, "halfSold")).isEqualTo(true);
        verify(realTradeService, never()).confirmFill(anyString(), anyString(), anyInt());
    }
}
