package com.myplatform.backend.service;

import com.myplatform.backend.entity.BotSellInflight;
import com.myplatform.backend.repository.BotSellInflightRepository;
import com.myplatform.backend.service.BotSellInflightService.SellGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SELL in-flight 마커 (P3-1 잔여 ③ B안) — fake 영속(Map)으로 리더 전환/더블런 시나리오 검증.
 *
 * <p>핵심 회귀 대상(DESIGN §4.3):
 *  1. 순수 decideSellGate 판정(없음/유효/만료/경계).
 *  2. 동시 acquire 경쟁 → 한쪽 SKIP.
 *  3. S3 보존: release/TTL 만료 후 잔여분 재시도 PROCEED.
 *  4. 비대칭 극성: DB 오류 = PROCEED_UNGUARDED(fail-open) — BUY tryAcquire(throw)와 반대 고정.
 */
class BotSellInflightServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 7, 8, 10, 0, 0, 0, KST);

    private BotSellInflightRepository repo;
    private TelegramNotificationService telegram;
    private Map<String, BotSellInflight> backing;

    @BeforeEach
    void setUp() {
        repo = mock(BotSellInflightRepository.class);
        telegram = mock(TelegramNotificationService.class);
        lenient().when(telegram.isEnabled()).thenReturn(true);
        backing = new HashMap<>();
        // fake 영속: 같은 DB 를 보는 두 인스턴스(리더 A/B) 시뮬레이션
        lenient().when(repo.findByStockCodeAndTradingMode(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(backing.get(inv.getArgument(0))));
        lenient().when(repo.save(any(BotSellInflight.class))).thenAnswer(inv -> {
            BotSellInflight m = inv.getArgument(0);
            if (backing.containsKey(m.getStockCode())) {
                throw new DataIntegrityViolationException("uk_bsi_code_mode");
            }
            backing.put(m.getStockCode(), m);
            return m;
        });
        // 조건부 UPDATE 시뮬레이션: expires_at <= now 일 때만 갱신(rowsAffected 반환)
        lenient().when(repo.reacquireExpired(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    BotSellInflight m = backing.get(inv.getArgument(0));
                    LocalDateTime now = inv.getArgument(2);
                    if (m == null || m.getExpiresAt().isAfter(now)) return 0;
                    m.setAcquiredAt(now);
                    m.setExpiresAt(inv.getArgument(3));
                    m.setHolder(inv.getArgument(4));
                    return 1;
                });
        lenient().doAnswer(inv -> backing.remove(inv.getArgument(0)))
                .when(repo).deleteByStockCodeAndTradingMode(anyString(), anyString());
    }

    private BotSellInflightService serviceAt(ZonedDateTime time) {
        return new BotSellInflightService(repo, telegram, Clock.fixed(time.toInstant(), KST));
    }

    @Test
    @DisplayName("decideSellGate — 없음/만료/경계(now==expiresAt)→PROCEED, 유효→SKIP")
    void decideSellGate_pure() {
        LocalDateTime now = T0.toLocalDateTime();
        assertThat(BotSellInflightService.decideSellGate(Optional.empty(), now))
                .isEqualTo(SellGate.PROCEED);
        assertThat(BotSellInflightService.decideSellGate(Optional.of(now.plusSeconds(1)), now))
                .isEqualTo(SellGate.SKIP_CONCURRENT);
        assertThat(BotSellInflightService.decideSellGate(Optional.of(now.minusSeconds(1)), now))
                .isEqualTo(SellGate.PROCEED);
        // 경계: now == expiresAt → 만료로 취급(재획득 가능)
        assertThat(BotSellInflightService.decideSellGate(Optional.of(now), now))
                .isEqualTo(SellGate.PROCEED);
    }

    @Test
    @DisplayName("S1 리더 전환: A 획득 직후 死 → B(TTL 내) SKIP → 다음 사이클(TTL 후) PROCEED")
    void leaderFailover_blocksWithinTtl_thenAllowsNextCycle() {
        // A: 획득 후 KIS 발사 직후 死(release 못 함)
        assertThat(serviceAt(T0).tryAcquire("005930")).isEqualTo(SellGate.PROCEED);
        // B 승계, 같은 사이클(TTL 60s 내) → 양보
        assertThat(serviceAt(T0.plusSeconds(30)).tryAcquire("005930")).isEqualTo(SellGate.SKIP_CONCURRENT);
        // 다음 사이클(TTL 만료 후) → 재획득(이 시점엔 KIS 잔고 재조회가 A 의 체결을 반영)
        assertThat(serviceAt(T0.plusSeconds(61)).tryAcquire("005930")).isEqualTo(SellGate.PROCEED);
    }

    @Test
    @DisplayName("S3 보존: 정상 완료 release 후 잔여분 재시도 즉시 PROCEED")
    void releaseAllowsImmediateRetry() {
        BotSellInflightService service = serviceAt(T0);
        assertThat(service.tryAcquire("005930")).isEqualTo(SellGate.PROCEED);
        service.release("005930");   // 부분체결 확정 → 포지션 유지 → 다음 사이클 잔여 재시도
        assertThat(serviceAt(T0.plusSeconds(5)).tryAcquire("005930")).isEqualTo(SellGate.PROCEED);
    }

    @Test
    @DisplayName("동시 insert 경쟁(UNIQUE 위반) → 한쪽 SKIP")
    void concurrentInsertRace_oneYields() {
        // A 가 이미 저장 완료(백킹에 존재) 상태에서, B 의 findBy 는 늦게 읽어 빈 값을 봤다고 가정 —
        // save 시 UNIQUE 위반 → SKIP
        backing.put("005930", BotSellInflight.builder()
                .stockCode("005930").tradingMode("REAL")
                .acquiredAt(T0.toLocalDateTime()).expiresAt(T0.toLocalDateTime().plusSeconds(60))
                .build());
        when(repo.findByStockCodeAndTradingMode(anyString(), anyString())).thenReturn(Optional.empty());
        assertThat(serviceAt(T0).tryAcquire("005930")).isEqualTo(SellGate.SKIP_CONCURRENT);
    }

    @Test
    @DisplayName("만료 행 재획득 경쟁 — 조건부 UPDATE 0행(다른 쪽이 선점) → SKIP")
    void expiredReacquireRace_loserYields() {
        backing.put("005930", BotSellInflight.builder()
                .stockCode("005930").tradingMode("REAL")
                .acquiredAt(T0.toLocalDateTime().minusMinutes(5))
                .expiresAt(T0.toLocalDateTime().minusMinutes(4))
                .build());
        // 경쟁자가 먼저 UPDATE 성공했다고 가정 → 내 조건부 UPDATE 는 0행
        when(repo.reacquireExpired(anyString(), anyString(), any(), any(), any())).thenReturn(0);
        assertThat(serviceAt(T0).tryAcquire("005930")).isEqualTo(SellGate.SKIP_CONCURRENT);
    }

    @Test
    @DisplayName("비대칭 극성: DB 오류 → PROCEED_UNGUARDED(fail-open, throw 금지) + RISK 알림 1회(스로틀)")
    void dbError_failsOpenWithThrottledAlert() {
        when(repo.findByStockCodeAndTradingMode(anyString(), anyString()))
                .thenThrow(new RuntimeException("DB down"));

        BotSellInflightService service = serviceAt(T0);
        // BUY 멱등키(tryAcquire=throw, fail-closed)와 반대 — 매도는 가드 없이 진행
        assertThat(service.tryAcquire("005930")).isEqualTo(SellGate.PROCEED_UNGUARDED);
        verify(telegram, times(1)).sendRisk(anyString());

        // 10분 스로틀 내 재발 → 추가 알림 없음(진행은 계속 fail-open)
        assertThat(service.tryAcquire("000660")).isEqualTo(SellGate.PROCEED_UNGUARDED);
        verify(telegram, times(1)).sendRisk(anyString());
    }

    @Test
    @DisplayName("release DB 오류 → 무해(예외 전파 없음, TTL 백스톱)")
    void releaseError_harmless() {
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(repo).deleteByStockCodeAndTradingMode(anyString(), anyString());
        serviceAt(T0).release("005930");   // 예외 없이 반환하면 통과
        verify(telegram, never()).sendRisk(anyString());
    }

    @Test
    @DisplayName("다른 종목은 별개 마커 — 차단 안 함")
    void differentStockNotBlocked() {
        BotSellInflightService service = serviceAt(T0);
        assertThat(service.tryAcquire("005930")).isEqualTo(SellGate.PROCEED);
        assertThat(service.tryAcquire("000660")).isEqualTo(SellGate.PROCEED);
    }
}
