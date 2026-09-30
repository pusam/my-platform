package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 신뢰 게이트(⑦) 표본 경계 — {@link ControlRoomSnapshotService#resolveSampleSince} ·
 * {@link ControlRoomSnapshotService#sampleBoundaryCaveat}(2026-10-01).
 *
 * <p>9/29~10/1 에 추천 입력(실적 전년동기 가드·성장률·거래정지 게이트·영업이익률 오독·지배주주 PER·결산월)이 잇달아
 * 바뀌었다. 게이트가 그 전 183건과 그 뒤 표본을 한 집계로 접으면 옛 산식 성적이 새 산식 성적으로 읽힌다 — 그래서
 * 경계 이후만 판정하고, 경계 이전은 "참고"로 따로 보여준다(숨기면 "근거 없음"으로 읽힌다).
 */
class ControlRoomTrustGateBoundaryTest {

    @Test
    @DisplayName("기본 경계는 2026-10-02 — 마지막 입력 수정(결산월)이 재무 수집·추천 스냅샷까지 통과한 뒤 첫 온전한 거래일")
    void defaultBoundary() {
        assertThat(ControlRoomSnapshotService.TRUST_GATE_SAMPLE_SINCE_DEFAULT).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(ControlRoomSnapshotService.resolveSampleSince(null)).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(ControlRoomSnapshotService.resolveSampleSince("  ")).isEqualTo(LocalDate.of(2026, 10, 2));
    }

    @Test
    @DisplayName("설정으로 옮길 수 있다 — 입력 정의가 또 바뀌면 경계도 따라 움직여야 한다")
    void configuredBoundary() {
        assertThat(ControlRoomSnapshotService.resolveSampleSince("2026-11-03")).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(ControlRoomSnapshotService.resolveSampleSince(" 2026-11-03 ")).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    @DisplayName("잘못된 설정은 경계를 없애지 않는다 — 기본값으로")
    void invalidConfigFallsBackToDefault() {
        assertThat(ControlRoomSnapshotService.resolveSampleSince("어제")).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(ControlRoomSnapshotService.resolveSampleSince("2026/10/02")).isEqualTo(LocalDate.of(2026, 10, 2));
    }

    @Test
    @DisplayName("설명문 — 경계 날짜·이유·이전 산식 참고치·통과 조건·슬리피지 미포함을 전부 적는다")
    void caveatSpellsEverythingOut() {
        var legacy = new ControlRoomSnapshotDto.LegacyReference("2026-06-25", "2026-10-02", 183, 44, 35,
                new BigDecimal("-4.33"), new BigDecimal("0.17"), "EVALUABLE");

        String text = ControlRoomSnapshotService.sampleBoundaryCaveat(LocalDate.of(2026, 10, 2), legacy);

        assertThat(text)
                .contains("2026-10-02 이후 기록분(현재 산식)")
                .contains("결산월")
                .contains("183건/고유 44일·대조군 35건")
                .contains("비용차감 -4.33%")
                .contains("대조군比 +0.17%")
                .contains("현재 판정에 섞지 않는다")
                .contains("30건·고유 10일을 채워도 통과가 아니다")
                .contains("슬리피지는 빠져 있다");
    }

    @Test
    @DisplayName("경계 이전 행이 없으면 '없음'이라고 적는다 — 참고치를 0 으로 위장하지 않는다(§4c)")
    void caveatWithoutLegacy() {
        String text = ControlRoomSnapshotService.sampleBoundaryCaveat(LocalDate.of(2026, 10, 2), null);
        assertThat(text).contains("경계 이전 평가 행 없음").doesNotContain("이전 산식(");
    }
}
