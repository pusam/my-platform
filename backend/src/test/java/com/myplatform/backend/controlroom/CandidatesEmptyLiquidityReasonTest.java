package com.myplatform.backend.controlroom;

import com.myplatform.backend.service.RecommendationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 후보 0건 사유 — 컷은 넘었지만 거래대금 하한으로 전부 빠진 날을 '컷을 넘은 종목이 없다'로 말하지 않는다(2026-11-11). */
class CandidatesEmptyLiquidityReasonTest {

    private static final LocalDateTime STALE = LocalDateTime.of(2026, 11, 4, 17, 0);

    @Test
    @DisplayName("재현: 하한으로 전부 빠졌으면 그렇게 말한다 — '입력 노후'로도, '컷 통과 0건'으로도 읽히지 않게")
    void allBelowFloorIsNamed() {
        assertThat(ControlRoomSnapshotService.emptyReasonShort(true, true, STALE, true, 4))
                .isEqualTo("컷 통과 4건 모두 거래대금 하한 미달");
        assertThat(ControlRoomSnapshotService.emptyReason(true, STALE, true, true, 4))
                .contains("4종목이 모두 거래대금 하한").contains("입력 노후가 아니다").doesNotContain("넘은 종목이 없다");
    }

    @Test
    @DisplayName("하한과 무관한 0건·채점 이상은 종전 문구 그대로")
    void otherwiseUnchanged() {
        assertThat(ControlRoomSnapshotService.emptyReasonShort(true, true, STALE, true, 0)).isEqualTo("컷 통과 0건(관망)");
        assertThat(ControlRoomSnapshotService.emptyReasonShort(true, true, STALE, false, 0)).contains("입력 노후");
        assertThat(ControlRoomSnapshotService.emptyReasonShort(false, false, STALE, true, 3)).isNull();
    }

    @Test
    @DisplayName("로그 문구 — 하한으로 전부 빠진 계산과 컷 통과 0건을 가른다")
    void logNote() {
        assertThat(RecommendationService.emptyResultNoteForTest(new RecommendationService.LiquidityStats(5.0, 0, 3, 0)))
                .isEqualTo("컷 통과 3종목 모두 거래대금 하한 미달 → 후보 0건(관망)");
        assertThat(RecommendationService.emptyResultNoteForTest(new RecommendationService.LiquidityStats(5.0, 0, 0, 0)))
                .isEqualTo("컷 통과 0건(관망)");
        assertThat(RecommendationService.emptyResultNoteForTest(null)).isEqualTo("컷 통과 0건(관망)");
    }
}
