package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제실 '재무 입력층' — 재무제표 값을 이어 쓴 종목이 있으면 그 수와 수집일을 말한다(2026-11-13, V79).
 *
 * <p>KIS 재무 호출이 실패한 날 직전 수집값을 이어 쓰면 충전율은 정상으로 보인다 — 이 문구가 없으면 장애가 숨는다.
 */
class ControlRoomFinancialCarriedNoteTest {

    @Test
    @DisplayName("이어 쓴 값이 없으면 문구는 그대로다 — 정상이면 조용하다(null)")
    void quietWhenNothingCarried() {
        assertThat(ControlRoomSnapshotService.withCarriedNote(null, 0, 2602, null)).isNull();
        assertThat(ControlRoomSnapshotService.withCarriedNote("손익계산서 0/2602", 0, 2602, null)).isEqualTo("손익계산서 0/2602");
        assertThat(ControlRoomSnapshotService.withCarriedDetail("원래 설명", 0, 2602, null)).isEqualTo("원래 설명");
    }

    @Test
    @DisplayName("충전율이 정상이어도 이어 쓴 종목 수와 가장 오래된 수집일을 말한다")
    void carriedIsSaidEvenWhenCoverageLooksNormal() {
        String note = ControlRoomSnapshotService.withCarriedNote(null, 1986, 2602, LocalDate.of(2026, 11, 11));

        assertThat(note).isEqualTo("이어 쓴 값 1986/2602 (11/11 수집분부터)");
        assertThat(ControlRoomSnapshotService.withCarriedDetail("", 1986, 2602, LocalDate.of(2026, 11, 11)))
                .contains("1986종목").contains("2026-11-11").contains("30일").contains("DART");
    }

    @Test
    @DisplayName("다른 경고가 있으면 뒤에 덧붙인다 — 앞 경고를 지우지 않는다")
    void appendedAfterAnExistingNote() {
        assertThat(ControlRoomSnapshotService.withCarriedNote("충전율 절반 미만 (최저 533/2602)", 40, 2602, null))
                .isEqualTo("충전율 절반 미만 (최저 533/2602) · 이어 쓴 값 40/2602");
    }
}
