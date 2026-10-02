package com.myplatform.backend.controlroom;

import com.myplatform.backend.service.BotPerformanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제실 봇 성적 카드 — 표면 한 줄과 DTO 옮기기({@code ControlRoomSnapshotService.botGateNote}·{@code toBotGateLine}).
 *
 * <p>봇이 꺼져 있으면 그 성적은 "지금 봇"이 아니라 꺼지기 전 기록이다 — 카드가 그걸 먼저 말해야 한다(2026-10-02 실측:
 * 7/27 15:20 이후 꺼짐, 7/3~7/27 모의 매도 114건).
 */
class ControlRoomBotGateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

    @Test
    @DisplayName("봇이 꺼져 있으면 꺼진 날짜와 '새 표본이 쌓이지 않는다'를 먼저 말한다")
    void botOffIsTheHeadline() {
        String note = ControlRoomSnapshotService.botGateNote(false,
                LocalDateTime.of(2026, 7, 27, 15, 20, 15), LocalDate.of(2026, 7, 27), TODAY);

        assertThat(note).isEqualTo("봇 꺼짐 — 2026-07-27부터 · 새 표본이 쌓이지 않는다");
    }

    @Test
    @DisplayName("켜져 있어도 마지막 매도가 2주 넘게 전이면 '최근 거래 없음' — 정상이면 조용하다(null)")
    void staleTradesWhileOnAndQuietWhenNormal() {
        assertThat(ControlRoomSnapshotService.botGateNote(true, null, LocalDate.of(2026, 9, 10), TODAY))
                .isEqualTo("최근 거래 없음 — 마지막 매도 2026-09-10");
        assertThat(ControlRoomSnapshotService.botGateNote(true, null, LocalDate.of(2026, 9, 25), TODAY)).isNull();
        assertThat(ControlRoomSnapshotService.botGateNote(null, null, null, TODAY)).isNull();
    }

    @Test
    @DisplayName("판정을 그대로 옮긴다 — 화면이 다시 계산할 숫자를 남기지 않는다")
    void mapsVerdictWithoutRecomputation() {
        BotGateRules.Verdict v = BotGateRules.judge(List.of(
                new BotGateRules.TradeOutcome(LocalDate.of(2026, 7, 3), "SCALPING", new BigDecimal("-0.5")),
                new BotGateRules.TradeOutcome(LocalDate.of(2026, 7, 6), "SWING", new BigDecimal("1.2"))),
                new BigDecimal("-2.10"), 1);
        BotPerformanceService.BotGateLine line = new BotPerformanceService.BotGateLine("VIRTUAL", true, 25L,
                new BigDecimal("10000000"), new BigDecimal("-799916"),
                LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 6), v, null);

        ControlRoomSnapshotDto.BotGateLine dto = ControlRoomSnapshotService.toBotGateLine(line);

        assertThat(dto.dataAvailable()).isTrue();
        assertThat(dto.state()).isEqualTo("COLLECTING");
        assertThat(dto.trades()).isEqualTo(2);
        assertThat(dto.distinctDays()).isEqualTo(2);
        assertThat(dto.dailyMeanPct()).isEqualByComparingTo("0.35");
        assertThat(dto.maxDrawdownPct()).isEqualByComparingTo("-2.10");
        assertThat(dto.realizedPnlKrw()).isEqualTo(-799_916L);
        assertThat(dto.firstTradeDay()).isEqualTo("2026-07-03");
        assertThat(dto.lastTradeDay()).isEqualTo("2026-07-06");
        assertThat(dto.strategies()).extracting(BotGateRules.StrategyLine::strategy).containsExactly("SCALPING", "SWING");
        assertThat(dto.excludedTrades()).isEqualTo(1);
        assertThat(dto.note()).isEqualTo(v.headline());
        assertThat(dto.noteDetail()).isEqualTo(v.detail());
    }

    @Test
    @DisplayName("계좌 단위 안내(활성 가상계좌 없음 등)는 헤드라인 앞에 붙인다")
    void lineNotePrefixesHeadline() {
        BotPerformanceService.BotGateLine line = new BotPerformanceService.BotGateLine("VIRTUAL", true, null,
                null, BigDecimal.ZERO, null, null, BotGateRules.judge(List.of(), null, 0),
                "활성 가상계좌 없음 — 모의 봇 거래 0건");

        assertThat(ControlRoomSnapshotService.toBotGateLine(line).note())
                .startsWith("활성 가상계좌 없음 — 모의 봇 거래 0건 — 표본 수집 중");
    }

    @Test
    @DisplayName("집계 실패한 줄은 dataAvailable=false + 실패 사유 — '거래 없음'으로 위장하지 않는다(§4c)")
    void failedLineIsNotZeroTrades() {
        BotPerformanceService.BotGateLine failed = new BotPerformanceService.BotGateLine("REAL", false, null,
                null, null, null, null, null, "집계 실패 (QueryTimeoutException)");

        ControlRoomSnapshotDto.BotGateLine dto = ControlRoomSnapshotService.toBotGateLine(failed);

        assertThat(dto.dataAvailable()).isFalse();
        assertThat(dto.state()).isNull();
        assertThat(dto.note()).isEqualTo("집계 실패 (QueryTimeoutException)");
        assertThat(dto.noteDetail()).contains("측정 자체가 실패했다");
    }
}
