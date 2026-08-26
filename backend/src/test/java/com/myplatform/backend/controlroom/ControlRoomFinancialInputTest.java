package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재무 입력층 진단 문장 ({@link ControlRoomSnapshotService#financialInputNote}) — 2026-08-26.
 *
 * <p><b>이 카드가 생긴 이유</b>: KIS 손익계산서 응답의 금액 필드명이 틀려 434종목 전 기간의
 * 매출·영업이익·순이익·자본총계가 몇 달 동안 NULL 이었는데 <b>어느 화면에도 안 보였다</b>.
 * 종목별 WARN 로그는 있었지만 434줄을 세는 사람은 없다.
 *
 * <p>그래서 이 함수의 계약은 "정상이면 조용하고, 죽으면 어느 호출이 죽었는지 말한다"이다.
 * 정상일 때 시끄러우면 사람이 무시하게 되고, 그러면 다음 사망도 똑같이 지나간다.
 */
class ControlRoomFinancialInputTest {

    @Test
    @DisplayName("전부 정상이면 조용하다 — 정상일 때 시끄러우면 사람이 경고를 무시하게 된다")
    void healthyIsSilent() {
        assertThat(ControlRoomSnapshotService.financialInputNote(434, 434, 434, 434)).isNull();
        assertThat(ControlRoomSnapshotService.financialInputNoteDetail(434, 434, 434, 434)).isNull();
    }

    @Test
    @DisplayName("2026-08-26 실측 재현 — 비율만 살고 손익계산서·재무상태표가 0")
    void reproducesTheRealIncident() {
        // 카드 표면은 짧게 — 6칸 그리드에서 4줄짜리 문장은 카드를 혼자 늘린다
        assertThat(ControlRoomSnapshotService.financialInputNote(434, 434, 0, 0))
                .isEqualTo("손익·재무상태표 0/434 — 응답 필드 불일치 의심");

        // 상세는 "무엇을 해야 하는지"까지 — 툴팁과 크루 컨텍스트가 이걸 받는다
        assertThat(ControlRoomSnapshotService.financialInputNoteDetail(434, 434, 0, 0))
                .contains("0/434")
                // 진단의 핵심 — 비율이 살아 있으니 API 사망이 아니라 필드 불일치다
                .contains("필드명 불일치")
                .contains("[손익계산서 스키마]");
    }

    @Test
    @DisplayName("3종이 전부 0 이면 필드 문제가 아니라 토큰·키 장애로 안내한다")
    void allDeadPointsAtCredentials() {
        assertThat(ControlRoomSnapshotService.financialInputNote(434, 0, 0, 0)).contains("토큰");
        assertThat(ControlRoomSnapshotService.financialInputNoteDetail(434, 0, 0, 0))
                .contains("토큰")
                .doesNotContain("필드명 불일치");
    }

    @Test
    @DisplayName("한 호출만 죽으면 그 호출만 지목한다")
    void singleCallDeath() {
        assertThat(ControlRoomSnapshotService.financialInputNote(434, 434, 0, 434))
                .isEqualTo("손익계산서 0/434");
        assertThat(ControlRoomSnapshotService.financialInputNote(434, 434, 434, 0))
                .isEqualTo("재무상태표 0/434");
        assertThat(ControlRoomSnapshotService.financialInputNote(434, 0, 434, 434))
                .isEqualTo("재무비율 0/434");
    }

    @Test
    @DisplayName("전멸이 아니라 절반 미만으로 조용히 퇴화하는 것도 잡는다")
    void partialDegradationIsCaught() {
        assertThat(ControlRoomSnapshotService.financialInputNote(434, 434, 200, 434))
                .contains("절반 미만")
                .contains("200/434");
        assertThat(ControlRoomSnapshotService.financialInputNoteDetail(434, 434, 200, 434))
                .contains("조용한 퇴화");
    }

    @Test
    @DisplayName("절반을 넘기면 조용 — 부분 결손은 늘 있고 그때마다 울리면 신호가 아니라 소음이다")
    void aboveHalfIsSilent() {
        assertThat(ControlRoomSnapshotService.financialInputNote(434, 434, 300, 434)).isNull();
    }

    @Test
    @DisplayName("행이 0이면 '결측 0건'이 아니라 '행 없음' — 두 상태를 섞지 않는다(§4c)")
    void zeroRowsIsItsOwnState() {
        assertThat(ControlRoomSnapshotService.financialInputNote(0, 0, 0, 0)).isEqualTo("행 없음");
        assertThat(ControlRoomSnapshotService.financialInputNoteDetail(0, 0, 0, 0))
                .contains("한 번도 안 돌았거나");
    }
}
