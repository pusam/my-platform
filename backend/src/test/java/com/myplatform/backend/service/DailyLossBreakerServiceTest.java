package com.myplatform.backend.service;

import com.myplatform.backend.entity.BotConfig;
import com.myplatform.backend.entity.VirtualAccount;
import com.myplatform.backend.repository.BotConfigRepository;
import com.myplatform.backend.repository.VirtualAccountRepository;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import com.myplatform.backend.service.DailyLossBreakerService.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 일일 손실 서킷브레이커 — judge 순수함수 경계값 + 서비스 오케스트레이션(TRIP 1회 멱등·fail-open 예외).
 *
 * <p>핵심 안전 규약: ① pnl ≤ -limit 등호 포함 발동 ② <b>BLOCKED-before-null</b>(발동 후 조회 실패에도
 * 차단 유지) ③ 조건부 UPDATE rowsAffected==1 일 때만 알림/감사(경합·재시작 멱등) ④ 매도 경로 무관(비대칭).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailyLossBreakerServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 6);
    private static final BigDecimal LIMIT = new BigDecimal("300000");

    @Mock private BotConfigRepository botConfigRepository;
    @Mock private VirtualTradeHistoryRepository tradeHistoryRepository;
    @Mock private VirtualAccountRepository virtualAccountRepository;
    @Mock private TradingAuditService auditService;
    @Mock private ObjectProvider<TelegramNotificationService> telegramProvider;
    @Mock private TelegramNotificationService telegram;

    private DailyLossBreakerService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(10, 0).atZone(KST).toInstant(), KST);
        service = new DailyLossBreakerService(botConfigRepository, tradeHistoryRepository,
                virtualAccountRepository, auditService, telegramProvider, clock);
        lenient().when(telegramProvider.getIfAvailable()).thenReturn(telegram);
        lenient().when(telegram.isEnabled()).thenReturn(true);
    }

    private BotConfig breakerConfig(BigDecimal limit, Boolean enabled, LocalDate trippedDate) {
        return BotConfig.builder()
                .configKey("daily_loss_breaker")
                .dailyLossLimitKrw(limit)
                .dailyLossBreakerEnabled(enabled)
                .dailyLossBreakerTrippedDate(trippedDate)
                .build();
    }

    private void givenConfig(BotConfig config) {
        when(botConfigRepository.findByConfigKey("daily_loss_breaker")).thenReturn(Optional.of(config));
    }

    private void givenRealPnl(BigDecimal pnl) {
        when(tradeHistoryRepository.sumRealizedPnlBetween(eq(999999L), any(), any())).thenReturn(pnl);
    }

    // ================================================================
    // judge 순수함수 — 경계값
    // ================================================================

    @Nested
    @DisplayName("judge 경계값")
    class JudgeBoundaries {

        @Test
        @DisplayName("정확히 -limit = TRIP (등호 포함), -limit+1원 = PASS")
        void exactBoundary() {
            assertThat(DailyLossBreakerService.judge(new BigDecimal("-300000"), LIMIT, true, null, TODAY))
                    .isEqualTo(Decision.TRIP);
            assertThat(DailyLossBreakerService.judge(new BigDecimal("-299999"), LIMIT, true, null, TODAY))
                    .isEqualTo(Decision.PASS);
            assertThat(DailyLossBreakerService.judge(new BigDecimal("-300001"), LIMIT, true, null, TODAY))
                    .isEqualTo(Decision.TRIP);
        }

        @Test
        @DisplayName("이익/0원 = PASS")
        void profitPasses() {
            assertThat(DailyLossBreakerService.judge(BigDecimal.ZERO, LIMIT, true, null, TODAY))
                    .isEqualTo(Decision.PASS);
            assertThat(DailyLossBreakerService.judge(new BigDecimal("500000"), LIMIT, true, null, TODAY))
                    .isEqualTo(Decision.PASS);
        }

        @Test
        @DisplayName("enabled=false = DISABLED (한도 초과라도)")
        void disabledFlag() {
            assertThat(DailyLossBreakerService.judge(new BigDecimal("-999999"), LIMIT, false, null, TODAY))
                    .isEqualTo(Decision.DISABLED);
        }

        @Test
        @DisplayName("limit null/0/음수 = DISABLED (오설정 가드 — 0원 한도 상시차단 사고 방지)")
        void invalidLimit() {
            assertThat(DailyLossBreakerService.judge(new BigDecimal("-1"), null, true, null, TODAY))
                    .isEqualTo(Decision.DISABLED);
            assertThat(DailyLossBreakerService.judge(new BigDecimal("-1"), BigDecimal.ZERO, true, null, TODAY))
                    .isEqualTo(Decision.DISABLED);
            assertThat(DailyLossBreakerService.judge(new BigDecimal("-1"), new BigDecimal("-100"), true, null, TODAY))
                    .isEqualTo(Decision.DISABLED);
        }

        @Test
        @DisplayName("trippedDate=오늘 = BLOCKED / 어제 = 자동 해제(과거 발동 무시)")
        void trippedDateComparison() {
            assertThat(DailyLossBreakerService.judge(BigDecimal.ZERO, LIMIT, true, TODAY, TODAY))
                    .isEqualTo(Decision.BLOCKED);
            assertThat(DailyLossBreakerService.judge(BigDecimal.ZERO, LIMIT, true, TODAY.minusDays(1), TODAY))
                    .isEqualTo(Decision.PASS);  // 어제 발동 → 오늘 자동 해제
        }

        @Test
        @DisplayName("★ null pnl + trippedDate=오늘 = BLOCKED — 발동 후 DB 블립에도 차단 유지(fail-open 예외)")
        void nullPnlButTrippedStaysBlocked() {
            assertThat(DailyLossBreakerService.judge(null, LIMIT, true, TODAY, TODAY))
                    .isEqualTo(Decision.BLOCKED);
        }

        @Test
        @DisplayName("null pnl + 미발동 = PASS (판정 불가 — 미발동만 fail-open)")
        void nullPnlNotTrippedPasses() {
            assertThat(DailyLossBreakerService.judge(null, LIMIT, true, null, TODAY))
                    .isEqualTo(Decision.PASS);
        }

        @Test
        @DisplayName("발동 상태에선 pnl 회복(이익 전환)이어도 당일은 BLOCKED 유지")
        void blockedStaysEvenIfPnlRecovers() {
            assertThat(DailyLossBreakerService.judge(new BigDecimal("100000"), LIMIT, true, TODAY, TODAY))
                    .isEqualTo(Decision.BLOCKED);
        }
    }

    // ================================================================
    // allowEntry — 오케스트레이션
    // ================================================================

    @Test
    @DisplayName("TRIP 전이: 조건부 UPDATE 1행 → 진입 거부 + 텔레그램/감사 1회")
    void trip_firstTime() {
        givenConfig(breakerConfig(LIMIT, true, null));
        givenRealPnl(new BigDecimal("-350000"));
        when(botConfigRepository.tripDailyLossBreaker(eq("daily_loss_breaker"), eq(TODAY))).thenReturn(1);

        boolean allowed = service.allowEntry(true);

        assertThat(allowed).isFalse();
        verify(telegram, times(1)).sendRisk(anyString());
        verify(auditService, times(1)).blocked(any(), any(), any(), any(), any(), any(),
                eq("DAILY_LOSS_BREAKER"), anyString());
    }

    @Test
    @DisplayName("TRIP 경합 패자(UPDATE 0행): 진입 거부하되 중복 알림/감사 없음 (멱등)")
    void trip_raceLoserNoDuplicateAlert() {
        givenConfig(breakerConfig(LIMIT, true, null));
        givenRealPnl(new BigDecimal("-350000"));
        when(botConfigRepository.tripDailyLossBreaker(eq("daily_loss_breaker"), eq(TODAY))).thenReturn(0);

        boolean allowed = service.allowEntry(true);

        assertThat(allowed).isFalse();
        verify(telegram, never()).sendRisk(anyString());
        verify(auditService, never()).blocked(any(), any(), any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("이미 오늘 발동(BLOCKED): 진입 거부, UPDATE/알림 없음")
    void alreadyTripped_noSideEffects() {
        givenConfig(breakerConfig(LIMIT, true, TODAY));
        givenRealPnl(new BigDecimal("-350000"));

        boolean allowed = service.allowEntry(true);

        assertThat(allowed).isFalse();
        verify(botConfigRepository, never()).tripDailyLossBreaker(anyString(), any());
        verify(telegram, never()).sendRisk(anyString());
    }

    @Test
    @DisplayName("한도 미달: 진입 허용")
    void underLimit_allows() {
        givenConfig(breakerConfig(LIMIT, true, null));
        givenRealPnl(new BigDecimal("-100000"));

        assertThat(service.allowEntry(true)).isTrue();
    }

    @Test
    @DisplayName("VIRTUAL: 활성 가상계좌로 합산, 계좌 없으면 손실 0 = 허용")
    void virtualMode_activeAccountOrZero() {
        givenConfig(breakerConfig(LIMIT, true, null));
        VirtualAccount account = new VirtualAccount();
        account.setId(77L);
        when(virtualAccountRepository.findFirstByIsActiveTrueOrderByIdDesc()).thenReturn(Optional.of(account));
        when(tradeHistoryRepository.sumRealizedPnlBetween(eq(77L), any(), any()))
                .thenReturn(new BigDecimal("-400000"));
        when(botConfigRepository.tripDailyLossBreaker(anyString(), any())).thenReturn(1);

        assertThat(service.allowEntry(false)).isFalse();  // 가상계좌 손실로 발동

        // 계좌 없음 → 손실 0 → 허용
        when(virtualAccountRepository.findFirstByIsActiveTrueOrderByIdDesc()).thenReturn(Optional.empty());
        givenConfig(breakerConfig(LIMIT, true, null));
        assertThat(service.allowEntry(false)).isTrue();
    }

    @Test
    @DisplayName("합산 조회 실패 + 미발동: fail-open 허용 + RISK 알림 / + 오늘 발동: 차단 유지")
    void queryFailure_failOpenUnlessTripped() {
        givenConfig(breakerConfig(LIMIT, true, null));
        when(tradeHistoryRepository.sumRealizedPnlBetween(any(), any(), any()))
                .thenThrow(new RuntimeException("DB down"));

        assertThat(service.allowEntry(true)).isTrue();          // 미발동 → fail-open
        verify(telegram, times(1)).sendRisk(anyString());        // 무력화 알림

        givenConfig(breakerConfig(LIMIT, true, TODAY));           // 이미 오늘 발동
        assertThat(service.allowEntry(true)).isFalse();          // 조회 실패에도 차단 유지
    }

    @Test
    @DisplayName("release: UPDATE 1행이면 해제+감사+텔레그램, 0행(미발동)이면 no-op")
    void release_idempotent() {
        when(botConfigRepository.releaseDailyLossBreaker("daily_loss_breaker")).thenReturn(1);
        assertThat(service.release("admin")).isTrue();
        verify(telegram, times(1)).sendRisk(anyString());

        when(botConfigRepository.releaseDailyLossBreaker("daily_loss_breaker")).thenReturn(0);
        assertThat(service.release("admin")).isFalse();
        verify(telegram, times(1)).sendRisk(anyString());  // 추가 발송 없음
    }

    @Test
    @DisplayName("isTrippedToday: 오늘=true, 어제/NULL=false")
    void isTrippedToday() {
        givenConfig(breakerConfig(LIMIT, true, TODAY));
        assertThat(service.isTrippedToday()).isTrue();
        givenConfig(breakerConfig(LIMIT, true, TODAY.minusDays(1)));
        assertThat(service.isTrippedToday()).isFalse();
        givenConfig(breakerConfig(LIMIT, true, null));
        assertThat(service.isTrippedToday()).isFalse();
    }
}
