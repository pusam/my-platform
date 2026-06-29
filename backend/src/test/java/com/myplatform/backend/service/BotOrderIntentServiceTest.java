package com.myplatform.backend.service;

import com.myplatform.backend.entity.BotOrderIntent;
import com.myplatform.backend.repository.BotOrderIntentRepository;
import com.myplatform.backend.service.BotOrderIntentService.OrderGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 봇 실주문 멱등키 — 리더 전환 중복 BUY 방지. fake 영속(Map)으로 인스턴스 전환 시나리오 검증.
 */
class BotOrderIntentServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Clock clock = Clock.fixed(ZonedDateTime.of(2026, 5, 11, 14, 0, 0, 0, KST).toInstant(), KST);

    private BotOrderIntentService service;
    private Map<String, BotOrderIntent> backing;

    private static String key(String code, String side, String reason) { return code + "|" + side + "|" + reason; }

    @BeforeEach
    void setUp() {
        BotOrderIntentRepository repo = mock(BotOrderIntentRepository.class);
        backing = new HashMap<>();
        // fake 영속: findBy → Map 조회, save → Map 기록 (같은 Redis/DB 를 보는 인스턴스 시뮬레이션)
        when(repo.findByStockCodeAndSideAndTradeDateAndReason(anyString(), anyString(), any(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(
                        backing.get(key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(3)))));
        when(repo.save(any(BotOrderIntent.class))).thenAnswer(inv -> {
            BotOrderIntent i = inv.getArgument(0);
            backing.put(key(i.getStockCode(), i.getSide(), i.getReason()), i);
            return i;
        });
        service = new BotOrderIntentService(repo, clock);
    }

    @Test
    @DisplayName("decideGate — 없음/FAILED→PROCEED, PENDING/DONE→SKIP")
    void decideGate() {
        assertThat(BotOrderIntentService.decideGate(Optional.empty())).isEqualTo(OrderGate.PROCEED);
        assertThat(BotOrderIntentService.decideGate(Optional.of("FAILED"))).isEqualTo(OrderGate.PROCEED);
        assertThat(BotOrderIntentService.decideGate(Optional.of("PENDING"))).isEqualTo(OrderGate.SKIP_DUPLICATE);
        assertThat(BotOrderIntentService.decideGate(Optional.of("DONE"))).isEqualTo(OrderGate.SKIP_DUPLICATE);
    }

    @Test
    @DisplayName("A 주문 직후 死 → B 승계 → 같은 종목 재매수 차단(2번째 SKIP)")
    void failoverBlocksDuplicate() {
        // A: 선기록 → PROCEED(PENDING 저장). 이후 KIS 호출 중 死 → markDone 못 함, PENDING 잔존.
        assertThat(service.tryAcquire("005930", "BUY", "SWING_FOREIGN")).isEqualTo(OrderGate.PROCEED);
        // B 승계 → 같은 키 → SKIP (이중 매수 차단)
        assertThat(service.tryAcquire("005930", "BUY", "SWING_FOREIGN")).isEqualTo(OrderGate.SKIP_DUPLICATE);
    }

    @Test
    @DisplayName("KIS 확정 거부 정상 케이스 — markFailed 후 재시도 허용(안 막힘)")
    void failedAllowsRetry() {
        assertThat(service.tryAcquire("005930", "BUY", "SWING_FOREIGN")).isEqualTo(OrderGate.PROCEED);
        service.markFailed("005930", "BUY", "SWING_FOREIGN");
        assertThat(service.tryAcquire("005930", "BUY", "SWING_FOREIGN")).isEqualTo(OrderGate.PROCEED);
    }

    @Test
    @DisplayName("markDone(KIS 성공) 후 → 재주문 차단 + 주문번호 기록")
    void doneBlocksRetry() {
        service.tryAcquire("005930", "BUY", "SWING_FOREIGN");
        service.markDone("005930", "BUY", "SWING_FOREIGN", "0001");
        assertThat(service.tryAcquire("005930", "BUY", "SWING_FOREIGN")).isEqualTo(OrderGate.SKIP_DUPLICATE);
        assertThat(backing.get(key("005930", "BUY", "SWING_FOREIGN")).getKisOrderNo()).isEqualTo("0001");
    }

    @Test
    @DisplayName("다른 시그널/종목은 별개 — 차단 안 함")
    void differentKeyNotBlocked() {
        service.tryAcquire("005930", "BUY", "SWING_FOREIGN");
        assertThat(service.tryAcquire("005930", "BUY", "SWING_INSTITUTION")).isEqualTo(OrderGate.PROCEED);  // 다른 시그널
        assertThat(service.tryAcquire("000660", "BUY", "SWING_FOREIGN")).isEqualTo(OrderGate.PROCEED);      // 다른 종목
    }
}
