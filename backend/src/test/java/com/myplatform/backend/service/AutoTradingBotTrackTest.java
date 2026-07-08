package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-6: 자동매매 봇 트랙 수 — 문서·코드 정합 가드.
 *
 * <p>활성 @Scheduled 트랙은 정확히 8개 — 전략 5(스캘핑 매수/매도/청산 + 스윙 매수/매도)
 * + 정규장 마감 강제청산(executeRegularSessionLiquidation, 윈도우 15:20~15:28)
 * + 청산 미완료 경고(warnIfLiquidationMissed, 15:29)
 * + NXT 방어 청산 재시도(executeNxtLiquidationRetry, 15:35~19:55 — 2026-07-08 추가, flag OFF 기본이라
 *   cron 은 발화하되 shouldRunNxtLiquidation 이 선차단 = 현행 무영향). 오버나잇 방지 + 리더 페일오버 캐치업.
 * 종가봇 매수/매도 2개는 비활성(@Scheduled 주석처리). 누군가 트랙을 켜고/끄면 이 테스트가 깨져
 * 문서(AutoTradingBotService 헤더 · STOCK_AZ_FULL.md §14 · NXT_ROUTING_DESIGN.md) 갱신을 강제한다.
 * (리더 선출 하트비트는 BotLeaderElectionService 소속이라 이 카운트에 미포함.)
 */
class AutoTradingBotTrackTest {

    private static final List<String> ACTIVE_TRACKS = List.of(
            "executeScalpingBuyLogic",
            "executeScalpingSellLogic",
            "executeScalpingClearance",
            "executeSwingBuyLogic",
            "executeSwingSellLogic",
            "executeRegularSessionLiquidation",
            "warnIfLiquidationMissed",
            "executeNxtLiquidationRetry");

    private static final List<String> INACTIVE_TRACKS = List.of(
            "executeClosingBuyLogic",
            "executeClosingSellLogic");

    private boolean hasScheduled(String methodName) {
        Method m = Arrays.stream(AutoTradingBotService.class.getDeclaredMethods())
                .filter(x -> x.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("메서드 없음(이름 변경?): " + methodName));
        return m.isAnnotationPresent(Scheduled.class);
    }

    @Test
    @DisplayName("활성 트랙 8개는 @Scheduled 보유")
    void activeTracksScheduled() {
        for (String name : ACTIVE_TRACKS) {
            assertThat(hasScheduled(name)).as("%s 는 활성(@Scheduled) 이어야 함", name).isTrue();
        }
    }

    @Test
    @DisplayName("종가봇 2개는 @Scheduled 미보유(비활성)")
    void closingTracksNotScheduled() {
        for (String name : INACTIVE_TRACKS) {
            assertThat(hasScheduled(name)).as("%s 는 비활성(@Scheduled 주석) 이어야 함", name).isFalse();
        }
    }

    @Test
    @DisplayName("@Scheduled 메서드 총 8개 — 활성 트랙 수 고정")
    void exactlyEightScheduled() {
        long count = Arrays.stream(AutoTradingBotService.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .count();
        assertThat(count).as("활성 @Scheduled 트랙은 정확히 8개(전략 5 + 정규장 강제청산 + 미완료 경고 + NXT 방어 청산)").isEqualTo(8);
    }
}
